package com.gbcolors.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbcolors.model.GameEnums;
import com.gbcolors.model.SessionEntity;
import com.gbcolors.repository.SessionRepository;
import com.gbcolors.repository.UserRepository;
import com.gbcolors.service.GameLoopService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Handles real-time WebSocket connections (/ws?token=...) for both Users and Admin.
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final SessionRepository sessionRepo;
    private final UserRepository userRepo;
    private final GameLoopService gameLoopService;
    private final WebSocketHub wsHub;

    public GameWebSocketHandler(
            ObjectMapper objectMapper,
            SessionRepository sessionRepo,
            UserRepository userRepo,
            GameLoopService gameLoopService,
            WebSocketHub wsHub
    ) {
        this.objectMapper = objectMapper;
        this.sessionRepo = sessionRepo;
        this.userRepo = userRepo;
        this.gameLoopService = gameLoopService;
        this.wsHub = wsHub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String token = extractToken(session.getUri());
        if (token == null || token.isBlank()) {
            wsHub.closeUnauthorized(session);
            return;
        }

        SessionEntity sess = sessionRepo.findById(token).orElse(null);
        if (sess == null) {
            wsHub.closeUnauthorized(session);
            return;
        }

        session.getAttributes().put("token", token);
        session.getAttributes().put("email", sess.getEmail());
        session.getAttributes().put("role", sess.getRole());

        if (sess.getRole() == GameEnums.SessionRole.ADMIN) {
            wsHub.registerAdmin(session);
            wsHub.emitToSession(session, "admin_init", gameLoopService.getAdminInitPayload());
            return;
        }

        if (!userRepo.existsByEmail(sess.getEmail())) {
            wsHub.closeUnauthorized(session);
            return;
        }

        wsHub.registerUser(sess.getEmail(), session);
        Map<String, Object> initState = gameLoopService.getInitialStateForUser(sess.getEmail());
        if (initState != null) {
            wsHub.emitToSession(session, "init_state", initState);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Object emailObj = session.getAttributes().get("email");
        Object roleObj = session.getAttributes().get("role");
        if (!(emailObj instanceof String email) || !(roleObj instanceof GameEnums.SessionRole role)) {
            wsHub.closeUnauthorized(session);
            return;
        }

        try {
            Map<String, Object> frame = objectMapper.readValue(message.getPayload(), new TypeReference<>() {});
            String event = asString(frame.get("event"));
            String reqId = asString(frame.get("reqId"));
            Object dataObj = frame.get("data");

            if ("ping".equals(event)) {
                return;
            }

            if (role == GameEnums.SessionRole.ADMIN) {
                if ("admin_set_outcome_mode".equals(event) && dataObj instanceof Map<?, ?> data) {
                    String mode = asString(data.get("mode"));
                    Integer forcedNum = asInteger(data.get("forcedNum"));
                    Map<String, Object> res = gameLoopService.setOutcomeMode(mode, forcedNum);
                    wsHub.sendAck(session, reqId, res);
                }
                return;
            }

            switch (event) {
                case "switch_mode" -> {
                    String mode = dataObj == null ? "DEMO" : String.valueOf(dataObj);
                    wsHub.sendAck(session, reqId, gameLoopService.switchMode(email, mode));
                }
                case "place_bet" -> {
                    if (dataObj instanceof Map<?, ?> data) {
                        Map<String, Object> res = gameLoopService.placeBet(
                                email,
                                asString(data.get("type")),
                                asString(data.get("choice")),
                                asInteger(data.get("amount"))
                        );
                        wsHub.sendAck(session, reqId, res);
                    }
                }
                case "cancel_bet" -> {
                    String betId = dataObj == null ? "" : String.valueOf(dataObj);
                    wsHub.sendAck(session, reqId, gameLoopService.cancelBet(email, betId));
                }
                case "add_demo_balance" -> {
                    Integer amt = asInteger(dataObj);
                    wsHub.sendAck(session, reqId, gameLoopService.addDemoBalance(email, amt));
                }
                case "submit_deposit" -> {
                    if (dataObj instanceof Map<?, ?> data) {
                        Map<String, Object> res = gameLoopService.submitDeposit(
                                email,
                                asInteger(data.get("amount")),
                                asString(data.get("utr")),
                                asString(data.get("method"))
                        );
                        wsHub.sendAck(session, reqId, res);
                    }
                }
                case "submit_withdrawal" -> {
                    if (dataObj instanceof Map<?, ?> data) {
                        Map<String, Object> res = gameLoopService.submitWithdrawal(
                                email,
                                asInteger(data.get("amount")),
                                asString(data.get("upiOrBank"))
                        );
                        wsHub.sendAck(session, reqId, res);
                    }
                }
                default -> {}
            }
        } catch (Exception ex) {
            System.err.println("WebSocket message error: " + ex.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        wsHub.unregister(session);
    }

    private String extractToken(URI uri) {
        if (uri == null || uri.getRawQuery() == null) {
            return null;
        }
        for (String pair : uri.getRawQuery().split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && "token".equals(pair.substring(0, idx))) {
                return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String asString(Object obj) {
        return obj == null ? "" : String.valueOf(obj);
    }

    private Integer asInteger(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(obj).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
