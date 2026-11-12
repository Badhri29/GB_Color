package com.gbcolors.controller;

import com.gbcolors.service.AuthService;
import com.gbcolors.service.GameLoopService;
import com.gbcolors.service.PasswordService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Strictly ADMIN-authenticated portal templates and operational management endpoints.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Map<String, Object> FORBIDDEN_BODY = Map.of(
            "success", false,
            "message", "Access Denied. Admin privileges required."
    );

    private final AuthService authService;
    private final GameLoopService gameLoopService;
    private final PasswordService passwordService;

    public AdminController(
            AuthService authService,
            GameLoopService gameLoopService,
            PasswordService passwordService
    ) {
        this.authService = authService;
        this.gameLoopService = gameLoopService;
        this.passwordService = passwordService;
    }

    @GetMapping("/view")
    public ResponseEntity<?> getAdminView(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        if (authService.resolveAdminSession(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(FORBIDDEN_BODY);
        }
        String html = readProtectedFile("admin-panel.html");
        if (html == null) {
            return ResponseEntity.status(404).body("Admin panel template missing.");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @GetMapping("/script")
    public ResponseEntity<?> getAdminScript(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        if (authService.resolveAdminSession(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(FORBIDDEN_BODY);
        }
        String js = readProtectedFile("admin-client.js");
        if (js == null) {
            return ResponseEntity.status(404).body("// Admin script missing");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript;charset=UTF-8"))
                .body(js);
    }

    @PostMapping("/smtp")
    public ResponseEntity<Map<String, Object>> saveSmtp(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        if (authService.resolveAdminSession(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(FORBIDDEN_BODY);
        }
        String adminEmail = str(body, "adminEmail");
        String host = str(body, "host");
        Integer port = parseInt(body == null ? null : body.get("port"));
        String authUser = str(body, "authUser");
        String authPass = str(body, "authPass");
        String fromName = str(body, "fromName");

        gameLoopService.updateSmtpConfig(adminEmail, host, port, authUser, authPass, fromName);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Admin Email & SMTP Settings saved successfully!"
        ));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        if (authService.resolveAdminSession(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(FORBIDDEN_BODY);
        }
        String newPassword = str(body, "newPassword");
        PasswordService.ValidationResult check = passwordService.validatePassword(newPassword);
        if (!check.valid()) {
            return ResponseEntity.status(400).body(Map.of("success", false, "message", check.message()));
        }
        gameLoopService.changeAdminPassword(newPassword);
        return ResponseEntity.ok(Map.of("success", true, "message", "Admin password updated successfully!"));
    }

    @PostMapping("/withdraw/action")
    public ResponseEntity<Map<String, Object>> withdrawAction(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        if (authService.resolveAdminSession(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(FORBIDDEN_BODY);
        }
        String requestId = str(body, "requestId");
        String action = str(body, "action");
        Map<String, Object> res = gameLoopService.processWithdrawAction(requestId, action);
        int status = Boolean.TRUE.equals(res.get("success")) ? 200 : 400;
        if ("Withdrawal request not found.".equals(res.get("message"))) {
            status = 404;
        }
        return ResponseEntity.status(status).body(res);
    }

    @PostMapping("/user/adjust")
    public ResponseEntity<Map<String, Object>> adjustUserBalance(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        if (authService.resolveAdminSession(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(FORBIDDEN_BODY);
        }
        String email = str(body, "email").toLowerCase();
        Integer delta = parseInt(body == null ? null : body.get("amount"));
        if (email.isEmpty() || delta == null) {
            return ResponseEntity.status(400).body(Map.of("success", false, "message", "Invalid user or amount."));
        }
        Map<String, Object> res = gameLoopService.adjustUserBalance(email, delta);
        int status = Boolean.TRUE.equals(res.get("success")) ? 200 : 400;
        return ResponseEntity.status(status).body(res);
    }

    @PostMapping("/game-config")
    public ResponseEntity<Map<String, Object>> saveGameConfig(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        if (authService.resolveAdminSession(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(FORBIDDEN_BODY);
        }
        Integer cycleSeconds = parseInt(body == null ? null : body.get("cycleDurationSeconds"));
        Integer closeSeconds = parseInt(body == null ? null : body.get("betCloseSeconds"));
        Integer minProfitPct = parseInt(body == null ? null : body.get("adminMinProfitPercent"));
        Map<String, Object> res = gameLoopService.updateGameConfig(cycleSeconds, closeSeconds, minProfitPct);
        int status = Boolean.TRUE.equals(res.get("success")) ? 200 : 400;
        return ResponseEntity.status(status).body(res);
    }

    @PostMapping("/payment-config")
    public ResponseEntity<Map<String, Object>> savePaymentConfig(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        if (authService.resolveAdminSession(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(FORBIDDEN_BODY);
        }
        String upiId = str(body, "upiId");
        String payeeName = str(body, "upiPayeeName");
        String rzpKeyId = str(body, "razorpayKeyId");
        String rzpKeySecret = str(body, "razorpayKeySecret");
        Map<String, Object> res = gameLoopService.updatePaymentConfig(upiId, payeeName, rzpKeyId, rzpKeySecret);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/deposit/action")
    public ResponseEntity<Map<String, Object>> depositAction(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        if (authService.resolveAdminSession(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(FORBIDDEN_BODY);
        }
        String requestId = str(body, "requestId");
        String action = str(body, "action");
        Map<String, Object> res = gameLoopService.processDepositAction(requestId, action);
        int status = Boolean.TRUE.equals(res.get("success")) ? 200 : 400;
        if ("Deposit request not found.".equals(res.get("message"))) {
            status = 404;
        }
        return ResponseEntity.status(status).body(res);
    }

    @PostMapping("/user/block")
    public ResponseEntity<Map<String, Object>> blockUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        if (authService.resolveAdminSession(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(FORBIDDEN_BODY);
        }
        String email = str(body, "email").toLowerCase();
        String blockType = str(body, "blockType");
        Integer durationHours = parseInt(body == null ? null : body.get("durationHours"));
        String reason = str(body, "reason");
        if (email.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("success", false, "message", "User email is required."));
        }
        Map<String, Object> res = gameLoopService.blockUser(email, blockType, durationHours, reason);
        int status = Boolean.TRUE.equals(res.get("success")) ? 200 : 400;
        return ResponseEntity.status(status).body(res);
    }

    @PostMapping("/user/unblock")
    public ResponseEntity<Map<String, Object>> unblockUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        if (authService.resolveAdminSession(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(FORBIDDEN_BODY);
        }
        String email = str(body, "email").toLowerCase();
        if (email.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("success", false, "message", "User email is required."));
        }
        Map<String, Object> res = gameLoopService.unblockUser(email);
        int status = Boolean.TRUE.equals(res.get("success")) ? 200 : 400;
        return ResponseEntity.status(status).body(res);
    }

    private String readProtectedFile(String filename) {
        Path fsPath = Path.of("protected", filename);
        if (Files.exists(fsPath)) {
            try {
                return Files.readString(fsPath, StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }
        ClassPathResource res = new ClassPathResource("protected/" + filename);
        if (res.exists()) {
            try (InputStream is = res.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }
        return null;
    }

    private String str(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) return "";
        return String.valueOf(body.get(key));
    }

    private Integer parseInt(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(obj).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
