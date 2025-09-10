package com.compiler.server.lsp

import com.compiler.server.service.lsp.client.DocumentSync.openDocument
import com.compiler.server.service.lsp.client.KotlinLspClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.platform.commons.logging.LoggerFactory
import org.testcontainers.containers.ComposeContainer
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@ExtendWith(KotlinLspComposeExtension::class)
class LspClientTest {

    private val workspacePath = "/lsp-users-test-projects-root"

    private val workspaceName = "lsp-users-test-project"

    private lateinit var client: KotlinLspClient

    private val fakeResourceUri = "file:///foo/bar/File.kt"


    @Test
    fun `LSP client should initialize correctly`() {
        assertTrue { (::client.isInitialized) }
    }

    @Test
    fun `LSP client should provide completions as CompletionItems`() = runBlocking {
        val code = "fun main() {\n    val alex = 1\n    val alex1 = 1 + a\n}"
        val position = Position(2, 21)

        client.openDocument(fakeResourceUri, code)
        delay(1.seconds)
        val completions = client.getCompletion(fakeResourceUri, position).await()
        assertAll(
            { assertTrue { completions.isNotEmpty() } },
            { assertContains(completions.map { it.label }, "alex") },
            { assertEquals("kotlin.Int", completions.first { it.label == "alex" }.labelDetails?.description) }
        )
    }

    @BeforeEach
    fun setup() = runBlocking {
        if (::client.isInitialized) {
            client.shutdown().await()
            client.exit()
        }
        client = KotlinLspClient.create(workspacePath, workspaceName)
    }
}

internal class KotlinLspComposeExtension: BeforeAllCallback, ExtensionContext.Store.CloseableResource {
    override fun beforeAll(context: ExtensionContext?) {
        if (!started) {
            container = KotlinLspComposeExtension::class.java.getResource("/lsp/compose.yaml")?.let {
                ComposeContainer(File(it.file))
                    .withExposedService("kotlin-lsp", 9999)
            } ?: error("Could not find docker compose file")

            container.start()
            context?.root?.getStore(ExtensionContext.Namespace.GLOBAL)?.put("COMPOSE_CONTAINER", this)
        }
    }

    override fun close() {
        container.stop()
    }

    companion object {
        private var started = false
        private val logger = LoggerFactory.getLogger(KotlinLspComposeExtension::class.java)
        lateinit var container: ComposeContainer
    }
}