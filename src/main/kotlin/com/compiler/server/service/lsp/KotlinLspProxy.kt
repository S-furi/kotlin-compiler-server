package com.compiler.server.service.lsp

import com.compiler.server.compiler.components.lsp.LspProject
import com.compiler.server.model.Project
import com.compiler.server.model.ProjectFile
import com.compiler.server.service.lsp.client.LspClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.Position
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Component
class KotlinLspProxy {

    internal lateinit var client: LspClient
    internal val lspProjects = ConcurrentHashMap<Project, LspProject>()

    @EventListener(ApplicationReadyEvent::class)
    fun initClientOnReady() {
        CoroutineScope(Dispatchers.IO).launch {
            client = LspClient.createSingle(
                LSP_USERS_PROJECTS_ROOT.path,
                "kotlin-compiler-server",
            )
        }
    }

    fun isLspClientConnected(): Boolean = ::client.isInitialized

    /**
     * Initialize the LSP client. This method must be called before any other method in this
     * class. It is recommended to call this method when the **spring** application context is initialized.
     *
     * [workspacePath] is the path ([[java.net.URI.path]]) to the root project directory,
     * where the project must be a project supported by [Kotlin-LSP](https://github.com/Kotlin/kotlin-lsp).
     * The workspace will not contain users' files, but it can be used to store common files,
     * to specify kotlin/java versions, project-wide imported libraries and so on.
     *
     * @param workspacePath the path to the workspace directory, namely the root of the common project
     * @param clientName the name of the client, defaults to "lsp-proxy"
     */
    suspend fun initializeClient(
        workspacePath: String = LSP_USERS_PROJECTS_ROOT.path,
        clientName: String = "kotlin-compiler-server"
    ) {
        if (!::client.isInitialized) client = LspClient.createSingle(workspacePath, clientName)
    }

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
        client.openDocument(uri, projectFile.text)
        return getCompletions(lspProject, line, ch, projectFile.name)
            .also { closeProject(project) }
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

    internal fun closeProject(project: Project) {
        val lspProject = lspProjects[project] ?: return
        lspProject.getDocumentsUris().forEach { uri -> client.closeDocument(uri) }
        lspProject.tearDown()
        lspProjects.remove(project)
    }

    fun closeAllProjects() {
        lspProjects.keys.forEach { closeProject(it) }
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
    private val clientsProjects = ConcurrentHashMap<String, Project>()

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
        changeDocumentContent(lspProject, documentToChange, newContent)
        return getCompletions(lspProject, line, ch, documentToChange)
    }

    fun KotlinLspProxy.onClientConnected(clientId: String) {
        val project = Project(files = listOf(ProjectFile(name = "$client.kt"))).also { clientsProjects[clientId] = it }
        val lspProject = LspProject.fromProject(project).also { lspProjects[project] = it }
        lspProject.getDocumentsUris().forEach { uri -> client.openDocument(uri, "") }
    }

    fun KotlinLspProxy.onClientDisconnected(clientId: String) {
        clientsProjects[clientId]?.let {
            closeProject(it)
            clientsProjects.remove(clientId)
        }
    }

    private fun KotlinLspProxy.changeDocumentContent(lspProject: LspProject, documentToChange: String, newContent: String) {
        lspProject.changeDocumentContents(documentToChange, newContent)
        client.changeDocument(lspProject.getDocumentUri(documentToChange)!!, newContent)
    }
}