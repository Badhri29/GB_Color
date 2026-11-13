package com.gbcolors;

import com.gbcolors.service.EmailOtpService;
import com.gbcolors.service.PasswordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:gbcolorstest;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "gbcolors.admin-email=gbcolor.admin@gmail.com",
        "gbcolors.admin-default-password=Admin@123",
        "gbcolors.seed-sample-users=true"
})
class AuthAndAdminSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private EmailOtpService emailOtpService;

    @Test
    void healthEndpointRespondsOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.app").value("GB_Colors"));
    }

    @Test
    void passwordValidationRejectsWeakOrSpacePasswordsAndAcceptsStrong() {
        assertFalse(passwordService.validatePassword("weak").valid());
        assertFalse(passwordService.validatePassword("Pass withSpace@1").valid());
        assertFalse(passwordService.validatePassword("lowercase1@").valid());
        assertFalse(passwordService.validatePassword("NoSymbol123").valid());
        assertTrue(passwordService.validatePassword("ValidPass@2026").valid());
    }

    @Test
    void adminRoutesDenyUnauthenticatedAndRegularUsersButAllowAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/view"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/script"))
                .andExpect(status().isForbidden());

        // Sign in as Admin
        MvcResult adminLogin = mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"gbcolor.admin@gmail.com\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();

        String body = adminLogin.getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/admin/view").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("GB_Colors")));
    }

    @Test
    void otpSignupAndSigninFlowWorksForValidUserOnly() throws Exception {
        String testEmail = "player1@example.com";
        String otp = emailOtpService.generateAndStoreOtp(testEmail);

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + testEmail + "\",\"otp\":\"" + otp + "\",\"password\":\"Player@2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.user.email").value(testEmail));

        mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + testEmail + "\",\"password\":\"WrongPass@1\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + testEmail + "\",\"password\":\"Player@2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.role").value("USER"));

        // Admin blocks user temporarily, verify signin returns 403 blocked, then unblock and allow
        MvcResult adminLogin = mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"gbcolor.admin@gmail.com\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String adminToken = adminLogin.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/admin/user/block")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + testEmail + "\",\"blockType\":\"TEMPORARY\",\"durationHours\":24,\"reason\":\"Test block\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + testEmail + "\",\"password\":\"Player@2026\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.blocked").value(true));

        mockMvc.perform(post("/api/admin/user/unblock")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + testEmail + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + testEmail + "\",\"password\":\"Player@2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void adminCanConfigureAndEnforcePaymentMinMaxLimits() throws Exception {
        MvcResult adminLogin = mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"gbcolor.admin@gmail.com\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String adminToken = adminLogin.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        MvcResult userLogin = mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user1@gb.com\",\"password\":\"User@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String userToken = userLogin.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        // Invalid range (min > max) must fail cleanly
        mockMvc.perform(post("/api/admin/payment-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minDeposit\":5000,\"maxDeposit\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        // Update limits
        mockMvc.perform(post("/api/admin/payment-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minDeposit\":150,\"maxDeposit\":2000,\"minWithdraw\":300,\"maxWithdraw\":3000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Verify payment config returns updated limits
        mockMvc.perform(get("/api/payment/config")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minDeposit").value(150))
                .andExpect(jsonPath("$.maxDeposit").value(2000))
                .andExpect(jsonPath("$.minWithdraw").value(300))
                .andExpect(jsonPath("$.maxWithdraw").value(3000));

        // Enforce deposit min/max
        mockMvc.perform(post("/api/payment/upi/initiate")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(post("/api/payment/upi/initiate")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":2500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        // Enforce withdraw min/max
        mockMvc.perform(post("/api/payment/withdraw")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":250,\"upiOrBank\":\"player1@upi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(post("/api/payment/withdraw")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":3500,\"upiOrBank\":\"player1@upi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void unifiedAdminForgotPasswordDirectNotifyAndStructuredWithdrawWork() throws Exception {
        // 1. Admin login exposes both admin info and play wallet (for same User Dashboard)
        MvcResult adminLogin = mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"gbcolor.admin@gmail.com\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.admin.email").value("gbcolor.admin@gmail.com"))
                .andExpect(jsonPath("$.user.email").exists())
                .andReturn();
        String adminToken = adminLogin.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        // 2. Register verified user using OTP store
        String aliceEmail = "alice.verify@example.com";
        String otp = emailOtpService.generateAndStoreOtp(aliceEmail);

        MvcResult signupRes = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + aliceEmail + "\",\"otp\":\"" + otp + "\",\"password\":\"Alice@2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.emailVerified").value(true))
                .andReturn();
        String aliceToken = signupRes.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        // 3. Forgot password rejects unknown email, admin email, and refuses reset when SMTP is unconfigured
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"missing.user@example.com\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"gbcolor.admin@gmail.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + aliceEmail + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));

        // Change password updates password & keeps account valid
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Alice@2026\",\"newPassword\":\"AliceNew@99\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + aliceEmail + "\",\"password\":\"AliceNew@99\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 4. Admin Notification Chat Box: Broadcast reaches everyone, Direct reaches ONLY targetEmail
        mockMvc.perform(post("/api/admin/notify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"ALL\",\"title\":\"System Update\",\"message\":\"Bonus happy hour live\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/admin/notify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"USER\",\"targetEmail\":\"" + aliceEmail + "\",\"title\":\"Private VIP\",\"message\":\"Only for Alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Verify Alice sees both Broadcast and Direct in /api/auth/me notifications, while user1 sees Broadcast ONLY
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bonus happy hour live")))
                .andExpect(content().string(containsString("Only for Alice")));

        MvcResult user1Login = mockMvc.perform(post("/api/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user1@gb.com\",\"password\":\"User@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String user1Token = user1Login.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        String user1Notifs = mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + user1Token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bonus happy hour live")))
                .andReturn().getResponse().getContentAsString();
        assertFalse(user1Notifs.contains("Only for Alice"), "Direct notification must not leak to other users");

        // 5. Structured BANK & UPI withdrawal validation on /api/payment/withdraw
        // user1 starts with realBalance = 1500
        mockMvc.perform(post("/api/payment/withdraw")
                        .header("Authorization", "Bearer " + user1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":350,\"withdrawMethod\":\"BANK\",\"accountHolderName\":\"Ravi Kumar\",\"bankAccountNumber\":\"12345\",\"ifscCode\":\"BAD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(post("/api/payment/withdraw")
                        .header("Authorization", "Bearer " + user1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":350,\"withdrawMethod\":\"BANK\",\"accountHolderName\":\"Ravi Kumar\",\"bankAccountNumber\":\"123456789012\",\"ifscCode\":\"HDFC0001234\",\"bankName\":\"HDFC Bank\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + user1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.withdrawRequests[0].ifscCode").value("HDFC0001234"))
                .andExpect(jsonPath("$.user.withdrawRequests[0].withdrawMethod").value("BANK"));

        // 6. Automated UPI Order Initiation & Webhook Auto-Approval Test
        MvcResult upiOrderRes = mockMvc.perform(post("/api/payment/upi/initiate")
                        .header("Authorization", "Bearer " + user1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.orderId").isNotEmpty())
                .andReturn();

        String upiOrderId = upiOrderRes.getResponse().getContentAsString()
                .replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");

        // Verify order status is PENDING initially
        mockMvc.perform(get("/api/payment/order-status/" + upiOrderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(500));

        // Simulate Automated UPI Gateway Webhook sending instant confirmation
        mockMvc.perform(post("/api/payment/webhook/upi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_txn_id\":\"" + upiOrderId + "\",\"status\":\"COMPLETED\",\"amount\":500,\"upi_txn_id\":\"431200998877\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Order status is now APPROVED and balance is credited automatically
        mockMvc.perform(get("/api/payment/order-status/" + upiOrderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 7. Bank SMS Auto-Approval Webhook Test
        MvcResult upiOrder2Res = mockMvc.perform(post("/api/payment/upi/initiate")
                        .header("Authorization", "Bearer " + user1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isOk())
                .andReturn();
        String upiOrder2Id = upiOrder2Res.getResponse().getContentAsString()
                .replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/payment/webhook/sms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sender\":\"HDFCBK\",\"message\":\"Dear UPI user A/C ... credited by INR 1000.00 on 13-Nov-26 by UPI:431200889900\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/payment/order-status/" + upiOrder2Id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 8. Admin Payment Config update with Multi-Currency & Webhook Secret
        mockMvc.perform(post("/api/admin/payment-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upiId\":\"badhrinarayanan79-3@okhdfcbank\",\"upiPayeeName\":\"Badhri Narayanan\",\"activeCurrency\":\"USD\",\"upiWebhookSecret\":\"whsec_test123\",\"smsWebhookToken\":\"sms_test456\",\"minDeposit\":100,\"maxDeposit\":50000,\"minWithdraw\":200,\"maxWithdraw\":50000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
