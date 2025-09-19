package com.compiler.server.lsp.utils

import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.ComposeContainer
import java.io.File

internal class KotlinLspComposeExtension: BeforeAllCallback, ExtensionContext.Store.CloseableResource {
    override fun beforeAll(context: ExtensionContext) {
        if (!started) {
            container = KotlinLspComposeExtension::class.java.getResource("/lsp/compose.yaml")?.file?.let {
                ComposeContainer(File(it)).withExposedService("kotlin-lsp", 9999)
            } ?: error("Could not find docker compose file")

            container.start()
            context.root.getStore(ExtensionContext.Namespace.GLOBAL)?.put("COMPOSE_CONTAINER", this)
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
