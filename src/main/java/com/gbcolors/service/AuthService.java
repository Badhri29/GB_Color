package com.gbcolors.service;

import com.gbcolors.model.*;
import com.gbcolors.repository.SessionRepository;
import com.gbcolors.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Handles OTP issuance, user registration, login, session verification, and logout.
 */
@Service
public class AuthService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepo;
    private final SessionRepository sessionRepo;
    private final PasswordService passwordService;
    private final EmailOtpService emailOtpService;
    private final GameLoopService gameLoopService;

    public AuthService(
            UserRepository userRepo,
            SessionRepository sessionRepo,
            PasswordService passwordService,
            EmailOtpService emailOtpService,
            GameLoopService gameLoopService
    ) {
        this.userRepo = userRepo;
        this.sessionRepo = sessionRepo;
        this.passwordService = passwordService;
        this.emailOtpService = emailOtpService;
        this.gameLoopService = gameLoopService;
    }

    public record AuthResult(int statusCode, Map<String, Object> body) {}

    public AuthResult sendOtp(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return new AuthResult(400, Map.of("success", false, "message", "Please enter a valid Email ID."));
        }

        PlatformSettingsEntity settings = gameLoopService.getPlatformSettings();
        if (email.equals(settings.getAdminEmail().toLowerCase()) || userRepo.existsByEmail(email)) {
            return new AuthResult(400, Map.of(
                    "success", false,
                    "message", "This Email ID is already registered. Please Sign In instead."
            ));
        }

        String otp = emailOtpService.generateAndStoreOtp(email);
        try {
            emailOtpService.sendRegistrationOtp(email, otp, settings);
            return new AuthResult(200, Map.of(
                    "success", true,
                    "mode", "SMTP",
                    "message", "Verification OTP sent to " + email + ". Please check your Inbox (or Spam folder)."
            ));
        } catch (Exception ex) {
            System.err.println("Send OTP Error: " + ex.getMessage());
            return new AuthResult(500, Map.of(
                    "success", false,
                    "message", "Failed to send OTP email: " + ex.getMessage()
            ));
        }
    }

    @Transactional
    public AuthResult signup(String rawEmail, String rawOtp, String password) {
        String email = normalizeEmail(rawEmail);
        String otp = rawOtp == null ? "" : rawOtp.trim();

        if (email.isEmpty() || otp.isEmpty() || password == null || password.isEmpty()) {
            return new AuthResult(400, Map.of("success", false, "message", "Email, OTP, and Password are all required."));
        }

        PlatformSettingsEntity settings = gameLoopService.getPlatformSettings();
        if (email.equals(settings.getAdminEmail().toLowerCase()) || userRepo.existsByEmail(email)) {
            return new AuthResult(400, Map.of("success", false, "message", "Account already exists. Please Sign In."));
        }

        String otpErr = emailOtpService.verifyOtp(email, otp);
        if (otpErr != null) {
            return new AuthResult(400, Map.of("success", false, "message", otpErr));
        }

        PasswordService.ValidationResult passCheck = passwordService.validatePassword(password);
        if (!passCheck.valid()) {
            return new AuthResult(400, Map.of("success", false, "message", passCheck.message()));
        }

        emailOtpService.consumeOtp(email);

        UserEntity user = new UserEntity();
        user.setUserId("GB" + (100000 + RANDOM.nextInt(900000)));
        user.setEmail(email);
        user.setUsername(email.contains("@") ? email.substring(0, email.indexOf('@')) : email);
        user.setPasswordHash(passwordService.hashPassword(password));
        user.setActiveMode(GameEnums.ActiveMode.DEMO);
        user.setCreatedAt(Instant.now());
        userRepo.save(user);

        String token = generateSessionToken();
        saveSession(token, email, GameEnums.SessionRole.USER);

        gameLoopService.broadcastAdminUpdate();

        Map<String, Object> sanitizedUser = gameLoopService.getSanitizedUserPayload(email);
        return new AuthResult(200, Map.of(
                "success", true,
                "message", "Email verified & GB_Colors account created!",
                "token", token,
                "role", "USER",
                "user", sanitizedUser
        ));
    }

    @Transactional
    public AuthResult signin(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        if (email.isEmpty() || password == null || password.isEmpty()) {
            return new AuthResult(400, Map.of("success", false, "message", "Please enter both Email ID and Password."));
        }

        PlatformSettingsEntity settings = gameLoopService.getPlatformSettings();
        if (email.equals(settings.getAdminEmail().toLowerCase())) {
            if (!passwordService.verifyPassword(password, settings.getAdminPasswordHash())) {
                return new AuthResult(401, Map.of("success", false, "message", "Invalid Email ID or Password."));
            }
            String token = generateSessionToken();
            saveSession(token, email, GameEnums.SessionRole.ADMIN);
            return new AuthResult(200, Map.of(
                    "success", true,
                    "message", "Admin Login Successful!",
                    "token", token,
                    "role", "ADMIN",
                    "admin", Map.of("email", email)
            ));
        }

        UserEntity user = userRepo.findByEmail(email).orElse(null);
        if (user == null || !passwordService.verifyPassword(password, user.getPasswordHash())) {
            return new AuthResult(401, Map.of(
                    "success", false,
                    "message", "Invalid Email ID or Password. Only verified users can log in."
            ));
        }

        String token = generateSessionToken();
        saveSession(token, email, GameEnums.SessionRole.USER);
        Map<String, Object> sanitizedUser = gameLoopService.getSanitizedUserPayload(email);

        return new AuthResult(200, Map.of(
                "success", true,
                "message", "Sign In Successful!",
                "token", token,
                "role", "USER",
                "user", sanitizedUser
        ));
    }

    public AuthResult getCurrentSession(String authHeader) {
        SessionEntity sess = resolveSession(authHeader).orElse(null);
        if (sess == null) {
            return new AuthResult(401, Map.of("success", false, "message", "Not authenticated."));
        }
        if (sess.getRole() == GameEnums.SessionRole.ADMIN) {
            PlatformSettingsEntity settings = gameLoopService.getPlatformSettings();
            return new AuthResult(200, Map.of(
                    "success", true,
                    "role", "ADMIN",
                    "admin", Map.of("email", settings.getAdminEmail())
            ));
        }
        Map<String, Object> user = gameLoopService.getSanitizedUserPayload(sess.getEmail());
        if (user == null) {
            return new AuthResult(401, Map.of("success", false, "message", "User not found."));
        }
        return new AuthResult(200, Map.of(
                "success", true,
                "role", "USER",
                "user", user
        ));
    }

    @Transactional
    public void logout(String authHeader) {
        resolveSession(authHeader).ifPresent(sessionRepo::delete);
    }

    public Optional<SessionEntity> resolveAdminSession(String authHeader) {
        return resolveSession(authHeader).filter(s -> s.getRole() == GameEnums.SessionRole.ADMIN);
    }

    public Optional<SessionEntity> resolveSession(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return Optional.empty();
        }
        String token = authHeader.replace("Bearer ", "").trim();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        return sessionRepo.findById(token);
    }

    private void saveSession(String token, String email, GameEnums.SessionRole role) {
        SessionEntity s = new SessionEntity();
        s.setToken(token);
        s.setEmail(email);
        s.setRole(role);
        s.setCreatedAt(Instant.now());
        sessionRepo.save(s);
    }

    private String generateSessionToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String normalizeEmail(String rawEmail) {
        return rawEmail == null ? "" : rawEmail.trim().toLowerCase();
    }
}
