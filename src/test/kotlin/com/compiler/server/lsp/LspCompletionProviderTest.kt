package com.compiler.server.lsp

import com.compiler.server.lsp.utils.LspIntegrationTestUtils.RequireLspServer
import com.compiler.server.lsp.utils.RequireLspServerCondition
import com.compiler.server.model.Project
import com.compiler.server.model.ProjectFile
import model.Completion
import model.Icon
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.platform.commons.logging.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import kotlin.test.assertEquals

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RequireLspServer
@ExtendWith(RequireLspServerCondition::class)
class LspCompletionProviderTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var webTestClient: WebTestClient

    val testCode = """
        fun main() {
            3.0.toIn
        }
        fun Double.toIntervalZeroBased(): IntRange = IntRange(0, this.toInt())
    """.trimIndent()

    val testProject = Project(files = listOf(ProjectFile(text = testCode, name = "file.kt")))

    @Test
    fun `rest endpoint should return simple completions`() {
        val completions = getCompletions(testProject, 1, 11)
        val toUint = completions.find { it.text == "toUInt()" }
            ?: error("Expected to find \"toUInt()\" completion, but got $completions\"")

        val expected = Completion(
            text = "toUInt()",
            displayText = "toUInt() for Double in kotlin",
            tail = "UInt",
            import = "kotlin.toUInt",
            icon = Icon.METHOD,
            hasOtherImports = null
       )
        assertEquals(expected, toUint)
    }

    @Test
    fun `rest endpoint should return completions in the expected order`() {
        val completions = getCompletions(testProject, 1, 11).map { it.text }
        val expectedTexts = listOf("toInt()", "toIntervalZeroBased()", "toUInt()", "roundToInt()")
        assertEquals(expectedTexts, completions)
    }

    @Test
    fun `rest endpoint should provide fully qualified name if same name is imported`() {
        val code = """
            import java.util.Random
            fun main() {
                val rnd = Random
            }
        """.trimIndent()

        val line = 2
        val ch = 20

        val project = Project(files = listOf(ProjectFile(text = code, name = "random.kt")))

        val completions = getCompletions(project, line, ch)
        val ktRandom = completions.find { it.displayText == "Random (kotlin.random)" && it.hasOtherImports == true }
            ?: error("Expected to find \"kotlin.random.Random\" completion, but got $completions\"")

        assertAll(
            { assertEquals("kotlin.random.Random", ktRandom.text) } ,
            { assertNull(ktRandom.import) },
        )
    }

    private fun getCompletions(project: Project, line: Int, ch: Int): List<Completion> {
        val url = "http://localhost:$port/api/compiler/lsp/complete?line=$line&ch=$ch"
        return withTimeout {
            post()
                .uri(url)
                .bodyValue(project)
                .exchange()
                .expectStatus().isOk
                .expectBodyList(Completion::class.java)
                .returnResult()
                .responseBody?.also { println(it) }
        } ?: emptyList()
    }

    private fun <T> withTimeout(
        duration: Duration = Duration.ofSeconds(20),
        body: WebTestClient.() -> T?
    ): T? = with(webTestClient.mutate().responseTimeout(duration).build()) { body() }

    companion object {
        private val logger = LoggerFactory.getLogger(LspCompletionProviderTest::class.java)
    }
}