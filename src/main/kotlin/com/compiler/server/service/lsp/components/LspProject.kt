package com.compiler.server.service.lsp.components

import com.compiler.server.model.Project
import com.compiler.server.model.ProjectType
import com.compiler.server.service.lsp.KotlinLspProxy
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonValue
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@JsonIgnoreProperties(value = ["ownerId"])
class LspProject(
    confType: ProjectType = ProjectType.JAVA,
    files: List<LspDocument> = emptyList(),
    ownerId: String? = null,
) {
    private val projectRoot: Path = baseDir.resolve("$confType-${ownerId ?: UUID.randomUUID().toString()}")
    private val documentsToPaths: MutableMap<String, Path> = mutableMapOf()
    private val documentsVersions = ConcurrentHashMap<String, AtomicInteger>()

    init {
        projectRoot.toFile().mkdirs()
        files.associateTo(documentsToPaths) { file ->
            file.name to projectRoot.resolve(file.name).also {
                it.toFile().writeText(file.text)
            }
        }
    }

    @Synchronized
    fun changeDocumentContents(name: String, newContents: String) {
        documentsToPaths[name]?.toFile()?.writeText(newContents)
        documentsVersions[name]?.incrementAndGet()
    }

    fun getDocumentUri(name: String): String? = documentsToPaths[name]?.toUri()?.toString()

    fun getDocumentsUris(): List<String> = documentsToPaths.keys.mapNotNull { getDocumentUri(it) }

    fun getLatestDocumentVersion(name: String): Int = documentsVersions.compute(name) { _, v ->
        (v ?: AtomicInteger(1)).apply { addAndGet(1) }
    }!!.get()

    fun resetDocumentVersion(name: String) {
        documentsVersions[name]?.set(1)
    }

    fun tearDown() {
        documentsToPaths.values.forEach { it.toFile().delete() }
        projectRoot.toFile().delete()
    }

    companion object {
        private val baseDir = Path.of(KotlinLspProxy.LSP_LOCAL_WORKSPACE_ROOT).toAbsolutePath()

        fun fromProject(project: Project, ownerId: String? = null): LspProject {
            return LspProject(
                confType = ensureSupportedConfType(project.confType),
                files = project.files.map { LspDocument(it.text, it.name) },
                ownerId = ownerId,
            )
        }

        /**
         * If and when kotlin LSP support other project types, this function can be updated.
         */
        private fun ensureSupportedConfType(projectType: ProjectType): ProjectType {
            require(projectType == ProjectType.JAVA) { "Only JVM related projects are supported" }
            return projectType
        }
    }
}

data class LspDocument(
    val text: String = "",
    val name: String = "File.kt",
    val publicId: String? = null,
)

@Suppress("unused")
enum class LspProjectType(@JsonValue val id: String) {
    JAVA("java"),
    // add here support for JS, WASM, ...
}