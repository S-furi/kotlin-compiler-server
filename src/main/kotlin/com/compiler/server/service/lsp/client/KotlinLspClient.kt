package com.compiler.server.service.lsp.client

import com.compiler.server.service.lsp.KotlinLspProxy
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

class KotlinLspClient : AutoCloseable {

    private val languageClient = KotlinLanguageClient()
    internal val languageServer: LanguageServer by lazy { getRemoteLanguageServer() }
    private lateinit var stopFuture: Future<Void>
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val maxLspConnectRetries = 5
    private val socket by lazy {
        var attempt = 0
        do {
            try {
                return@lazy Socket(KotlinLspProxy.LSP_HOST, KotlinLspProxy.LSP_PORT)
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

    fun initRequest(kotlinProjectRoot: String, projectName: String = "None"): CompletableFuture<Void> {
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

    fun getCompletion(
        uri: String,
        position: Position,
        triggerKind: CompletionTriggerKind = CompletionTriggerKind.Invoked,
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

    /**
     * Completion request on `textDocument/completion`, as [getCompletion].
     * This method retries the request if it fails due to a [ResponseErrorException] with code
     * [ResponseErrorCode.RequestFailed] and when the failing reason is due to the document not
     * yet present in the language server (i.e. `didOpen` has not been called yet or the notification
     * has not yet arrived). This method helps in situations where the language server is busy processing
     * clients' requests and the completion request is received before the `didOpen` notification is processed.
     */
    suspend fun getCompletionsWithRetry(
        uri: String,
        position: Position,
        triggerKind: CompletionTriggerKind = CompletionTriggerKind.Invoked,
        maxRetries: Int = 3,
    ): List<CompletionItem> {
        var attempt = 0

        do {
            runCatching {
                return getCompletion(uri, position, triggerKind).await()
            }.onFailure { e ->
                when (e) {
                    is ResponseErrorException if (e.responseError.code == ResponseErrorCode.RequestFailed.value) -> {
                        if (e.message?.startsWith("Document with url FileUrl(url='$uri'") ?: false) {
                            logger.warn("Failed to get completions, retrying... (${attempt + 1}/$maxRetries)", e)
                            delay(exponentialBackoffMillis(attempt).milliseconds)
                            attempt++
                        }
                    }
                    else -> {
                        logger.error("Failed to get completions", e)
                        return emptyList()
                    }
                }
            }
        } while (attempt < maxRetries)
        return emptyList()
    }

    fun shutdown(): CompletableFuture<Any> = languageServer.shutdown()

    fun exit() {
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

    private fun getRemoteLanguageServer(): LanguageServer{
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val launcher = LSPLauncher.createClientLauncher(languageClient, input, output)
        stopFuture = launcher.startListening()
        return launcher?.remoteProxy ?: throw RuntimeException("Cannot connect to server")
    }

    override fun close() = runBlocking {
        shutdown().await()
        exit()
    }

    private fun exponentialBackoffMillis(attempt: Int): Double = 1000.0 * 2.0.pow(attempt)

    companion object Companion {

        /**
         * Creates and initialize an LSP client.
         *
         * [kotlinProjectRoot] is the path ([[java.net.URI.path]]) to the root project directory,
         * where the project must be a project supported by [Kotlin-LSP](https://github.com/Kotlin/kotlin-lsp).
         * The workspace will not contain users' files, but it can be used to store common files,
         * to specify kotlin/java versions, project-wide imported libraries and so on.
         *
         * @param kotlinProjectRoot the path to the workspace directory, namely the root of the common project
         * @param projectName the name of the project
         */
        suspend fun create(kotlinProjectRoot: String, projectName: String = "None"): KotlinLspClient {
            return KotlinLspClient().apply {
                initRequest(kotlinProjectRoot, projectName).await()
            }
        }
    }
}

object DocumentSync {
    fun KotlinLspClient.openDocument(uri: String, content: String, version: Int = 1, languageId: String = "kotlin") {
        languageServer.textDocumentService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, languageId, version, content)
            )
        )
    }

    fun KotlinLspClient.changeDocument(uri: String, newContent: String, version: Int = 1) {
        if (uri.isEmpty()) return
        val params = DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, version),
            listOf(TextDocumentContentChangeEvent(newContent)),
        )
        languageServer.textDocumentService.didChange(params)
    }

    fun KotlinLspClient.closeDocument(uri: String) {
        languageServer.textDocumentService.didClose(
            DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
        )
    }
}