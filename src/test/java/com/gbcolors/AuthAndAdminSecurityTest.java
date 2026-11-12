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
        "gbcolors.admin-default-password=Admin@123"
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
}
