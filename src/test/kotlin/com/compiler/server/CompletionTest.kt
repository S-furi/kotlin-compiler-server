package com.compiler.server

import com.compiler.server.base.BaseExecutorTest
import com.compiler.server.lsp.utils.LspIntegrationTestUtils
import com.compiler.server.lsp.utils.RequireLspServerCondition
import com.compiler.server.model.Project
import com.compiler.server.model.ProjectFile
import com.compiler.server.service.lsp.KotlinLspProxy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ExtendWith

class CompletionTest : BaseExecutorTest(), AbstractCompletionTest {
  override fun performCompletion(code: String, line: Int, character: Int, completions: List<String>, isJs: Boolean) {
      complete(code, line, character, completions, isJs)
  }
}

@LspIntegrationTestUtils.RequireLspServer
@ExtendWith(RequireLspServerCondition::class)
class LspCompletionTest : BaseExecutorTest(), AbstractCompletionTest {

    override fun performCompletion(
        code: String,
        line: Int,
        character: Int,
        completions: List<String>,
        isJs: Boolean,
    ) {
        runBlocking {
            val project = Project(files = listOf(ProjectFile(text = code, name = "test.kt")))
            lspProxy.getOneTimeCompletions(
                project = project,
                line = line,
                ch = character,
            )
        }
    }

    companion object {
        private val lspProxy = KotlinLspProxy()

        @BeforeAll
        @JvmStatic
        fun setUpLsp() = runBlocking {
            lspProxy.initializeClient()
        }
    }
}