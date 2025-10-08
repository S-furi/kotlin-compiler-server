package completions.configuration

import completions.controllers.ws.LspCompletionWebSocketHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter

@Configuration
class WebSocketConfig {
    @Bean
    fun webSocketHandlerAdapter(): WebSocketHandlerAdapter = WebSocketHandlerAdapter()

    @Bean
    fun webSocketMapping(handler: LspCompletionWebSocketHandler): HandlerMapping =
        SimpleUrlHandlerMapping(mapOf("/completions/lsp/complete" to handler), 1)
}