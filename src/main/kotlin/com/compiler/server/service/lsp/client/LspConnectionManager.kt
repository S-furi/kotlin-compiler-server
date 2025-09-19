package com.compiler.server.service.lsp.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ConnectException
import java.net.Socket
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import kotlin.random.Random


internal class LspConnectionManager(
    private val host: String,
    private val port: Int,
    private val languageClient: LanguageClient = KotlinLanguageClient(),
    private val maxConnectionRetries: Int = 5,
): AutoCloseable {
    private val logger = LoggerFactory.getLogger(this::class.java)

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var serverProxy: LanguageServer? = null
    @Volatile
    private var listenFuture: Future<Void>? = null

    private val isClosing = AtomicBoolean(false)

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val disconnectListeners = mutableListOf<() -> Unit>()
    private val reconnectListeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun ensureConnected(): LanguageServer {
        if (isClosing.get()) error("Connection manager is closing or already closed")
        serverProxy?.let { return it }
        connect()
        return serverProxy ?: error("Could not connect to LSP server")
    }

    private fun connect() {
        var attempt = 0
        while (attempt < maxConnectionRetries && !isClosing.get()) {
            try {
                val s = Socket(host, port).apply {
                    tcpNoDelay = true
                    keepAlive = true
                    soTimeout = 0
                    setPerformancePreferences(0, 2, 1)
                }
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())
                val launcher = LSPLauncher.createClientLauncher(languageClient, input, output)
                launcher.startListening().also {
                    listenFuture = it
                    watchConnection(it)
                }
                socket = s
                serverProxy = launcher.remoteProxy
                notifyReconnected()
                return
            } catch (e: Exception) {
                if (e is ConnectException || e.cause is ConnectException) {
                    logger.info("Could not connect to LSP server: ${e.message}")
                } else {
                    logger.warn("Unexpected error while connecting to LSP server:", e)
                }
                Thread.sleep(exponentialBackoffMillis(attempt++).toLong())
                logger.info("Trying reconnect, attempt {} of {}", attempt, maxConnectionRetries)
            }
        }
        throw ConnectException("Could not connect to LSP server after $maxConnectionRetries attempts")
    }

    private fun watchConnection(future: Future<Void>) {
        scope.launch {
            try {
                future.get()
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    logger.info("LSP listening job finished with exception:", t)
                }
            } finally {
                handleDisconnectAndReconnect()
            }
        }
    }

    private fun handleDisconnectAndReconnect() {
        if (isClosing.get()) return
        notifyDisconnected()
        tearDown()

        var attempt = 0
        while (!isClosing.get() && attempt < maxConnectionRetries) {
            try {
                if (attempt > 0) Thread.sleep(exponentialBackoffMillis(attempt).toLong())
                connect()
                return
            } catch (t: Throwable) {
                logger.info("Reconnect attempt {} failed: {}", ++attempt, t.message)
            }
        }
    }

    private fun notifyDisconnected() = notify(disconnectListeners)

    private fun notifyReconnected() = notify(reconnectListeners)

    private fun notify(listeners: List<() -> Unit>) {
        runCatching { listeners.forEach {
            runCatching { it() } }
        }
    }

    private fun tearDown() {
        runCatching { listenFuture?.cancel(true) }
        runCatching { socket?.close() }
        listenFuture = null
        socket = null
        serverProxy = null
    }

    fun addOnDisconnectListener(listener: () -> Unit) {
        disconnectListeners += listener
    }

    fun addOnReconnectListener(listener: () -> Unit) {
        reconnectListeners += listener
    }

    override fun close() {
        isClosing.set(true)
        tearDown()
    }

    companion object {

        /**
         * Basic exponential backoff with jitter (+/-30%), up to 1000ms.
         */
        internal fun exponentialBackoffMillis(attempt: Int): Double {
            val base = 100.0 * 2.0.pow(attempt)
            val jitter = Random.nextDouble(from = -0.3, until = 0.3)
            val withJitter = base * (1.0 + jitter)
            return withJitter.coerceAtMost(500.0)
        }
    }
}