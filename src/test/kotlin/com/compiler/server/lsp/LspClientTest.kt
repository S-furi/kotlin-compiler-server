package com.compiler.server.lsp

import com.compiler.server.AbstractCompletionTest
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
import org.testcontainers.containers.ComposeContainer
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@ExtendWith(KotlinLspComposeExtension::class)
class LspClientTest: AbstractCompletionTest {

    private val workspacePath = "/lsp-users-projects-root"
    private val workspaceName = "test"
    private val fakeResourceUri = "file:///foo/bar/File.kt"

    private lateinit var client: KotlinLspClient

    @Test
    fun `LSP client should initialize correctly`() {
        assertTrue { (::client.isInitialized) }
    }

    @Test
    fun `LSP client should provide completions for local variables`() = runBlocking {
        val code = "fun main() {\n    val alex = 1\n    val alex1 = 1 + a\n}"
        val position = Position(2, 21)

        client.openDocument(fakeResourceUri, code)
        delay(1.seconds)
        val completions = client.getCompletion(fakeResourceUri, position).await()
        assertAll(
            { assertTrue { completions.isNotEmpty() } },
            { assertContains(completions.map { it.label }, "alex") },
            { assertEquals("Int", completions.first { it.label == "alex" }.labelDetails?.description) }
        )
    }

    @Test
    fun `LSP client should provide completions for stdlib elements`() = runBlocking {
        val code = "fun main() {\n    3.0.toIn\n}"
        val position = Position(1, 12)

        client.openDocument(fakeResourceUri, code)
        delay(1.seconds)
        val completions = client.getCompletion(fakeResourceUri, position).await()
        assertAll(
            { assertTrue { completions.isNotEmpty() } },
            { assertContains(completions.map { it.label }, "toInt") },
        )
    }

    @Test
    fun `LSP client should provide completions for libs declared in build file`() = runBlocking {
        val code = "fun main() {\n    runBlock\n}"
        val position = Position(1, 12)

        client.openDocument(fakeResourceUri, code)
        delay(1.seconds)
        val completions = client.getCompletion(fakeResourceUri, position).await()
        assertAll(
            { assertTrue { completions.isNotEmpty() } },
            { assertContains(completions.map { it.label }, "runBlocking") },
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

    override fun performCompletion(
        code: String,
        line: Int,
        character: Int,
        completions: List<String>,
        isJs: Boolean
    ) = runBlocking {
        if (isJs) return@runBlocking
        client.openDocument(fakeResourceUri, code)
        val lspCompletions = client.getCompletion(fakeResourceUri, Position(line, character)).await().map { it.label }
        completions.map { it.replace("""\([\w\s:]*\)""".toRegex(), "") }.forEach { completion ->
            assertContains(lspCompletions, completion)
        }
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
        lateinit var container: ComposeContainer
    }
}