package lsp

import lsp.utils.CARET_MARKER
import lsp.utils.KotlinLspComposeExtension
import lsp.utils.extractCaret
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import completions.lsp.client.KotlinLspClient
import completions.lsp.client.LspClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@ExtendWith(KotlinLspComposeExtension::class)
class LspClientTest {

    @Test
    fun `LSP client should initialize correctly`() {
        assertTrue { isClientInitialized() }
    }

    @Test
    fun `LSP client should provide completions for local variables`() = runBlocking {
        val (code, position) = extractCaret {
            """
                fun main() {
                    val alex = 1
                    val alex1 = 1 + a$CARET_MARKER
                }
            """.trimIndent()
        }

        val uri = randomResourceUri
        client.openDocument(uri, code)
        delay(1.seconds)
        val completions = client.getCompletion(uri, position).await()
        assertAll(
            { assertTrue { completions.isNotEmpty() } },
            { assertContains(completions.map { it.label }, "alex") },
            { assertEquals("Int", completions.first { it.label == "alex" }.labelDetails?.description) }
        )
    }

    @Test
    fun `LSP client should provide completions for stdlib elements`() = runBlocking {
        val (code, position) = extractCaret {
            """
                fun main() {
                    3.0.toIn$CARET_MARKER
                }
            """.trimIndent()
        }
        val uri = randomResourceUri
        client.openDocument(uri, code)
        delay(1.seconds)
        val completions = client.getCompletion(uri, position).await()
        assertAll(
            { assertTrue { completions.isNotEmpty() } },
            { assertContains(completions.map { it.label }, "toInt") },
        )
    }

    @Test
    fun `LSP client should provide completions for libs declared in build file (kotlinx-coroutines)`() = runBlocking {
        val (code, position) = extractCaret {
            """
                fun main() {
                    runBlock$CARET_MARKER
                }
            """.trimIndent()
        }
        val uri = randomResourceUri
        client.openDocument(uri, code)
        delay(1.seconds)
        val completions = client.getCompletion(uri, position).await()
        assertAll(
            { assertTrue { completions.isNotEmpty() } },
            { assertContains(completions.map { it.label }, "runBlocking") },
        )
    }

    @AfterEach
    fun cleanup() = runBlocking {
        openedDocuments.forEach { client.closeDocument(it) }
    }

    companion object {
        private val WORKSPACE_PATH = System.getProperty("LSP_REMOTE_WORKSPACE_ROOT") ?: "/lsp-users-projects-root-test"
        private val LSP_HOST = System.getProperty("LSP_HOST") ?: "localhost"
        private val LSP_PORT = System.getProperty("LSP_PORT")?.toInt() ?: 9999

        private const val WORKSPACE_NAME = "test"

        private val openedDocuments = mutableListOf<String>()
        private val randomResourceUri
            get() = "file:///lspClientTest/${UUID.randomUUID()}.kt".also { openedDocuments.add(it) }

        private lateinit var client: KotlinLspClient

        fun isClientInitialized() = ::client.isInitialized

        @BeforeAll
        @JvmStatic
        fun setup() = runBlocking {
            if (isClientInitialized()) {
                client.close()
            }
            client = LspClient.createSingle(WORKSPACE_PATH, WORKSPACE_NAME, host = LSP_HOST, port = LSP_PORT)
        }

        @AfterAll
        @JvmStatic
        fun teardown() = runBlocking {
            if (::client.isInitialized) {
                client.shutdown().await()
                client.exit()
            }
        }
    }
}
