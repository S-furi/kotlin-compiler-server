package com.compiler.server.lsp

import com.compiler.server.lsp.utils.LspIntegrationTestUtils.RequireLspServer
import com.compiler.server.lsp.utils.RequireLspServerCondition
import com.compiler.server.service.lsp.client.KotlinLspClient
import com.compiler.server.service.lsp.client.LspClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

//@ExtendWith(KotlinLspComposeExtension::class)
@RequireLspServer(host = "localhost", port = 9999)
@ExtendWith(RequireLspServerCondition::class)
class LspClientTest {

    private val workspacePath = this::class.java.getResource("/lsp/lsp-users-projects-root")?.toURI()?.path
        ?: error("Could not find test LSP workspace directory")

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
        client = LspClient.createSingle(workspacePath, workspaceName)
    }
}
