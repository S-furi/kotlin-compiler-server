@file:Suppress("ReactiveStreamsUnusedPublisher")

package completions.controllers.ws

import completions.model.Project
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import completions.lsp.KotlinLspProxy
import completions.lsp.StatefulKotlinLspProxy.onClientConnected
import completions.lsp.StatefulKotlinLspProxy.onClientDisconnected
import completions.service.lsp.LspCompletionProvider
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import model.Completion
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import kotlin.coroutines.cancellation.CancellationException

@Component
class LspCompletionWebSocketHandler(
    private val lspProxy: KotlinLspProxy,
    private val lspCompletionProvider: LspCompletionProvider,
) : WebSocketHandler {
    private val job = SupervisorJob()

    private val logger = LoggerFactory.getLogger(LspCompletionWebSocketHandler::class.java)

    override fun handle(session: WebSocketSession): Mono<Void?> {
        val sessionId = session.id

        val sink = Sinks.many().unicast().onBackpressureBuffer<WebSocketMessage>()
        val outbound: Flux<WebSocketMessage> = sink.asFlux()

        val sessionJob = SupervisorJob(job)
        val sessionScope = CoroutineScope(Dispatchers.IO + sessionJob + CoroutineName("LspWS-$sessionId"))

        sessionScope.launch {
            runCatching { lspProxy.requireAvailable() }
                .onSuccess {
                    lspProxy.onClientConnected(sessionId)
                    sink.emit(session, WSResponse.Init(sessionId))
                }
        }

        val requestChannel = Channel<WsCompletionRequest>(Channel.UNLIMITED)

        val inbound = session.receive()
            .map { it.payloadAsText }
            .doOnNext { payload ->
                runCatching { objectMapper.readValue<WsCompletionRequest>(payload) }
                    .onFailure { sink.emit(session, WSResponse.Error("Failed to parse request: $payload")) }
                    .onSuccess { requestChannel.trySend(it) }
            }.then()

        setupCompletionWorker(session, sessionScope, requestChannel, sink)

        val sendMono = session.send(outbound).doFinally { cleanup(session, sessionJob, sink) }
        return Mono.`when`(inbound, sendMono)
            .doOnError { logger.warn("WS session error for client $sessionId: ${it.message}") }
            .doFinally { cleanup(session, sessionJob, sink) }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun setupCompletionWorker(
        session: WebSocketSession,
        sessionScope: CoroutineScope,
        requestChannel: Channel<WsCompletionRequest>,
        sink: Sinks.Many<WebSocketMessage>,
    ) = sessionScope.launch {
        try {
            while (!requestChannel.isClosedForReceive) {
                val first = requestChannel.receiveCatching().getOrNull() ?: break
                var req = first

                while (true) {
                    val next = requestChannel.tryReceive().getOrNull() ?: break
                    sink.emit(session, WSResponse.Discarded(req.requestId))
                    req = next
                }

                val available = runCatching { lspProxy.requireAvailable() }.isSuccess
                if (!available) {
                    sink.emit(session, WSResponse.Error("Lsp client is not available"))
                    continue
                }

                try {
                    val completions = lspCompletionProvider.complete(
                        clientId = session.id,
                        project = req.project,
                        line = req.line,
                        ch = req.ch,
                        applyFuzzyRanking = true,
                    )
                    sink.emit(session, WSResponse.Completions(completions, req.requestId))
                } catch (e: Exception) {
                    logger.warn("Completion processing failed for client ${session.id}:", e)
                    sink.emit(session, WSResponse.Error(e.message ?: "Unknown error", req.requestId))
                }
            }
        } catch (t: Throwable) {
            if (t !is CancellationException) {
                logger.warn("Error collecting responses for client ${session.id}: ${t.message}")
                sink.emit(session, WSResponse.Error(t.message ?: "Error retrieving completions"))
            }
        } finally {
            requestChannel.close()
            sessionScope.coroutineContext.cancelChildren()
        }
    }

    private fun cleanup(session: WebSocketSession, sessionJob: Job, sink: Sinks.Many<WebSocketMessage>) {
        lspProxy.onClientDisconnected(session.id)
        sessionJob.cancel()
        sink.tryEmitComplete()
        session.close()
    }
}

private fun Sinks.Many<WebSocketMessage>.emit(session: WebSocketSession, response: WSResponse) =
    tryEmitNext(session.textMessage(response.toJson()))

sealed interface WSResponse {
    val requestId: String?

    open class Error(val message: String, override val requestId: String? = null) : WSResponse
    data class Init(val sessionId: String, override val requestId: String? = null) : WSResponse
    data class Completions(val completions: List<Completion>, override val requestId: String? = null) : WSResponse
    data class Discarded(override val requestId: String) : Error("discarded", requestId)

    fun toJson(): String = objectMapper.writeValueAsString(this)
}

private data class WsCompletionRequest(
    val requestId: String,
    val project: Project,
    val line: Int,
    val ch: Int,
)

private val objectMapper = ObjectMapper().apply { registerKotlinModule() }
