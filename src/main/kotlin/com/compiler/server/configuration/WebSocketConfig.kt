package com.compiler.server.configuration

import com.compiler.server.controllers.LspCompletionWebSocketHandler
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean

@Configuration
@EnableWebSocket
class WebSocketConfig : WebSocketConfigurer {

    @Autowired
    private lateinit var handler: LspCompletionWebSocketHandler

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/lsp/complete")
            .setAllowedOrigins(ACCESS_CONTROL_ALLOW_ORIGIN_VALUE)
    }

    @Bean
    fun createWebSocketContainer(): ServletServerContainerFactoryBean {
        return ServletServerContainerFactoryBean().apply {
            maxTextMessageBufferSize = (1_000_000) // 1 MB
            maxBinaryMessageBufferSize = (1_000_000)
            maxSessionIdleTimeout = 0L
        }
    }
}