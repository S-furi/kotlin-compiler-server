@file:Suppress("ReactiveStreamsUnusedPublisher")

package completions.controllers.ws

import completions.model.Project
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import completions.lsp.KotlinLspProxy
import completions.lsp.StatefulKotlinLspProxy.onClientConnected
import completions.lsp.StatefulKotlinLspProxy.onClientDisconnected
import completions.service.lsp.LspCompletionProvider
import kotlinx.coroutines.reactor.mono
import model.Completion
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.BufferOverflowStrategy
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers

@Component
class LspCompletionWebSocketHandler(
    private val lspProxy: KotlinLspProxy,
    private val lspCompletionProvider: LspCompletionProvider,
) : WebSocketHandler {

    private val logger = LoggerFactory.getLogger(LspCompletionWebSocketHandler::class.java)

    override fun handle(session: WebSocketSession): Mono<Void?> {
        val sessionId = session.id

        val init: Mono<WSResponse> = mono {
            runCatching {
                lspProxy.requireAvailable()
                lspProxy.onClientConnected(sessionId)
                WSResponse.Init(sessionId)
            }.getOrElse { WSResponse.Error("LSP not available: ${it.message}") }
        }.subscribeOn(Schedulers.boundedElastic())

        val sideSink = Sinks.many().unicast().onBackpressureBuffer<WSResponse>()
        val sideFlux = sideSink.asFlux()

        val requests: Flux<WsCompletionRequest> =
            session.receive()
                .map { it.payloadAsText }
                .flatMap({ payload ->
                    val req = runCatching { objectReader.readValue<WsCompletionRequest>(payload) }.getOrNull()
                    if (req == null) {
                        sideSink.tryEmitNext(WSResponse.Error("Failed to parse request: $payload"))
                        Mono.empty()
                    } else {
                        Mono.just(req)
                    }
                }, 1)
                .onBackpressureBuffer(
                    1,
                    { dropped -> sideSink.tryEmitNext(WSResponse.Discarded(dropped.requestId)) },
                    BufferOverflowStrategy.DROP_OLDEST
                )

        val completions: Flux<WSResponse> =
            requests.concatMap({ request ->
                mono {
                    lspProxy.requireAvailable()
                    lspCompletionProvider.complete(
                        clientId = sessionId,
                        project = request.project,
                        line = request.line,
                        ch = request.ch,
                        applyFuzzyRanking = true,
                    )
                }
                    .subscribeOn(Schedulers.boundedElastic())
                    .map<WSResponse> { WSResponse.Completions(it, request.requestId) }
                    .onErrorResume { e ->
                        logger.warn("Completion processing failed for client $sessionId:", e)
                        Mono.just<WSResponse>(WSResponse.Error(e.message ?: "Unknown error", request.requestId))
                    }
            }, 1)

        val outbound: Flux<WebSocketMessage> =
            Flux.merge(sideFlux, completions)
                .startWith(init)
                .map { session.textMessage(it.toJson()) }

        return session.send(outbound)
            .doOnError { logger.warn("WS session error for client $sessionId: ${it.message}") }
            .doFinally {
                runCatching { lspProxy.onClientDisconnected(sessionId) }
                runCatching { session.close() }
            }
    }

    companion object {
        private val objectReader = objectMapper.readerFor(WsCompletionRequest::class.java)
    }
}

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