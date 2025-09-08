package com.compiler.server.compiler.components

import com.compiler.server.compiler.KotlinFile
import com.compiler.server.model.ProjectType
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment

interface ICompletionProvider {
    fun complete(
        file: KotlinFile,
        line: Int,
        character: Int,
        projectType: ProjectType,
        coreEnvironment: KotlinCoreEnvironment
    )

}