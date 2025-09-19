package com.compiler.server.compiler.components.lsp

import com.compiler.server.model.Project
import com.compiler.server.service.lsp.FuzzyCompletionRanking.completionQuery
import com.compiler.server.service.lsp.FuzzyCompletionRanking.rankCompletions
import com.compiler.server.service.lsp.KotlinLspProxy
import com.compiler.server.service.lsp.LspCompletionParser.toCompletion
import com.compiler.server.service.lsp.StatefulKotlinLspProxy.getCompletionsForClient
import model.Completion
import org.eclipse.lsp4j.CompletionItem
import org.jetbrains.kotlin.psi.KtFile
import org.springframework.stereotype.Component

@Component
class LspCompletionProvider(
    private val lspProxy: KotlinLspProxy,
) {

    context(_: KtFile)
    suspend fun complete(project: Project, line: Int, ch: Int, applyFuzzyRanking: Boolean = true): List<Completion> =
        lspProxy.apply { requireAvailable() }.getOneTimeCompletions(project, line, ch).transformCompletions(applyFuzzyRanking)

    context(_: KtFile)
    suspend fun complete(clientId: String, project: Project, line: Int, ch: Int, applyFuzzyRanking: Boolean = true): List<Completion> =
        lspProxy.apply { requireAvailable() }.getCompletionsForClient(clientId, project, line, ch).transformCompletions(applyFuzzyRanking)

    context(_: KtFile)
    private fun List<CompletionItem>.transformCompletions(applyFuzzyRanking: Boolean): List<Completion> =
            if (applyFuzzyRanking) {
                rankedCompletions()
            } else {
                this
            }.mapNotNull { it.toCompletion() }.cleanupImports()

    private fun List<CompletionItem>.rankedCompletions(): List<CompletionItem> =
        firstOrNull()?.completionQuery
            ?.takeIf { !it.isBlank() }
            ?.let { rankCompletions(it) }
            ?: this


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