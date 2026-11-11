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
    }
}
