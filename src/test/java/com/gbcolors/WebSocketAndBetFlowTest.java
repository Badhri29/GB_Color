package com.gbcolors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gbcolors.service.AuthService;
import com.gbcolors.service.EmailOtpService;
import com.gbcolors.service.GameLoopService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:gbcolorswstest;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "gbcolors.admin-email=gbcolor@admin.com",
        "gbcolors.admin-default-password=Admin@123"
})
class WebSocketAndBetFlowTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AuthService authService;

    @Autowired
    private EmailOtpService emailOtpService;

    @Autowired
    private GameLoopService gameLoopService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void authenticatedWebSocketBetCancelDepositAndWithdrawFlow() throws Exception {
        String email = "wsplayer@example.com";
        String otp = emailOtpService.generateAndStoreOtp(email);
        AuthService.AuthResult signupRes = authService.signup(email, otp, "Player@2026");
        assertEquals(200, signupRes.statusCode());
        String token = String.valueOf(signupRes.body().get("token"));

        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        List<JsonNode> buffered = new ArrayList<>();
        StringBuilder partialBuffer = new StringBuilder();

        WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws?token=" + token), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        partialBuffer.append(data);
                        if (last) {
                            messages.offer(partialBuffer.toString());
                            partialBuffer.setLength(0);
                        }
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }
                })
                .get(5, TimeUnit.SECONDS);

        // 1. Expect init_state frame
        JsonNode initFrame = waitForEvent(messages, buffered, "init_state");
        assertNotNull(initFrame);
        assertEquals(1000L, initFrame.get("data").get("user").get("demoBalance").asLong());

        // 2. Place bet via WebSocket with reqId ack
        String placePayload = objectMapper.writeValueAsString(Map.of(
                "event", "place_bet",
                "reqId", "req_1",
                "data", Map.of("type", "COLOR", "choice", "GREEN", "amount", 100)
        ));
        ws.sendText(placePayload, true).get(3, TimeUnit.SECONDS);
        JsonNode placeAck = waitForAck(messages, buffered, "req_1");
        assertTrue(placeAck.get("payload").get("success").asBoolean());
        String betId = placeAck.get("payload").get("bet").get("id").asText();

        JsonNode userAfterBet = waitForEvent(messages, buffered, "user_updated");
        assertEquals(900L, userAfterBet.get("data").get("demoBalance").asLong());

        // 3. Cancel bet and verify refund
        String cancelPayload = objectMapper.writeValueAsString(Map.of(
                "event", "cancel_bet",
                "reqId", "req_2",
                "data", betId
        ));
        ws.sendText(cancelPayload, true).get(3, TimeUnit.SECONDS);
        JsonNode cancelAck = waitForAck(messages, buffered, "req_2");
        assertTrue(cancelAck.get("payload").get("success").asBoolean());

        JsonNode userAfterCancel = waitForEvent(messages, buffered, "user_updated");
        assertEquals(1000L, userAfterCancel.get("data").get("demoBalance").asLong());

        // 4. Deposit request -> Admin approval -> Withdrawal -> Offline Paid -> Next Login Notification
        ws.sendText(objectMapper.writeValueAsString(Map.of(
                "event", "submit_deposit",
                "reqId", "req_3",
                "data", Map.of("amount", 500, "utr", "998877665544", "method", "UPI")
        )), true).get(3, TimeUnit.SECONDS);
        JsonNode depAck = waitForAck(messages, buffered, "req_3");
        assertTrue(depAck.get("payload").get("success").asBoolean());
        String depositId = depAck.get("payload").get("depositId").asText();

        Map<String, Object> approveRes = gameLoopService.processDepositAction(depositId, "APPROVED");
        assertTrue(Boolean.TRUE.equals(approveRes.get("success")));

        JsonNode depNotif = waitForEvent(messages, buffered, "notification");
        assertEquals("DEPOSIT_APPROVED", depNotif.get("data").get("type").asText());

        ws.sendText(objectMapper.writeValueAsString(Map.of(
                "event", "submit_withdrawal",
                "reqId", "req_4",
                "data", Map.of("amount", 200, "upiOrBank", "player@upi")
        )), true).get(3, TimeUnit.SECONDS);
        JsonNode wthAck = waitForAck(messages, buffered, "req_4");
        assertTrue(wthAck.get("payload").get("success").asBoolean());
        String withdrawId = wthAck.get("payload").get("requestId").asText();

        // Simulate user logging out / disconnecting before admin completes payout
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "logout").get(3, TimeUnit.SECONDS);
        Map<String, Object> paidRes = gameLoopService.processWithdrawAction(withdrawId, "PAID");
        assertTrue(Boolean.TRUE.equals(paidRes.get("success")));

        // Reconnect and verify unread withdrawal notification arrives first on login
        BlockingQueue<String> messages2 = new LinkedBlockingQueue<>();
        List<JsonNode> buffered2 = new ArrayList<>();
        StringBuilder partial2 = new StringBuilder();
        WebSocket ws2 = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws?token=" + token), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        partial2.append(data);
                        if (last) {
                            messages2.offer(partial2.toString());
                            partial2.setLength(0);
                        }
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }
                })
                .get(5, TimeUnit.SECONDS);

        JsonNode initAfterPaid = waitForEvent(messages2, buffered2, "init_state");
        JsonNode unreadNotifs = initAfterPaid.get("data").get("user").get("unreadNotifications");
        assertTrue(unreadNotifs.isArray() && unreadNotifs.size() >= 1);
        assertEquals("WITHDRAW_PAID", unreadNotifs.get(unreadNotifs.size() - 1).get("type").asText());

        ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS);
        assertNotNull(gameLoopService.getSanitizedUserPayload(email));
    }

    @Test
    void unauthorizedWebSocketIsRejectedWithAuthRequired() throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        List<JsonNode> buffered = new ArrayList<>();
        CompletableFuture<Integer> closedCode = new CompletableFuture<>();
        StringBuilder partialBuffer = new StringBuilder();

        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws?token=invalid_token"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        partialBuffer.append(data);
                        if (last) {
                            messages.offer(partialBuffer.toString());
                            partialBuffer.setLength(0);
                        }
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        closedCode.complete(statusCode);
                        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                    }
                })
                .get(5, TimeUnit.SECONDS);

        JsonNode errFrame = waitForEvent(messages, buffered, "connect_error");
        assertEquals("AUTH_REQUIRED", errFrame.get("data").get("message").asText());
        assertEquals(4001, closedCode.get(5, TimeUnit.SECONDS));
    }

    private JsonNode waitForEvent(BlockingQueue<String> queue, List<JsonNode> buffered, String eventName) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Iterator<JsonNode> it = buffered.iterator();
            while (it.hasNext()) {
                JsonNode n = it.next();
                if (n.has("event") && eventName.equals(n.get("event").asText())) {
                    it.remove();
                    return n;
                }
            }
            String raw = queue.poll(250, TimeUnit.MILLISECONDS);
            if (raw != null) {
                buffered.add(objectMapper.readTree(raw));
            }
        }
        fail("Timed out waiting for event: " + eventName);
        return null;
    }

    private JsonNode waitForAck(BlockingQueue<String> queue, List<JsonNode> buffered, String reqId) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Iterator<JsonNode> it = buffered.iterator();
            while (it.hasNext()) {
                JsonNode n = it.next();
                if (n.has("type") && "__ack__".equals(n.get("type").asText())
                        && reqId.equals(n.get("reqId").asText())) {
                    it.remove();
                    return n;
                }
            }
            String raw = queue.poll(250, TimeUnit.MILLISECONDS);
            if (raw != null) {
                buffered.add(objectMapper.readTree(raw));
            }
        }
        fail("Timed out waiting for ack: " + reqId);
        return null;
    }
}
