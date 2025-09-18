package com.compiler.server.service.lsp.client

import com.compiler.server.service.lsp.KotlinLspProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ConnectException
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KotlinLspClient : LspClient {

    private val languageClient = KotlinLanguageClient()
    internal val languageServer: LanguageServer by lazy { getRemoteLanguageServer() }
    private lateinit var stopFuture: Future<Void>
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val maxLspConnectRetries = 5
    private val socket by lazy {
        var attempt = 0
        do {
            try {
                return@lazy Socket(KotlinLspProxy.LSP_HOST, KotlinLspProxy.LSP_PORT).apply {
                    tcpNoDelay = true
                    keepAlive = true
                    soTimeout = 0
                    receiveBufferSize = 256 * 1024
                    sendBufferSize = 256 * 1024
                    setPerformancePreferences(0, 2, 1)
                }
            } catch (e: Exception) {
                when (e) {
                    is ConnectException -> {
                        logger.info("Connection to LSP server failed, retrying... ($attempt / $maxLspConnectRetries)")
                        Thread.sleep(exponentialBackoffMillis(attempt).toLong())
                        attempt++
                    }

                    else -> throw e
                }
            }
        } while (attempt < maxLspConnectRetries)
        throw ConnectException("Could not connect to LSP server after $maxLspConnectRetries attempts")
    }

    override var initialized: Boolean = false

    override fun initRequest(kotlinProjectRoot: String, projectName: String): CompletableFuture<Void> {
        val capabilities = getCompletionCapabilities()
        val workspaceFolders = listOf(WorkspaceFolder("file://$kotlinProjectRoot", projectName))

        val params = InitializeParams().apply {
            this.capabilities = capabilities
            this.workspaceFolders = workspaceFolders
        }

        logger.info("Initializing LSP client...")

        return languageServer.initialize(params)
            .thenCompose { res ->
                logger.debug(">>> Initialization response from server:\n{}", res)
                languageServer.initialized(InitializedParams())
                logger.info("LSP client initialized with workspace={}, name={}", kotlinProjectRoot, projectName)

                CompletableFuture.completedFuture(null)
            }
    }

    override fun getCompletion(
        uri: String,
        position: Position,
        triggerKind: CompletionTriggerKind,
    ): CompletableFuture<List<CompletionItem>> {
        val context = CompletionContext(triggerKind)
        val params = CompletionParams(
            TextDocumentIdentifier(uri),
            position,
            context
        )

        return languageServer.textDocumentService.completion(params)
            .thenCompose { res ->
                when {
                    res.isLeft -> res.left
                    res.isRight -> res.right.items
                    else -> emptyList()
                }.let { CompletableFuture.completedFuture(it) }
            }
    }

    override suspend fun getCompletionsWithRetry(
        uri: String,
        position: Position,
        triggerKind: CompletionTriggerKind,
        maxRetries: Int,
    ): List<CompletionItem> {
        var attempt = 0

        do {
            try {
                return withTimeout(1.5.seconds) {
                    getCompletion(uri, position, triggerKind).awaitCancellable()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                when (e) {
                    is ResponseErrorException if (e.responseError.code == ResponseErrorCode.RequestFailed.value) -> {
                        if (e.message?.startsWith("Document with url FileUrl(url='$uri'") ?: false) {
                            logger.info("Failed to get completions (document not ready), retrying... (${attempt + 1}/$maxRetries)")
                            delay(exponentialBackoffMillis(attempt).milliseconds)
                            attempt++
                            continue
                        }
                    }
                }
                logger.warn("Failed to get completions (non-retriable)", e)
                return emptyList()
            }
        } while (attempt < maxRetries)
        return emptyList()
    }

    override fun openDocument(uri: String, content: String, version: Int, languageId: String) {
        languageServer.textDocumentService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, languageId, version, content)
            )
        )
    }

    override fun changeDocument(uri: String, newContent: String, version: Int) {
        if (uri.isEmpty()) return
        val params = DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, version),
            listOf(TextDocumentContentChangeEvent(newContent)),
        )
        languageServer.textDocumentService.didChange(params)
    }

    override fun closeDocument(uri: String) {
        languageServer.textDocumentService.didClose(
            DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
        )
    }

    override fun shutdown(): CompletableFuture<Any> = languageServer.shutdown()

    override fun exit() {
        languageServer.exit()
        stopFuture.cancel(true)
        socket.close()
    }

    private fun getCompletionCapabilities() = ClientCapabilities().apply {
        textDocument = TextDocumentClientCapabilities().apply {
            completion =
                CompletionCapabilities(
                    CompletionItemCapabilities(true)
                ).apply { contextSupport = true }
        }
        workspace = WorkspaceClientCapabilities().apply { workspaceFolders = true }
    }

    private fun getRemoteLanguageServer(): LanguageServer {
        val input = BufferedInputStream(socket.getInputStream(), 256 * 1024)
        val output = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)
        val launcher = LSPLauncher.createClientLauncher(languageClient, input, output)
        stopFuture = launcher.startListening()
        return launcher?.remoteProxy ?: throw RuntimeException("Cannot connect to server")
    }

    override fun close() = runBlocking {
        shutdown().await()
        exit()
    }

    /**
     * Basic exponential backoff with jitter (+/-30%), up to 1000ms.
     */
    private fun exponentialBackoffMillis(attempt: Int): Double {
        val base = 100.0 * 2.0.pow(attempt)
        val jitter = Random.nextDouble(from = -0.3, until = 0.3)
        val withJitter = base * (1.0 + jitter)
        return withJitter.coerceAtMost(500.0)
    }

    private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T =
        suspendCancellableCoroutine { continuation ->
            this.whenComplete { value, throwable ->
                if (throwable == null) continuation.resume(value)
                else continuation.resumeWithException(throwable)
            }
            continuation.invokeOnCancellation { this.cancel(true) }
        }
}
