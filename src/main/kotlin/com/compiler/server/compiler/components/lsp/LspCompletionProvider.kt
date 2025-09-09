package com.compiler.server.compiler.components.lsp

import com.compiler.server.model.Project
import com.compiler.server.service.lsp.FuzzyCompletionRanking.completionQuery
import com.compiler.server.service.lsp.FuzzyCompletionRanking.rankCompletions
import com.compiler.server.service.lsp.KotlinLspProxy
import com.compiler.server.service.lsp.LspCompletionParser.toCompletion
import model.Completion
import org.jetbrains.kotlin.psi.KtFile
import org.springframework.stereotype.Component

@Component
class LspCompletionProvider(
    private val lspProxy: KotlinLspProxy,
) {

    context(_: KtFile)
    suspend fun complete(project: Project, line: Int, ch: Int): List<Completion> =
        lspProxy.getOneTimeCompletions(project, line, ch).let { completionItems ->
            completionItems.firstOrNull()?.completionQuery
                ?.takeIf { !it.isBlank() }
                ?.let { completionItems.rankCompletions(it) }
                ?: completionItems
        }.mapNotNull { it.toCompletion() }.cleanupImports()

    context(file: KtFile)
    private fun List<Completion>.cleanupImports(): List<Completion> {
        val imports = file.importDirectives.mapNotNull { it.importedFqName?.asString() }.toSet()
        return map { completion ->
            if (completion.import != null && completion.import in imports) {
                completion.copy(import = null)
            } else if (imports.any { it.endsWith(completion.text) }) {
                completion.copy(text = getTextWhenHasOtherImports(completion), import = null, hasOtherImports = true)
            } else {
                completion
            }
        }
    }

    private fun getTextWhenHasOtherImports(completion: Completion) =
        completion.import?.substringBeforeLast('.') + '.' + completion.text
}