package com.compiler.server.lsp.ws

import com.compiler.server.lsp.utils.LspIntegrationTestUtils
import com.compiler.server.lsp.utils.RequireLspServerCondition
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.websocket.ContainerProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.WebSocketClient
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import kotlin.test.assertContains
import kotlin.time.Duration.Companion.seconds

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@LspIntegrationTestUtils.RequireLspServer
@ExtendWith(RequireLspServerCondition::class)
class KotlinLspProxyWSTest {

    @LocalServerPort
    private var port: Int = 0
    private var url = "ws://localhost:$port/lsp/complete"

    private lateinit var client: WebSocketClient
    private lateinit var handler: TestClientHandler

    private val objectMapper = ObjectMapper().apply { registerKotlinModule() }

    @Test
    fun `methods completions should be retrieved`() {
        val content =
            """
            fun main() {
                3.0.toIn
            }
            fun Double.toInterval(): IntRange = IntRange(0, toInt())
        """.trimIndent()
        checkCompletionsWithWebSocketSession(content, 1, 11, listOf("toInterval(", "toInt(", "toUInt("))
    }

    @Test
    fun `project dependency lib (kotlinx-coroutines) should be retrieved`() {
        val content =
            """
            fun main() {
                GlobalSc
            }
            """.trimIndent()
        checkCompletionsWithWebSocketSession(content, 1, 11, listOf("GlobalScope"))
    }

    @BeforeEach
    fun setup() {
        client = StandardWebSocketClient(
            ContainerProvider.getWebSocketContainer().apply {
                defaultMaxSessionIdleTimeout = 0L
            }
        )
        handler = TestClientHandler()
    }

    fun checkCompletionsWithWebSocketSession(
        content: String,
        line: Int,
        ch: Int,
        expectedCompletions: List<String>
    ) = runBlocking {
        val session = connect()
        val msg = buildCompletionRequest(session.id, content, line, ch)
        session.sendMessage(TextMessage(objectMapper.writeValueAsString(msg)))
        val completions = handler.receiveCompletions()
        expectedCompletions.forEach { assertContains(completions, it) }
    }

    private suspend fun connect(): WebSocketSession {
        val session = withTimeoutOrNull(defaultTimeout) {
            client.execute(handler, url).await()
        } ?: error("Failed to connect to LSP server at $url within $defaultTimeout")
        val initMessage = handler.receiveMessage()

        assertNotNull(objectMapper.readTree(initMessage))
        return session
    }

    private fun buildCompletionRequest(sessionId: String, content: String, line: Int, ch: Int): Map<String, Any> {
        val project = mapOf(
            "files" to listOf(mapOf("name" to "$sessionId.kt", "text" to content)),
            "confType" to "java",
        )
        return mapOf(
            "project" to project,
            "line" to line,
            "ch" to ch,
        )
    }

    private inner class TestClientHandler() : TextWebSocketHandler() {
        private val messages: Channel<String> = Channel(Channel.UNLIMITED)

        override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
            messages.trySend(message.payload)
        }

        suspend fun receiveMessage(): String =
            withTimeout(defaultTimeout) {
                messages.receive()
            }

        suspend fun receiveCompletions(): List<String> {
            val msg = receiveMessage()
            val json = objectMapper.readTree(msg)
            return extractCompletionTexts(json)
        }

        private fun extractCompletionTexts(msg: JsonNode): List<String> {
            val completions = msg["additionalData"]?.get("completions") ?: return emptyList()
            return completions.mapNotNull { it["text"]?.asText() }
        }
    }

    companion object {
        private val defaultTimeout = 10.seconds
    }
}