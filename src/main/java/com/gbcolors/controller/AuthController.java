package com.gbcolors.controller;

import com.gbcolors.config.EnvLoader;
import com.gbcolors.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public health and authentication endpoints.
 */
@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "app", "GB_Colors",
                "mysqlConnected", EnvLoader.isMysqlConnected()
        ));
    }

    @PostMapping("/api/auth/send-otp")
    public ResponseEntity<Map<String, Object>> sendOtp(@RequestBody Map<String, Object> body) {
        String email = body == null ? "" : String.valueOf(body.getOrDefault("email", ""));
        AuthService.AuthResult result = authService.sendOtp(email);
        return ResponseEntity.status(result.statusCode()).body(result.body());
    }

    @PostMapping("/api/auth/signup")
    public ResponseEntity<Map<String, Object>> signup(@RequestBody Map<String, Object> body) {
        String email = body == null ? "" : String.valueOf(body.getOrDefault("email", ""));
        String otp = body == null ? "" : String.valueOf(body.getOrDefault("otp", ""));
        String password = body == null ? "" : String.valueOf(body.getOrDefault("password", ""));
        AuthService.AuthResult result = authService.signup(email, otp, password);
        return ResponseEntity.status(result.statusCode()).body(result.body());
    }

    @PostMapping("/api/auth/signin")
    public ResponseEntity<Map<String, Object>> signin(@RequestBody Map<String, Object> body) {
        String email = body == null ? "" : String.valueOf(body.getOrDefault("email", ""));
        String password = body == null ? "" : String.valueOf(body.getOrDefault("password", ""));
        AuthService.AuthResult result = authService.signin(email, password);
        return ResponseEntity.status(result.statusCode()).body(result.body());
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<Map<String, Object>> me(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        AuthService.AuthResult result = authService.getCurrentSession(authHeader);
        return ResponseEntity.status(result.statusCode()).body(result.body());
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        authService.logout(authHeader);
        return ResponseEntity.ok(Map.of("success", true, "message", "Logged out successfully."));
    }

    @PostMapping("/api/auth/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(@RequestBody Map<String, Object> body) {
        String email = body == null ? "" : String.valueOf(body.getOrDefault("email", ""));
        AuthService.AuthResult result = authService.forgotPassword(email);
        return ResponseEntity.status(result.statusCode()).body(result.body());
    }

    @PostMapping("/api/auth/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        String currentPassword = body == null ? "" : String.valueOf(body.getOrDefault("currentPassword", ""));
        String newPassword = body == null ? "" : String.valueOf(body.getOrDefault("newPassword", ""));
        AuthService.AuthResult result = authService.changePassword(authHeader, currentPassword, newPassword);
        return ResponseEntity.status(result.statusCode()).body(result.body());
    }
}
