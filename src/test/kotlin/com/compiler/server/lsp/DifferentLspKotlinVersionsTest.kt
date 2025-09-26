package com.compiler.server.lsp

import com.compiler.server.lsp.utils.CARET_MARKER
import com.compiler.server.lsp.utils.GradleProjectTemplate
import com.compiler.server.lsp.utils.KotlinVersionRetriever
import com.compiler.server.lsp.utils.LspIntegrationTestUtils
import com.compiler.server.lsp.utils.RequireLspServerCondition
import com.compiler.server.lsp.utils.extractCaret
import com.compiler.server.service.lsp.client.LspClient
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionItem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.io.path.absolutePathString
import kotlin.test.assertTrue

@LspIntegrationTestUtils.RequireLspServer
@ExtendWith(RequireLspServerCondition::class)
class DifferentLspKotlinVersionsTest {
    private val deleteRecursivelyCode =
        """
            import java.nio.file.Path
            import kotlin.io.path.ExperimentalPathApi
            import kotlin.io.path.deleteRecursively

            @OptIn(ExperimentalPathApi::class)
            fun main() {
                val path = Path.of("hello-world")
                path.deleteRecur$CARET_MARKER
            }                
        """.trimIndent()

    @Test
    fun `completions with kotlin version 1-7-21 should be empty for 'deleteRecursively'`() = runBlocking {
        checkCompletionsWithKtProjectVersion(ktVersion = "1.7.21") {
            assertTrue { it.isEmpty() }
        }
    }

    @Test
    fun `completions with latest kotlin version should be non-empty for 'deleteRecursively'`() = runBlocking {
        checkCompletionsWithKtProjectVersion(ktVersion = KotlinVersionRetriever.latest) {
            assertTrue { it.isNotEmpty() }
        }
    }

    private suspend fun checkCompletionsWithKtProjectVersion(ktVersion: String, code: String = deleteRecursivelyCode, checks: (List<CompletionItem>) -> Unit) {
        GradleProjectTemplate.withTemporaryProjectTemplate(ktVersion) { workspaceRoot ->
            LspClient.createSingle(workspaceRoot.absolutePathString()).use { client ->
                val (code, caret) = extractCaret { code }
                val uri = "file:///foo/bar/test.kt"
                client.openDocument(uri, code)
                val completions = client.getCompletion(uri, caret).await()
                checks(completions)
            }
        }
    }
}