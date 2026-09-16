package com.deepon.smartdocs.websocket.config;

import com.deepon.smartdocs.websocket.DocumentWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Registers {@code /ws} with the handler and handshake interceptor (design
 * doc section 5.2). No {@code setAllowedOrigins} call here — origin
 * validation is {@link WsHandshakeInterceptor}'s own explicit job (it also
 * has to apply the "missing Origin rejected in prod" rule Spring's built-in
 * origin check doesn't know about), so this registration deliberately
 * doesn't layer a second, differently-behaved origin check on top.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DocumentWebSocketHandler documentWebSocketHandler;
    private final WsHandshakeInterceptor wsHandshakeInterceptor;
    private final int maxTextMessageBufferBytes;
    private final int idleTimeoutSeconds;

    public WebSocketConfig(DocumentWebSocketHandler documentWebSocketHandler, WsHandshakeInterceptor wsHandshakeInterceptor,
                            @Value("${smartdocs.websocket.max-text-message-bytes:1100000}") int maxTextMessageBufferBytes,
                            @Value("${smartdocs.websocket.idle-timeout-seconds:90}") int idleTimeoutSeconds) {
        this.documentWebSocketHandler = documentWebSocketHandler;
        this.wsHandshakeInterceptor = wsHandshakeInterceptor;
        this.maxTextMessageBufferBytes = maxTextMessageBufferBytes;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(documentWebSocketHandler, "/ws")
                .addInterceptors(wsHandshakeInterceptor);
    }

    /**
     * {@code @Lazy}: this bean's {@code afterPropertiesSet} reads the
     * {@code jakarta.websocket.server.ServerContainer} attribute off the
     * real servlet context, which only a genuinely started embedded Tomcat
     * sets — every {@code @SpringBootTest} using the default MOCK web
     * environment (most of this repo's tests) has no such attribute and
     * would fail context refresh eagerly. Lazy defers that lookup to first
     * real use, which only happens when an actual server has started.
     */
    @Bean
    @Lazy
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(maxTextMessageBufferBytes);
        container.setMaxSessionIdleTimeout(idleTimeoutSeconds * 1000L);
        return container;
    }
}
