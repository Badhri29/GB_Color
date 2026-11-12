package com.gbcolors.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe WebSocket room hub mirroring Socket.io room semantics (admin_room and user:<email>).
 */
@Component
public class WebSocketHub {

    private final ObjectMapper objectMapper;
    private final Set<WebSocketSession> allSessions = ConcurrentHashMap.newKeySet();
    private final Set<WebSocketSession> adminSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    public WebSocketHub(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void registerAdmin(WebSocketSession session) {
        allSessions.add(session);
        adminSessions.add(session);
    }

    public void registerUser(String email, WebSocketSession session) {
        allSessions.add(session);
        userSessions.computeIfAbsent(email, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(WebSocketSession session) {
        allSessions.remove(session);
        adminSessions.remove(session);
        Object emailAttr = session.getAttributes().get("email");
        if (emailAttr instanceof String email) {
            Set<WebSocketSession> set = userSessions.get(email);
            if (set != null) {
                set.remove(session);
                if (set.isEmpty()) {
                    userSessions.remove(email);
                }
            }
        }
    }

    public void emitToSession(WebSocketSession session, String event, Object data) {
        sendJson(session, Map.of("event", event, "data", data));
    }

    public void sendAck(WebSocketSession session, String reqId, Object payload) {
        if (reqId == null || reqId.isBlank()) {
            return;
        }
        sendJson(session, Map.of("type", "__ack__", "reqId", reqId, "payload", payload));
    }

    public void emitToUser(String email, String event, Object data) {
        Set<WebSocketSession> targets = userSessions.get(email);
        if (targets == null || targets.isEmpty()) {
            return;
        }
        String json = serialize(Map.of("event", event, "data", data));
        if (json == null) return;
        for (WebSocketSession s : targets) {
            sendRawText(s, json);
        }
    }

    public void emitToAdmin(String event, Object data) {
        if (adminSessions.isEmpty()) {
            return;
        }
        String json = serialize(Map.of("event", event, "data", data));
        if (json == null) return;
        for (WebSocketSession s : adminSessions) {
            sendRawText(s, json);
        }
    }

    public void emitAll(String event, Object data) {
        if (allSessions.isEmpty()) {
            return;
        }
        String json = serialize(Map.of("event", event, "data", data));
        if (json == null) return;
        for (WebSocketSession s : allSessions) {
            sendRawText(s, json);
        }
    }

    public void closeUnauthorized(WebSocketSession session) {
        try {
            emitToSession(session, "connect_error", Map.of("message", "AUTH_REQUIRED"));
            session.close(new CloseStatus(4001, "AUTH_REQUIRED"));
        } catch (IOException ignored) {}
    }

    public void disconnectUser(String email, String reason) {
        Set<WebSocketSession> targets = userSessions.remove(email);
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (WebSocketSession s : targets) {
            allSessions.remove(s);
            try {
                if (s.isOpen()) {
                    s.close(new CloseStatus(4003, reason != null ? reason : "ACCOUNT_BLOCKED"));
                }
            } catch (IOException ignored) {}
        }
    }

    public boolean isUserOnline(String email) {
        Set<WebSocketSession> targets = userSessions.get(email);
        return targets != null && targets.stream().anyMatch(WebSocketSession::isOpen);
    }

    private void sendJson(WebSocketSession session, Object body) {
        String json = serialize(body);
        if (json != null) {
            sendRawText(session, json);
        }
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            System.err.println("WebSocket serialization error: " + ex.getMessage());
            return null;
        }
    }

    private void sendRawText(WebSocketSession session, String json) {
        if (session == null || !session.isOpen()) {
            return;
        }
        synchronized (session) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (IOException ignored) {
                unregister(session);
            }
        }
    }
}
