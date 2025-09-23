package com.compiler.server.lsp.utils

import org.jetbrains.kotlin.konan.file.createTempDir
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.ComposeContainer
import java.io.File

internal class KotlinLspComposeExtension: BeforeAllCallback, ExtensionContext.Store.CloseableResource {
    override fun beforeAll(context: ExtensionContext) {
        if (!started) {
            container = KotlinLspComposeExtension::class.java.getResource("/lsp/compose.yaml")?.file?.let {
                ComposeContainer(File(it)).withExposedService("kotlin-lsp", 9999).withLocalCompose(true)
            } ?: error("Could not find docker compose file")

            container.start()
            started = true
            val mappedPort = container.getServicePort("kotlin-lsp", 9999)

            System.setProperty("LSP_HOST", "localhost")
            System.setProperty("LSP_PORT", mappedPort.toString())
            System.setProperty("LSP_REMOTE_WORKSPACE_ROOT", "/lsp-users-projects-root")

            val localWorkspaceRoot = createTempDir("lsp-users-projects-root").also { it.deleteOnExitRecursively() }
            System.setProperty("LSP_LOCAL_WORKSPACE_ROOT", localWorkspaceRoot.absolutePath)

            LspIntegrationTestUtils.waitForLspReady(
                host = System.getProperty("LSP_HOST"),
                port = System.getProperty("LSP_PORT")!!.toInt(),
                workspace = System.getProperty("LSP_REMOTE_WORKSPACE_ROOT")
            )
        }
    }

    override fun close() {
        container.stop()
    }

    companion object {
        private var started = false
        lateinit var container: ComposeContainer
    }
}
