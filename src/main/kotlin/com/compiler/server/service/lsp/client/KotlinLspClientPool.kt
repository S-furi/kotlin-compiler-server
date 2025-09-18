package com.compiler.server.service.lsp.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionTriggerKind
import org.eclipse.lsp4j.Position
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class KotlinLspClientPool(
    val kotlinProjectRoot: String,
    val projectName: String,
    private val coroutineScope: CoroutineScope,
    private val nClients: Int,
    private val permitsPerClient: Int,
) : LspClient {
    private val initDeferred = CompletableDeferred<Unit>()
    private lateinit var clientPool: List<LspClient>
    private val clientsPermits = ConcurrentHashMap<LspClient, Semaphore>()

    init {
        coroutineScope.launch {
            clientPool = List(nClients) {
                LspClient.createSingle(kotlinProjectRoot, projectName)
                    .also { client -> clientsPermits[client] = Semaphore(permitsPerClient) }
            }
            initDeferred.complete(Unit)
        }
    }

    override var initialized: Boolean = false
        get() = initDeferred.isCompleted

    override fun initRequest(
        kotlinProjectRoot: String,
        projectName: String
    ): CompletableFuture<Void> =
        CompletableFuture.completedFuture(null)

    override fun getCompletion(
        uri: String,
        position: Position,
        triggerKind: CompletionTriggerKind
    ) = withScopeAsCompletableFuture {
        withClient { c -> c.getCompletion(uri, position, triggerKind).await() } ?: emptyList()
    }

    override suspend fun getCompletionsWithRetry(
        uri: String,
        position: Position,
        triggerKind: CompletionTriggerKind,
        maxRetries: Int
    ): List<CompletionItem> =
        withClient { c -> c.getCompletionsWithRetry(uri, position, triggerKind, maxRetries) } ?: emptyList()

    override fun openDocument(
        uri: String,
        content: String,
        version: Int,
        languageId: String
    ) {
        withScopeAsCompletableFuture {
            withClient { c -> c.openDocument(uri, content, version, languageId) }
        }
    }

    override fun changeDocument(uri: String, newContent: String, version: Int) {
        withScopeAsCompletableFuture {
            withClient { c -> c.changeDocument(uri, newContent, version) }
        }
    }

    override fun closeDocument(uri: String) {
        withScopeAsCompletableFuture {
            withClient { c -> c.closeDocument(uri) }
        }
    }

    override fun shutdown(): CompletableFuture<Any> =
        clientPool.map { it.shutdown() }.reduceOrNull { acc, future -> acc.thenCombine(future) { _, _ -> } }?.thenApply { }
            ?: CompletableFuture.completedFuture(null)

    override fun exit() =
        clientPool.forEach { it.exit() }

    private suspend fun<T> withClient(body: suspend (LspClient) -> T): T? {
        if (!initDeferred.isCompleted) {
            initDeferred.await()
        }

        val client =
            clientsPermits.maxByOrNull { it.value.availablePermits }?.takeIf { it.value.availablePermits > 0 }?.key
                ?: clientPool.random()

        return clientsPermits[client]?.withTimeoutPermit { body(client) }
    }

    private fun<T> withScopeAsCompletableFuture(body: suspend () -> T) = coroutineScope.async { body() }.asCompletableFuture()

    companion object Companion {
        const val PERMITS_PER_CLIENT = 10
        const val DEFAULT_POOL_SIZE = 4
        const val ACQUIRE_TIMEOUT_MS = 50L
    }

    private suspend fun<T> Semaphore.withTimeoutPermit(action: suspend () -> T): T? =
        withTimeoutOrNull(ACQUIRE_TIMEOUT_MS) {
            try {
                acquire()
                action()
            } finally {
                release()
            }
        }
}