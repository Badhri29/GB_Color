package com.gbcolors.controller;

import com.gbcolors.model.GameEnums;
import com.gbcolors.model.SessionEntity;
import com.gbcolors.service.AuthService;
import com.gbcolors.service.GameLoopService;
import com.gbcolors.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authenticated user endpoints for real-time Razorpay checkout, dynamic UPI QR, UTR deposits, and withdrawals.
 */
@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private final AuthService authService;
    private final PaymentService paymentService;
    private final GameLoopService gameLoopService;

    public PaymentController(AuthService authService, PaymentService paymentService, GameLoopService gameLoopService) {
        this.authService = authService;
        this.paymentService = paymentService;
        this.gameLoopService = gameLoopService;
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        if (authService.resolveSession(authHeader).isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated."));
        }
        return ResponseEntity.ok(paymentService.getPublicPaymentConfig());
    }

    @PostMapping("/upi/initiate")
    public ResponseEntity<Map<String, Object>> initiateUpi(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        SessionEntity sess = resolveUserSession(authHeader);
        if (sess == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated."));
        }
        Integer amount = parseInt(body == null ? null : body.get("amount"));
        Map<String, Object> res = paymentService.initiateUpiPayment(sess.getEmail(), amount);
        return ResponseEntity.status(Boolean.TRUE.equals(res.get("success")) ? 200 : 400).body(res);
    }

    @PostMapping("/upi/submit-utr")
    public ResponseEntity<Map<String, Object>> submitUtr(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        SessionEntity sess = resolveUserSession(authHeader);
        if (sess == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated."));
        }
        Integer amount = parseInt(body == null ? null : body.get("amount"));
        String utr = body == null || body.get("utr") == null ? "" : String.valueOf(body.get("utr"));
        String method = body == null || body.get("method") == null ? "UPI" : String.valueOf(body.get("method"));
        Map<String, Object> res = gameLoopService.submitDeposit(sess.getEmail(), amount, utr, method);
        return ResponseEntity.status(Boolean.TRUE.equals(res.get("success")) ? 200 : 400).body(res);
    }

    @PostMapping("/razorpay/create-order")
    public ResponseEntity<Map<String, Object>> createRazorpayOrder(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        SessionEntity sess = resolveUserSession(authHeader);
        if (sess == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated."));
        }
        Integer amount = parseInt(body == null ? null : body.get("amount"));
        Map<String, Object> res = paymentService.createRazorpayOrder(sess.getEmail(), amount);
        return ResponseEntity.status(Boolean.TRUE.equals(res.get("success")) ? 200 : 400).body(res);
    }

    @PostMapping("/razorpay/verify")
    public ResponseEntity<Map<String, Object>> verifyRazorpay(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        SessionEntity sess = resolveUserSession(authHeader);
        if (sess == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated."));
        }
        String orderId = str(body, "razorpay_order_id");
        String paymentId = str(body, "razorpay_payment_id");
        String signature = str(body, "razorpay_signature");
        Map<String, Object> res = paymentService.verifyRazorpayPayment(sess.getEmail(), orderId, paymentId, signature);
        return ResponseEntity.status(Boolean.TRUE.equals(res.get("success")) ? 200 : 400).body(res);
    }

    @PostMapping("/withdraw")
    public ResponseEntity<Map<String, Object>> submitWithdraw(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        SessionEntity sess = resolveUserSession(authHeader);
        if (sess == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated."));
        }
        Integer amount = parseInt(body == null ? null : body.get("amount"));
        String withdrawMethod = str(body, "withdrawMethod");
        String upiId = str(body, "upiId");
        String accountHolderName = str(body, "accountHolderName");
        String bankAccountNumber = str(body, "bankAccountNumber");
        String ifscCode = str(body, "ifscCode");
        String bankName = str(body, "bankName");
        String upiOrBank = str(body, "upiOrBank");
        Map<String, Object> res = gameLoopService.submitWithdrawal(
                sess.getEmail(),
                amount,
                withdrawMethod,
                upiId,
                accountHolderName,
                bankAccountNumber,
                ifscCode,
                bankName,
                upiOrBank
        );
        return ResponseEntity.status(Boolean.TRUE.equals(res.get("success")) ? 200 : 400).body(res);
    }

    @GetMapping("/order-status/{orderId}")
    public ResponseEntity<Map<String, Object>> getOrderStatus(@PathVariable("orderId") String orderId) {
        Map<String, Object> res = paymentService.getOrderStatus(orderId);
        return ResponseEntity.status(Boolean.TRUE.equals(res.get("success")) ? 200 : 404).body(res);
    }

    @PostMapping("/webhook/upi")
    public ResponseEntity<Map<String, Object>> handleUpiWebhook(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secretHeader,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(required = false) Map<String, String> allParams
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (allParams != null) payload.putAll(allParams);
        if (body != null) payload.putAll(body);
        Map<String, Object> res = paymentService.processUpiWebhook(payload, secretHeader);
        return ResponseEntity.status(Boolean.TRUE.equals(res.get("success")) ? 200 : 400).body(res);
    }

    @PostMapping("/webhook/sms")
    public ResponseEntity<Map<String, Object>> handleSmsWebhook(
            @RequestHeader(value = "X-SMS-Token", required = false) String tokenHeader,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(required = false) Map<String, String> allParams
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (allParams != null) payload.putAll(allParams);
        if (body != null) payload.putAll(body);
        Map<String, Object> res = paymentService.processSmsWebhook(payload, tokenHeader);
        return ResponseEntity.status(Boolean.TRUE.equals(res.get("success")) ? 200 : 400).body(res);
    }

    @PostMapping("/paypal/create-order")
    public ResponseEntity<Map<String, Object>> createPaypalOrder(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        SessionEntity sess = resolveUserSession(authHeader);
        if (sess == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated."));
        }
        Double usd = parseDouble(body == null ? null : body.get("amountUsd"));
        if (usd == null) {
            Integer inr = parseInt(body == null ? null : body.get("amount"));
            usd = inr != null ? (inr / 85.0) : 10.0;
        }
        Map<String, Object> res = paymentService.createPaypalOrder(sess.getEmail(), BigDecimal.valueOf(usd));
        return ResponseEntity.status(Boolean.TRUE.equals(res.get("success")) ? 200 : 400).body(res);
    }

    @PostMapping("/paypal/capture")
    public ResponseEntity<Map<String, Object>> capturePaypalOrder(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body
    ) {
        SessionEntity sess = resolveUserSession(authHeader);
        if (sess == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Not authenticated."));
        }
        String orderId = str(body, "orderId");
        Map<String, Object> res = paymentService.capturePaypalOrder(sess.getEmail(), orderId);
        return ResponseEntity.status(Boolean.TRUE.equals(res.get("success")) ? 200 : 400).body(res);
    }

    private Double parseDouble(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(obj).trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private SessionEntity resolveUserSession(String authHeader) {
        return authService.resolveSession(authHeader)
                .filter(s -> s.getRole() == GameEnums.SessionRole.USER || s.getRole() == GameEnums.SessionRole.ADMIN)
                .orElse(null);
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
