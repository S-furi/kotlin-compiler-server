package com.compiler.server.service.lsp.client

import com.compiler.server.service.lsp.KotlinLspProxy
import com.compiler.server.service.lsp.client.LspConnectionManager.Companion.exponentialBackoffMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageServer
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KotlinLspClient : RetriableLspClient {

    private val logger = LoggerFactory.getLogger(this::class.java)
    private val connectionManager = LspConnectionManager(
        host = KotlinLspProxy.LSP_HOST,
        port = KotlinLspProxy.LSP_PORT,
    )

    private val disconnectListeners = mutableListOf<() -> Unit>()
    private val reconnectListeners = mutableListOf<() -> Unit>()

    private val languageServer: LanguageServer
        get() = connectionManager.ensureConnected()

    private lateinit var initParams: InitializeParams

    init {
        connectionManager.addOnDisconnectListener {
            logger.warn("Lost connection to LSP server, reconnecting...")
            disconnectListeners.forEach { runCatching { it() } }
        }

        connectionManager.addOnReconnectListener {
            logger.info("Reconnected to LSP server, re-initializing client...")
            if (::initParams.isInitialized) {
                runCatching {
                    languageServer.initialize(initParams).join()
                    languageServer.initialized(InitializedParams())
                    logger.info("LSP client re-initialized")
                }.onFailure { logger.warn("Re-initialize LSP client failed: ${it.message}") }
            }
        }
        reconnectListeners.forEach { runCatching { it() } }
    }

    override fun initRequest(kotlinProjectRoot: String, projectName: String): CompletableFuture<Void> {
        val capabilities = getCompletionCapabilities()
        val workspaceFolders = listOf(WorkspaceFolder("file://$kotlinProjectRoot", projectName))

        val params = InitializeParams().apply {
            this.capabilities = capabilities
            this.workspaceFolders = workspaceFolders
        }

        logger.debug("Initializing LSP client...")

        return languageServer.initialize(params)
            .thenCompose { res ->
                logger.debug(">>> Initialization response from server:\n{}", res)
                languageServer.initialized(InitializedParams())
                logger.info("LSP client initialized with workspace={}, name={}", kotlinProjectRoot, projectName)
                initParams = params
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
                return withTimeout(10.seconds) {
                    getCompletion(uri, position, triggerKind).awaitCancellable()
                }
            } catch (e: Throwable) {
                when (e) {
                    is CompletionException, is ResponseErrorException -> {
                        if ((e as? ResponseErrorException)?.responseError?.code != ResponseErrorCode.RequestFailed.value) {
                            break
                        }
                        if (e.message?.contains("Document with url FileUrl(url='$uri'") ?: false) {
                            logger.info("Failed to get completions (document not ready), retrying... (${attempt + 1}/$maxRetries)")
                            delay(exponentialBackoffMillis(attempt = attempt, base = 1000.0).milliseconds)
                            attempt++
                            continue
                        }
                    }
                }
                logger.warn("Failed to get completions", e)
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
        runCatching { languageServer.exit() }
        connectionManager.close()
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

    override fun close() = runBlocking {
        shutdown().await()
        exit()
    }

    private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T =
        suspendCancellableCoroutine { continuation ->
            this.whenComplete { value, throwable ->
                if (throwable == null) continuation.resume(value)
                else continuation.resumeWithException(throwable)
            }
            continuation.invokeOnCancellation { this.cancel(true) }
        }

    override fun addOnDisconnectListener(listener: () -> Unit) {
        disconnectListeners += listener
    }

    override fun addOnReconnectListener(listener: () -> Unit) {
        reconnectListeners += listener
    }
}