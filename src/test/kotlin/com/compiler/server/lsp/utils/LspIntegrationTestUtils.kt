package com.compiler.server.lsp.utils

import com.compiler.server.service.lsp.client.KotlinLspClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext
import java.net.Socket
import java.util.Optional
import kotlin.time.Duration.Companion.seconds

object LspIntegrationTestUtils {
    const val DEFAULT_LSP_HOST = "localhost"
    const val DEFAULT_LSP_PORT = 9999

    fun isServerReachable(
        host: String = DEFAULT_LSP_HOST,
        port: Int = DEFAULT_LSP_PORT,
    ): Boolean = runCatching {
        Socket(host, port).use { true }
    }.getOrElse { false }

    fun waitForLspReady(host: String, port: Int, workspace: String) = runBlocking {
        var lastError: Throwable? = null
        withTimeoutOrNull(90.seconds) {
            try {
                KotlinLspClient(host, port).use { client ->
                    client.initRequest(workspace, projectName = "probe").join()
                }
                return@withTimeoutOrNull
            } catch (t: Throwable) {
                lastError = t
                delay(1000)
            }
        } ?: error("LSP server did not become ready in time: ${lastError?.message}")
    }

    @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
    @Retention(AnnotationRetention.RUNTIME)
    @MustBeDocumented
    annotation class RequireLspServer(
        val host: String = DEFAULT_LSP_HOST,
        val port: Int = DEFAULT_LSP_PORT,
    )
}

class RequireLspServerCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        val methodAnnotation = context.testMethod
            .flatMap {
                it.getAnnotation(LspIntegrationTestUtils.RequireLspServer::class.java)?.let { ann -> Optional.of(ann) }
                    ?: Optional.empty()
            }
            .orElse(null)

        val classAnnotation = context.testClass
            .flatMap {
                it.getAnnotation(LspIntegrationTestUtils.RequireLspServer::class.java)?.let { ann -> Optional.of(ann) }
                    ?: Optional.empty()
            }.orElse(null)

        val annotation = methodAnnotation
            ?: classAnnotation
            ?: return ConditionEvaluationResult.enabled("No @RequireLspAnnotation found")


        return if (LspIntegrationTestUtils.isServerReachable(annotation.host, annotation.port)) {
            ConditionEvaluationResult.enabled("Lsp server is reachable at ${annotation.host}:${annotation.port}")
        } else {
            ConditionEvaluationResult.disabled("Lsp server is not reachable at ${annotation.host}:${annotation.port}")
        }
    }
}