package com.compiler.server.compiler.components.lsp

import com.compiler.server.compiler.components.lsp.LspCompletionParser.toCompletion
import com.compiler.server.model.Project
import com.compiler.server.service.lsp.KotlinLspProxy
import model.Completion
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class LspCompletionProvider(
    @param:Qualifier("kotlinLspProxy") private val lspProxy: KotlinLspProxy,
) {

    suspend fun complete(project: Project, line: Int, ch: Int): List<Completion> =
        lspProxy.getOneTimeCompletions(project, line, ch).mapNotNull { it.toCompletion() }
}