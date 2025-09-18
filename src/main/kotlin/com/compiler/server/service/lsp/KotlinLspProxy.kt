package com.compiler.server.service.lsp

import com.compiler.server.compiler.components.lsp.LspProject
import com.compiler.server.model.Project
import com.compiler.server.model.ProjectFile
import com.compiler.server.service.lsp.KotlinLspProxy.Companion.LSP_USERS_PROJECTS_ROOT
import com.compiler.server.service.lsp.client.LspClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.Position
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Component
class KotlinLspProxy {

    internal lateinit var lspClient: LspClient
    internal val lspProjects = ConcurrentHashMap<Project, LspProject>()

    @EventListener(ApplicationReadyEvent::class)
    fun initClientOnReady() {
        CoroutineScope(Dispatchers.IO).launch {
            lspClient = LspClient.createSingle(
                LSP_USERS_PROJECTS_ROOT.path,
                "kotlin-compiler-server",
            )
        }
    }

    fun isLspClientConnected(): Boolean = ::lspClient.isInitialized

    /**
     * Retrieve completions for a given line and character position in a project file.
     * The document will be opened, completion triggered and then closed.
     *
     * This modality is aimed for **stateless** scenarios where we don't care about
     * the identity of the client and the project.
     *
     * @param project the project containing the file
     * @param line the line number
     * @param ch the character position
     * @return a list of [CompletionItem]s
     */
    suspend fun getOneTimeCompletions(project: Project, line: Int, ch: Int): List<CompletionItem> {
        val lspProject = lspProjects.getOrPut(project) { createNewProject(project) }
        val projectFile = project.files.first() // we assume projects can have just a single file
        val uri = lspProject.getDocumentUri(projectFile.name) ?: return emptyList()
        lspClient.openDocument(uri, projectFile.text)
        return with(this.lspClient) {
            getCompletions(lspProject, line, ch, projectFile.name)
                .also { closeProject(project) }
        }
    }

    /**
     * Retrieve completions for a given line and character position in a project file. By now
     *
     * - we assume that the project contains a single file
     * - changes arrive **before** completion is triggered
     *
     * Changes are not incremental, whole file content is transmitted. Future support
     * for incremental changes may be added when [Kotlin-LSP](https://github.com/Kotlin/kotlin-lsp)
     * supports it.
     *
     * @param project the project containing the file
     * @param line the line number
     * @param ch the character position
     * @param fileName the name of the file to be used for completion
     * @return a list of [CompletionItem]s
     */
    context(client: LspClient)
    internal suspend fun getCompletions(
        project: LspProject,
        line: Int,
        ch: Int,
        fileName: String,
    ): List<CompletionItem> {
        val uri = project.getDocumentUri(fileName) ?: return emptyList()
        return client.getCompletionsWithRetry(uri, Position(line, ch))
    }

    private fun createNewProject(project: Project): LspProject = LspProject.fromProject(project)

    context(lspClient: LspClient)
    internal fun closeProject(project: Project) {
        val lspProject = lspProjects[project] ?: return
        lspProject.getDocumentsUris().forEach { uri -> lspClient.closeDocument(uri) }
        lspProject.tearDown()
        lspProjects.remove(project)
    }

    fun closeAllProjects() {
        lspProjects.keys.forEach { with(lspClient) { closeProject(it) } }
        lspProjects.clear()
    }

    companion object {
        val LSP_HOST = System.getenv("LSP_HOST") ?: "127.0.0.1"
        val LSP_PORT = System.getenv("LSP_PORT")?.toInt() ?: 9999
        val LSP_USERS_PROJECTS_ROOT: URI =
            Path.of(System.getenv("LSP_USERS_PROJECTS_ROOT") ?: ("lsp-users-projects-root")).toUri()
    }
}

object StatefulKotlinLspProxy {
    const val PERMITS_PER_CLIENT = 10
    const val DEFAULT_POOL_SIZE = 4
    val ACQUIRE_TIMEOUT = 1.seconds

    private val logger = LoggerFactory.getLogger(KotlinLspProxy::class.java)

    private val clientsProjects = ConcurrentHashMap<String, Project>()
    private val clientsToLspClients = ConcurrentHashMap<String, LspClient>()
    private val lspClientsPermits = ConcurrentHashMap<LspClient, Semaphore>()
    private lateinit var clientPool: List<LspClient>

    suspend fun KotlinLspProxy.initialisePool(
        nClients: Int = DEFAULT_POOL_SIZE,
        permitsPerClient: Int = PERMITS_PER_CLIENT
    ): CompletableDeferred<Unit> {
        if (::clientPool.isInitialized) return CompletableDeferred(Unit)
        return CompletableDeferred<Unit>().also { deferred ->
            clientPool = List(nClients - 1) {
                LspClient.createSingle(LSP_USERS_PROJECTS_ROOT.path, "kotlin-compiler-server-$it")
                    .also { c -> lspClientsPermits[c] = Semaphore(permitsPerClient) }
            } + lspClient
            lspClientsPermits[lspClient] = Semaphore(permitsPerClient)
            logger.info("LSP client pool initialised with $nClients")
            deferred.complete(Unit)
        }
    }

    /**
     * Retrieve completions for a given line and character position in a project file.
     * This modality is used for **stateful** scenarios, where the document will be
     * changed and then completion triggered, while it's being stored in memory
     * for the whole user session's duration.
     *
     * Please note that calling this method assumes that **the client** has **already opened
     * the document**.
     *
     * @param clientId the user identifier (or session identifier)
     * @param newProject the project containing the file
     * @param line the line number
     * @param ch the character position
     * @return a list of [CompletionItem]s
     */
    suspend fun KotlinLspProxy.getCompletionsForClient(clientId: String, newProject: Project, line: Int, ch: Int): List<CompletionItem> {
        val project = clientsProjects[clientId] ?: return emptyList()
        val lspProject = lspProjects[project] ?: return emptyList()
        val newContent = newProject.files.first().text
        val documentToChange = project.files.first().name
        return withLspClient(clientId) {
            changeDocumentContent(lspProject, documentToChange, newContent)
            getCompletions(lspProject, line, ch, documentToChange)
        } ?: emptyList()
    }

    fun KotlinLspProxy.onClientConnected(clientId: String) {
        val project = Project(files = listOf(ProjectFile(name = "$clientId.kt"))).also { clientsProjects[clientId] = it }
        val lspProject = LspProject.fromProject(project).also { lspProjects[project] = it }
        val assignedLspClient = assignClientToLspClient(clientId)
        lspProject.getDocumentsUris().forEach { uri -> assignedLspClient.openDocument(uri, "") }
    }

    fun KotlinLspProxy.onClientDisconnected(clientId: String) {
        clientsProjects[clientId]?.let {
            with(clientsToLspClients[clientId] ?: lspClient) {
                closeProject(it)
            }
            clientsProjects.remove(clientId)
        }
        unassignClientFromLspClient(clientId)
    }

    context(client: LspClient)
    private fun changeDocumentContent(
        lspProject: LspProject,
        documentToChange: String,
        newContent: String,
    ) {
        lspProject.changeDocumentContents(documentToChange, newContent)
        client.changeDocument(lspProject.getDocumentUri(documentToChange)!!, newContent)
    }

    /**
     * Execute a given [body] with a client assigned to the given [clientId].
     * If [initialisePool] has been called, then the request will be executed
     * on a client from the pool, otherwise it will be executed on the [LspClient]
     * instance stored in [proxy].
     */
    context(proxy: KotlinLspProxy)
    private suspend fun <T> withLspClient(clientId: String, body: suspend context(LspClient) () -> T): T? =
    clientsToLspClients[clientId]?.let { client ->
        lspClientsPermits[client]?.withTimeoutPermit {
            body(client)
        }
    } ?: run {
        logger.info("Cannot find client for $clientId, falling back to proxy")
        body(proxy.lspClient)
    }

    private fun assignClientToLspClient(clientId: String): LspClient {
        val clientsCounts = clientsToLspClients.values.groupingBy { it }.eachCount()
        val lspClient = lspClientsPermits.entries
            .minWithOrNull(
                compareBy<Map.Entry<LspClient, Semaphore>> { entry ->
                    clientsCounts[entry.key] ?: 0
                }.thenByDescending { entry ->
                    -entry.value.availablePermits
                }
            )?.key
            ?: clientPool.random()

        clientsToLspClients[clientId] = lspClient
        return lspClient
    }

    private fun unassignClientFromLspClient(clientId: String) {
        clientsToLspClients[clientId]?.let {
            clientsToLspClients.remove(clientId)
        }
    }

    private suspend fun<T> Semaphore.withTimeoutPermit(action: suspend () -> T): T? =
        withTimeoutOrNull(ACQUIRE_TIMEOUT) {
            try {
                acquire()
                action()
            } catch (e: TimeoutCancellationException) {
                logger.info("permit timeout triggered")
                throw e
            } finally {
                release()
            }
        }
}