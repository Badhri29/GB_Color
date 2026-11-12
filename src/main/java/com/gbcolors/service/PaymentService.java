package com.gbcolors.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbcolors.model.*;
import com.gbcolors.repository.DepositRequestRepository;
import com.gbcolors.repository.UserRepository;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Handles real-time Razorpay checkout orders & HMAC verification, plus dynamic UPI QR & deep links.
 */
@Service
public class PaymentService {

    private final GameLoopService gameLoopService;
    private final UserRepository userRepo;
    private final DepositRequestRepository depositRepo;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public PaymentService(
            GameLoopService gameLoopService,
            UserRepository userRepo,
            DepositRequestRepository depositRepo,
            ObjectMapper objectMapper
    ) {
        this.gameLoopService = gameLoopService;
        this.userRepo = userRepo;
        this.depositRepo = depositRepo;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> getPublicPaymentConfig() {
        PlatformSettingsEntity s = gameLoopService.getPlatformSettings();
        boolean razorpayEnabled = isNotBlank(s.getRazorpayKeyId()) && isNotBlank(s.getRazorpayKeySecret());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("razorpayEnabled", razorpayEnabled);
        out.put("razorpayKeyId", razorpayEnabled ? s.getRazorpayKeyId().trim() : "");
        out.put("upiId", s.getUpiId());
        out.put("upiPayeeName", s.getUpiPayeeName());
        out.put("minDeposit", s.getMinDepositAmount());
        out.put("maxDeposit", s.getMaxDepositAmount());
        out.put("minWithdraw", s.getMinWithdrawAmount());
        out.put("maxWithdraw", s.getMaxWithdrawAmount());
        return out;
    }

    public Map<String, Object> initiateUpiPayment(String email, Integer amount) {
        String amtErr = gameLoopService.validateDepositAmount(amount);
        if (amtErr != null) {
            return Map.of("success", false, "message", amtErr);
        }
        UserEntity user = userRepo.findByEmail(email).orElse(null);
        if (user == null) {
            return Map.of("success", false, "message", "User account not found.");
        }
        String blockErr = gameLoopService.getBlockMessageIfActive(user);
        if (blockErr != null) {
            return Map.of("success", false, "message", blockErr);
        }

        PlatformSettingsEntity s = gameLoopService.getPlatformSettings();
        String upiId = s.getUpiId();
        String payeeName = s.getUpiPayeeName();
        String refId = "GBUPI" + System.currentTimeMillis();

        String upiUri = "upi://pay?pa=" + urlEnc(upiId)
                + "&pn=" + urlEnc(payeeName)
                + "&am=" + amount + ".00"
                + "&cu=INR"
                + "&tr=" + urlEnc(refId)
                + "&tn=" + urlEnc("GB_Colors_Wallet_" + user.getUserId());

        String qrSvg = buildQrSvg(upiUri);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("upiUri", upiUri);
        out.put("upiId", upiId);
        out.put("payeeName", payeeName);
        out.put("amount", amount);
        out.put("refId", refId);
        out.put("qrSvg", qrSvg);
        return out;
    }

    public Map<String, Object> createRazorpayOrder(String email, Integer amount) {
        String amtErr = gameLoopService.validateDepositAmount(amount);
        if (amtErr != null) {
            return Map.of("success", false, "message", amtErr);
        }
        UserEntity user = userRepo.findByEmail(email).orElse(null);
        if (user == null) {
            return Map.of("success", false, "message", "User account not found.");
        }
        String blockErr = gameLoopService.getBlockMessageIfActive(user);
        if (blockErr != null) {
            return Map.of("success", false, "message", blockErr);
        }

        PlatformSettingsEntity s = gameLoopService.getPlatformSettings();
        String keyId = s.getRazorpayKeyId() == null ? "" : s.getRazorpayKeyId().trim();
        String keySecret = s.getRazorpayKeySecret() == null ? "" : s.getRazorpayKeySecret().trim();
        if (keyId.isEmpty() || keySecret.isEmpty()) {
            return Map.of(
                    "success", false,
                    "message", "Razorpay Live Checkout is not configured by Admin yet. Please use UPI QR payment."
            );
        }

        try {
            long amountPaise = amount.longValue() * 100L;
            String receipt = "GBC_" + System.currentTimeMillis();
            Map<String, Object> reqBody = Map.of(
                    "amount", amountPaise,
                    "currency", "INR",
                    "receipt", receipt,
                    "notes", Map.of("email", email, "userId", user.getUserId())
            );
            String jsonBody = objectMapper.writeValueAsString(reqBody);
            String authToken = Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.razorpay.com/v1/orders"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Basic " + authToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Map.of("success", false, "message", "Razorpay order creation failed (" + response.statusCode() + "). Check API keys.");
            }

            Map<String, Object> rzpOrder = objectMapper.readValue(response.body(), new TypeReference<>() {});
            String orderId = String.valueOf(rzpOrder.get("id"));

            DepositRequestEntity dr = new DepositRequestEntity();
            dr.setId("RZP_" + System.currentTimeMillis());
            dr.setEmail(user.getEmail());
            dr.setUserId(user.getUserId());
            dr.setUsername(user.getUsername());
            dr.setAmount(BigDecimal.valueOf(amount));
            dr.setMethod("RAZORPAY");
            dr.setOrderId(orderId);
            dr.setStatus(GameEnums.DepositStatus.PENDING);
            dr.setRequestedAt(Instant.now());
            depositRepo.save(dr);

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("orderId", orderId);
            res.put("amountPaise", amountPaise);
            res.put("amount", amount);
            res.put("currency", "INR");
            res.put("keyId", keyId);
            res.put("prefillEmail", user.getEmail());
            res.put("prefillName", user.getUsername());
            return res;
        } catch (Exception ex) {
            return Map.of("success", false, "message", "Payment gateway error: " + ex.getMessage());
        }
    }

    public Map<String, Object> verifyRazorpayPayment(String email, String orderId, String paymentId, String signature) {
        if (!isNotBlank(orderId) || !isNotBlank(paymentId) || !isNotBlank(signature)) {
            return Map.of("success", false, "message", "Missing Razorpay payment verification parameters.");
        }
        PlatformSettingsEntity s = gameLoopService.getPlatformSettings();
        String keySecret = s.getRazorpayKeySecret() == null ? "" : s.getRazorpayKeySecret().trim();
        if (keySecret.isEmpty()) {
            return Map.of("success", false, "message", "Razorpay secret is not configured.");
        }

        String expectedSig = hmacSha256Hex(orderId.trim() + "|" + paymentId.trim(), keySecret);
        boolean valid = expectedSig != null && MessageDigest.isEqual(
                expectedSig.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8)
        );
        if (!valid) {
            return Map.of("success", false, "message", "Payment signature verification failed! Contact support if money was deducted.");
        }

        return gameLoopService.completeRazorpayDeposit(email, orderId.trim(), paymentId.trim());
    }

    public String buildQrSvg(String content) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            StringBuilder sb = new StringBuilder(w * h * 8);
            sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(w).append(' ').append(h)
                    .append("\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"UPI QR Code\">");
            sb.append("<rect width=\"").append(w).append("\" height=\"").append(h).append("\" fill=\"#ffffff\"/>");
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (matrix.get(x, y)) {
                        sb.append("<rect x=\"").append(x).append("\" y=\"").append(y)
                                .append("\" width=\"1\" height=\"1\" fill=\"#0f172a\"/>");
                    }
                }
            }
            sb.append("</svg>");
            return sb.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private String hmacSha256Hex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception ex) {
            return null;
        }
    }

    private String urlEnc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
