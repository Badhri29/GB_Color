package com.gbcolors.controller;

import com.gbcolors.model.GameEnums;
import com.gbcolors.model.SessionEntity;
import com.gbcolors.service.AuthService;
import com.gbcolors.service.GameLoopService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Authenticated user endpoint to acknowledge/mark unread notifications (e.g. withdrawal paid alerts) as read.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final AuthService authService;
    private final GameLoopService gameLoopService;

    public NotificationController(AuthService authService, GameLoopService gameLoopService) {
        this.authService = authService;
        this.gameLoopService = gameLoopService;
    }

    @PostMapping("/read")
    public ResponseEntity<Map<String, Object>> markRead(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        SessionEntity sess = authService.resolveSession(authHeader)
                .filter(s -> s.getRole() == GameEnums.SessionRole.USER)
                .orElse(null);
        if (sess == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated."));
        }
        List<String> ids = null;
        if (body != null && body.get("ids") instanceof List<?> rawList) {
            ids = rawList.stream().filter(Objects -> Objects != null).map(String::valueOf).toList();
        }
        gameLoopService.markNotificationsRead(sess.getEmail(), ids);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
