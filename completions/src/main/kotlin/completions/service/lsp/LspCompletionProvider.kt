package completions.service.lsp

import completions.lsp.FuzzyCompletionRanking.completionQuery
import completions.lsp.FuzzyCompletionRanking.rankCompletions
import completions.lsp.KotlinLspProxy
import completions.lsp.LspCompletionParser.toCompletion
import completions.lsp.StatefulKotlinLspProxy.getCompletionsForClient
import completions.model.Project
import completions.model.ProjectFile
import model.Completion
import org.eclipse.lsp4j.CompletionItem
import org.springframework.stereotype.Component

@Component
class LspCompletionProvider(
    private val lspProxy: KotlinLspProxy,
) {

    suspend fun complete(
        project: Project,
        line: Int,
        ch: Int,
        applyFuzzyRanking: Boolean = true
    ): List<Completion> =
        lspProxy.getOneTimeCompletions(project, line, ch).transformCompletions(project, applyFuzzyRanking)

    suspend fun complete(
        clientId: String,
        project: Project,
        line: Int,
        ch: Int,
        applyFuzzyRanking: Boolean = true
    ): List<Completion> =
        lspProxy.getCompletionsForClient(clientId, project, line, ch).transformCompletions(project, applyFuzzyRanking)

    private fun List<CompletionItem>.transformCompletions(
        project: Project,
        applyFuzzyRanking: Boolean
    ): List<Completion> =
        if (applyFuzzyRanking) {
            rankedCompletions()
        } else {
            this
        }.mapNotNull { it.toCompletion() }.cleanupImports(project.files.first())

    private fun List<CompletionItem>.rankedCompletions(): List<CompletionItem> =
        firstOrNull()?.completionQuery
            ?.takeIf { !it.isBlank() }
            ?.let { rankCompletions(it) }
            ?: this


    private fun List<Completion>.cleanupImports(file: ProjectFile): List<Completion> {
        val imports = extractImports(file)
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

    private fun extractImports(file: ProjectFile): Set<String> {
        val importsPattern = """^\s*import\s+([\w.*]+)""".toRegex(RegexOption.MULTILINE)
        return importsPattern.findAll(file.text)
            .map { it.groupValues[1].trim() }
            .toSet()
    }

    private fun getTextWhenHasOtherImports(completion: Completion) =
        completion.import?.substringBeforeLast('.') + '.' + completion.text
}