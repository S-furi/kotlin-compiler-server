package com.compiler.server.compiler.components.lsp

import com.compiler.server.compiler.components.lsp.LspCompletionParser.toCompletion
import com.compiler.server.model.Project
import com.compiler.server.service.lsp.StatefulKotlinLspProxy
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import model.Completion
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Component
class LspCompletionWebSocketHandler(
    private val lspProxy: StatefulKotlinLspProxy,
): TextWebSocketHandler() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(
        Dispatchers.IO + job + CoroutineName("LspCompletionWebSocketHandler")
    )

    private val activeSession = ConcurrentHashMap<String, WebSocketSession>()
    private val logger = LoggerFactory.getLogger(LspCompletionWebSocketHandler::class.java)

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        scope.launch {
            val request = session.decodeCompletionRequestFromTextMessage(message) ?: return@launch
            lspProxy.getCompletionsForClient(session.id, request.project, request.line, request.ch)
                .mapNotNull { it.toCompletion() }
                .let { session.sendResponse(Response.Completions(it)) }
        }
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        activeSession[session.id] = session
        scope.launch {
            with(session) {
                logger.info("Lsp client connected: $id")
                withTimeoutOrNull(10.seconds) {
                    while (!lspProxy.isLspClientConnected()) {
                        delay(500.milliseconds)
                    }
                } ?: sendResponse(Response.Error("Proxy is still not connected to Language server"))
                lspProxy.onClientConnected(id)
                sendResponse(Response.Init(id))
            }
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        handleClientDisconnected(session.id)
        logger.info("Lsp client disconnected: ${session.id} ($status)")
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        handleClientDisconnected(session.id)
        logger.error("Lsp client transport error: ${session.id}", exception)
    }

    private fun handleClientDisconnected(clientId: String) {
        activeSession.remove(clientId)
        scope.launch { lspProxy.onClientDisconnected(clientId) }
    }

    private suspend fun WebSocketSession.sendResponse(response: Response) {
        try {
            if (isOpen) {
                withContext(scope.coroutineContext) {
                    sendMessage(TextMessage(response.toJson()))
                }
            }
        } catch (e: Exception) {
            logger.error("Error sending message to client $id:", e)
        }
    }

    private suspend fun WebSocketSession.decodeCompletionRequestFromTextMessage(message: TextMessage): CompletionRequest? =
        try {
            objectMapper.readValue(message.payload, CompletionRequest::class.java)
        } catch (e: JsonProcessingException) {
            logger.warn("Invalid JSON from client: ${message.payload}")
            sendResponse(Response.Error("Invalid JSON format for ${message.payload}: ${e.message}"))
            null
        }

    @PreDestroy
    fun cleanup() {
        lspProxy.closeAllProjects()
        this.job.cancel()
    }

    companion object {
        internal val objectMapper = ObjectMapper().apply { registerKotlinModule() }
    }
}

internal sealed interface Response {

    data class Error(val message: String): Response
    data class Init(val sessionId: String): Response
    data class Completions(val completions: List<Completion>): Response

    fun toJson(): String = LspCompletionWebSocketHandler.objectMapper.writeValueAsString(this)

}

private data class CompletionRequest(
    val project: Project,
    val line: Int,
    val ch: Int,
)
