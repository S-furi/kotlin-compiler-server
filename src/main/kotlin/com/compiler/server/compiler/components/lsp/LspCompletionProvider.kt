package com.compiler.server.compiler.components.lsp

import com.compiler.server.compiler.KotlinFile
import com.compiler.server.compiler.components.ICompletionProvider
import com.compiler.server.model.ProjectType
import org.eclipse.lsp4j.CompletionItem
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.psi.KtFile
import org.springframework.stereotype.Component
import java.net.URI

@Component
abstract class LspCompletionProvider : ICompletionProvider {

    abstract fun getCompletion(
        project: LspProject,
        line: Int,
        ch: Int,
        owner: String? = null
    ): List<CompletionItem>

    override fun complete(
        file: KotlinFile,
        line: Int,
        character: Int,
        projectType: ProjectType,
        coreEnvironment: KotlinCoreEnvironment
    ) {
        prepareContextForUser(file.kotlinFile, projectType)
    }

    private fun prepareContextForUser(
        kotlinFile: KtFile,
        projectType: ProjectType,
    ) {
        LspProject.fromFile(
            fileName = kotlinFile.name,
            fileContents = kotlinFile.text,
            projectType = projectType,
        )
    }



}