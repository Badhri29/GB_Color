package com.gbcolors.config;

import com.gbcolors.websocket.GameWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

/**
 * Exposes native WebSocket endpoint on /ws for same-origin and GitHub Pages origins.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GameWebSocketHandler handler;

    @Value("${gbcolors.frontend-origin:*}")
    private String frontendOrigin;

    public WebSocketConfig(GameWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = "*".equals(frontendOrigin.trim())
                ? new String[]{"*"}
                : Arrays.stream(frontendOrigin.split(",")).map(String::trim).toArray(String[]::new);

        registry.addHandler(handler, "/ws")
                .setAllowedOriginPatterns(origins);
    }
}
