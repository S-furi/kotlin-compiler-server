package com.compiler.server.compiler.components.lsp

import com.compiler.server.service.lsp.LspCompletionParser.toCompletion
import com.compiler.server.model.Project
import com.compiler.server.service.lsp.FuzzyMatcher.completionQuery
import com.compiler.server.service.lsp.FuzzyMatcher.rankCompletions
import com.compiler.server.service.lsp.KotlinLspProxy
import com.compiler.server.service.lsp.LspCompletionParser.toSimpleParsedCompletion
import model.Completion
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class LspCompletionProvider(
    @param:Qualifier("kotlinLspProxy") private val lspProxy: KotlinLspProxy,
) {

    suspend fun complete(project: Project, line: Int, ch: Int): List<Completion> {
        val completionItems = lspProxy.getOneTimeCompletions(project, line, ch)

        val completionQuery = completionItems.first().completionQuery
        val sorted = if (completionQuery != null && !completionQuery.isBlank()) {
            completionItems.rankCompletions(completionQuery)
        } else {
            completionItems
        }
        return sorted.mapNotNull { it.toSimpleParsedCompletion() }
    }
}