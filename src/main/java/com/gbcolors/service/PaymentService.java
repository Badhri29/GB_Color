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
        boolean paypalEnabled = isNotBlank(s.getPaypalClientId()) && isNotBlank(s.getPaypalClientSecret());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("razorpayEnabled", razorpayEnabled);
        out.put("razorpayKeyId", razorpayEnabled ? s.getRazorpayKeyId().trim() : "");
        out.put("paypalEnabled", paypalEnabled);
        out.put("paypalClientId", paypalEnabled ? s.getPaypalClientId().trim() : "");
        out.put("paypalMode", s.getPaypalMode());
        out.put("activeCurrency", s.getActiveCurrency());
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
                + "&tn=" + urlEnc("GB_Colors_Wallet_" + user.getUserId());

        // Save a pending deposit request tracking this dynamic order
        DepositRequestEntity dr = new DepositRequestEntity();
        dr.setId("DEP_" + System.currentTimeMillis() + Integer.toHexString(new Random().nextInt(0xFFFF)));
        dr.setEmail(user.getEmail());
        dr.setUserId(user.getUserId());
        dr.setUsername(user.getUsername());
        dr.setAmount(BigDecimal.valueOf(amount));
        dr.setMethod("UPI_DYNAMIC");
        dr.setOrderId(refId);
        dr.setStatus(GameEnums.DepositStatus.PENDING);
        dr.setRequestedAt(Instant.now());
        depositRepo.save(dr);

        String qrSvg = buildQrSvg(upiUri);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("upiUri", upiUri);
        out.put("upiId", upiId);
        out.put("payeeName", payeeName);
        out.put("amount", amount);
        out.put("refId", refId);
        out.put("orderId", refId);
        out.put("qrSvg", qrSvg);
        return out;
    }

    public Map<String, Object> getOrderStatus(String orderId) {
        if (!isNotBlank(orderId)) {
            return Map.of("success", false, "message", "Order ID required.");
        }
        DepositRequestEntity dr = depositRepo.findByOrderId(orderId.trim())
                .or(() -> depositRepo.findById(orderId.trim()))
                .orElse(null);
        if (dr == null) {
            return Map.of("success", false, "message", "Order not found.");
        }
        return Map.of(
                "success", true,
                "orderId", dr.getOrderId() != null ? dr.getOrderId() : dr.getId(),
                "status", dr.getStatus().name(),
                "amount", dr.getAmount().longValue(),
                "method", dr.getMethod(),
                "utr", dr.getUtrOrPaymentId() != null ? dr.getUtrOrPaymentId() : ""
        );
    }

    /**
     * Processes incoming automated UPI Gateway webhooks (upigateway.com, ekqr.in, etc.).
     */
    public Map<String, Object> processUpiWebhook(Map<String, Object> payload, String secretHeader) {
        if (payload == null || payload.isEmpty()) {
            return Map.of("success", false, "message", "Empty payload.");
        }
        PlatformSettingsEntity s = gameLoopService.getPlatformSettings();
        String expectedSecret = s.getUpiWebhookSecret();
        if (isNotBlank(expectedSecret)) {
            String incomingToken = firstNonBlank(
                    secretHeader,
                    str(payload, "token"),
                    str(payload, "secret"),
                    str(payload, "secret_key")
            );
            if (!expectedSecret.trim().equals(incomingToken.trim())) {
                return Map.of("success", false, "message", "Invalid webhook signature or secret token.");
            }
        }

        String rawStatus = firstNonBlank(
                str(payload, "status"),
                str(payload, "txnStatus"),
                str(payload, "payment_status"),
                str(payload, "order_status")
        ).toUpperCase();

        boolean isPaid = rawStatus.equals("COMPLETED") || rawStatus.equals("SUCCESS")
                || rawStatus.equals("PAID") || rawStatus.equals("TRUE") || rawStatus.equals("1");

        if (!isPaid) {
            return Map.of("success", true, "message", "Non-success status received: " + rawStatus);
        }

        String orderId = firstNonBlank(
                str(payload, "client_txn_id"),
                str(payload, "order_id"),
                str(payload, "orderId"),
                str(payload, "refId"),
                str(payload, "txnid")
        );
        String utr = firstNonBlank(
                str(payload, "upi_txn_id"),
                str(payload, "utr"),
                str(payload, "bank_ref_num"),
                str(payload, "rrn")
        );
        Integer amt = parseAmountInt(payload.get("amount"));

        return gameLoopService.completeAutomatedDeposit(
                orderId,
                utr.isEmpty() ? ("UPI_AUTO_" + System.currentTimeMillis()) : utr,
                "UPI_GATEWAY",
                amt,
                "Automated UPI Gateway Verified"
        );
    }

    /**
     * Processes incoming Bank SMS notification webhooks for zero-fee auto-approval.
     */
    public Map<String, Object> processSmsWebhook(Map<String, Object> payload, String tokenHeader) {
        if (payload == null || payload.isEmpty()) {
            return Map.of("success", false, "message", "Empty SMS payload.");
        }
        PlatformSettingsEntity s = gameLoopService.getPlatformSettings();
        String expectedToken = s.getSmsWebhookToken();
        if (isNotBlank(expectedToken)) {
            String incomingToken = firstNonBlank(tokenHeader, str(payload, "token"), str(payload, "secret"));
            if (!expectedToken.trim().equals(incomingToken.trim())) {
                return Map.of("success", false, "message", "Invalid SMS webhook token.");
            }
        }

        String message = firstNonBlank(str(payload, "message"), str(payload, "body"), str(payload, "sms"));
        if (message.isBlank()) {
            return Map.of("success", false, "message", "Missing SMS message content.");
        }

        // Regex parsing for amount (e.g. "Rs 500.00" or "INR 500" or "credited for Rs.500")
        Integer amount = null;
        java.util.regex.Matcher amtMatcher = java.util.regex.Pattern
                .compile("(?i)(?:rs\\.?|inr)\\s*([0-9]+(?:\\.[0-9]{1,2})?)")
                .matcher(message);
        if (amtMatcher.find()) {
            try {
                amount = (int) Math.round(Double.parseDouble(amtMatcher.group(1)));
            } catch (Exception ignored) {}
        }

        // Regex parsing for UTR (e.g. "UPI:431209876543" or "Ref no 431209876543" or "UTR 431209876543")
        String utr = null;
        java.util.regex.Matcher utrMatcher = java.util.regex.Pattern
                .compile("(?i)(?:upi[:/\\s]+|ref\\s*no[:\\s]*|utr[:\\s]*)([0-9]{10,18})")
                .matcher(message);
        if (utrMatcher.find()) {
            utr = utrMatcher.group(1);
        }

        if (amount == null && utr == null) {
            return Map.of("success", false, "message", "Could not parse amount or UTR from SMS: " + message);
        }

        return gameLoopService.completeAutomatedDeposit(
                null,
                utr != null ? utr : ("SMS_" + System.currentTimeMillis()),
                "BANK_SMS",
                amount,
                "Bank SMS Auto-Verified"
        );
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

    public Map<String, Object> createPaypalOrder(String email, BigDecimal usdAmount) {
        if (usdAmount == null || usdAmount.compareTo(BigDecimal.ONE) < 0) {
            return Map.of("success", false, "message", "Minimum USD deposit is $1.00");
        }
        UserEntity user = userRepo.findByEmail(email).orElse(null);
        if (user == null) {
            return Map.of("success", false, "message", "User account not found.");
        }
        PlatformSettingsEntity s = gameLoopService.getPlatformSettings();
        String clientId = s.getPaypalClientId();
        String secret = s.getPaypalClientSecret();
        if (!isNotBlank(clientId) || !isNotBlank(secret)) {
            return Map.of("success", false, "message", "PayPal is not configured by Admin yet.");
        }

        String baseUrl = "live".equalsIgnoreCase(s.getPaypalMode())
                ? "https://api-m.paypal.com"
                : "https://api-m.sandbox.paypal.com";

        try {
            // 1. Get access token
            String auth = Base64.getEncoder().encodeToString((clientId.trim() + ":" + secret.trim()).getBytes(StandardCharsets.UTF_8));
            HttpRequest tokenReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/oauth2/token"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                    .build();

            HttpResponse<String> tokenRes = httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString());
            if (tokenRes.statusCode() < 200 || tokenRes.statusCode() >= 300) {
                return Map.of("success", false, "message", "PayPal authentication failed. Check credentials.");
            }
            Map<String, Object> tokenData = objectMapper.readValue(tokenRes.body(), new TypeReference<>() {});
            String accessToken = str(tokenData, "access_token");

            // 2. Create order
            String formattedUsd = usdAmount.setScale(2, java.math.RoundingMode.HALF_UP).toString();
            Map<String, Object> orderPayload = Map.of(
                    "intent", "CAPTURE",
                    "purchase_units", List.of(Map.of(
                            "reference_id", "GBC_" + user.getUserId() + "_" + System.currentTimeMillis(),
                            "amount", Map.of(
                                    "currency_code", "USD",
                                    "value", formattedUsd
                            )
                    ))
            );

            HttpRequest orderReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v2/checkout/orders"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(orderPayload)))
                    .build();

            HttpResponse<String> orderRes = httpClient.send(orderReq, HttpResponse.BodyHandlers.ofString());
            if (orderRes.statusCode() < 200 || orderRes.statusCode() >= 300) {
                return Map.of("success", false, "message", "PayPal order creation failed: " + orderRes.body());
            }

            Map<String, Object> orderData = objectMapper.readValue(orderRes.body(), new TypeReference<>() {});
            String orderId = str(orderData, "id");

            // Convert to INR base game wallet units (1 USD ~ ₹85) or credit as raw units
            BigDecimal inrEquivalent = "USD".equalsIgnoreCase(s.getActiveCurrency())
                    ? usdAmount
                    : usdAmount.multiply(BigDecimal.valueOf(85));

            DepositRequestEntity dr = new DepositRequestEntity();
            dr.setId("PP_" + System.currentTimeMillis());
            dr.setEmail(user.getEmail());
            dr.setUserId(user.getUserId());
            dr.setUsername(user.getUsername());
            dr.setAmount(inrEquivalent);
            dr.setMethod("PAYPAL");
            dr.setOrderId(orderId);
            dr.setStatus(GameEnums.DepositStatus.PENDING);
            dr.setRequestedAt(Instant.now());
            depositRepo.save(dr);

            return Map.of(
                    "success", true,
                    "orderId", orderId,
                    "amountUsd", formattedUsd,
                    "inrEquivalent", inrEquivalent.longValue(),
                    "currency", s.getActiveCurrency()
            );
        } catch (Exception ex) {
            return Map.of("success", false, "message", "PayPal error: " + ex.getMessage());
        }
    }

    public Map<String, Object> capturePaypalOrder(String email, String paypalOrderId) {
        if (!isNotBlank(paypalOrderId)) {
            return Map.of("success", false, "message", "PayPal order ID required.");
        }
        PlatformSettingsEntity s = gameLoopService.getPlatformSettings();
        String clientId = s.getPaypalClientId();
        String secret = s.getPaypalClientSecret();
        if (!isNotBlank(clientId) || !isNotBlank(secret)) {
            return Map.of("success", false, "message", "PayPal credentials missing.");
        }

        String baseUrl = "live".equalsIgnoreCase(s.getPaypalMode())
                ? "https://api-m.paypal.com"
                : "https://api-m.sandbox.paypal.com";

        try {
            String auth = Base64.getEncoder().encodeToString((clientId.trim() + ":" + secret.trim()).getBytes(StandardCharsets.UTF_8));
            HttpRequest tokenReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/oauth2/token"))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                    .build();

            HttpResponse<String> tokenRes = httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> tokenData = objectMapper.readValue(tokenRes.body(), new TypeReference<>() {});
            String accessToken = str(tokenData, "access_token");

            HttpRequest capReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v2/checkout/orders/" + paypalOrderId.trim() + "/capture"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> capRes = httpClient.send(capReq, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> capData = objectMapper.readValue(capRes.body(), new TypeReference<>() {});
            String status = str(capData, "status").toUpperCase();

            if ("COMPLETED".equals(status)) {
                return gameLoopService.completeAutomatedDeposit(
                        paypalOrderId.trim(),
                        "PP_CAP_" + paypalOrderId.trim(),
                        "PAYPAL",
                        null,
                        "PayPal Payment Captured"
                );
            }
            return Map.of("success", false, "message", "PayPal status: " + status);
        } catch (Exception ex) {
            return Map.of("success", false, "message", "PayPal capture error: " + ex.getMessage());
        }
    }

    private String str(Map<String, Object> map, String key) {
        if (map == null || map.get(key) == null) return "";
        return String.valueOf(map.get(key)).trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    private Integer parseAmountInt(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.intValue();
        try {
            double d = Double.parseDouble(String.valueOf(val).trim());
            return (int) Math.round(d);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
