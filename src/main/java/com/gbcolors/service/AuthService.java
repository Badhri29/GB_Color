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
        user.setEmailVerified(true);
        user.setMustChangePassword(false);
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
            gameLoopService.ensureAdminPlayWalletExists();
            String token = generateSessionToken();
            saveSession(token, email, GameEnums.SessionRole.ADMIN);
            Map<String, Object> adminPlayUser = gameLoopService.getSanitizedUserPayload(email);
            return new AuthResult(200, Map.of(
                    "success", true,
                    "message", "Admin Login Successful!",
                    "token", token,
                    "role", "ADMIN",
                    "admin", Map.of("email", email),
                    "user", adminPlayUser != null ? adminPlayUser : Map.of("email", email)
            ));
        }

        UserEntity user = userRepo.findByEmail(email).orElse(null);
        if (user == null || !passwordService.verifyPassword(password, user.getPasswordHash())) {
            return new AuthResult(401, Map.of(
                    "success", false,
                    "message", "Invalid Email ID or Password. Only verified users can log in."
            ));
        }
        if (!user.isEmailVerified()) {
            return new AuthResult(403, Map.of(
                    "success", false,
                    "message", "Email not verified. Only verified accounts can sign in."
            ));
        }

        String blockErr = gameLoopService.getBlockMessageIfActive(user);
        if (blockErr != null) {
            return new AuthResult(403, Map.of(
                    "success", false,
                    "blocked", true,
                    "message", blockErr
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
            gameLoopService.ensureAdminPlayWalletExists();
            Map<String, Object> adminPlayUser = gameLoopService.getSanitizedUserPayload(settings.getAdminEmail().toLowerCase());
            return new AuthResult(200, Map.of(
                    "success", true,
                    "role", "ADMIN",
                    "admin", Map.of("email", settings.getAdminEmail()),
                    "user", adminPlayUser != null ? adminPlayUser : Map.of("email", settings.getAdminEmail())
            ));
        }
        UserEntity userEntity = userRepo.findByEmail(sess.getEmail()).orElse(null);
        if (userEntity == null || !userEntity.isEmailVerified()) {
            return new AuthResult(401, Map.of("success", false, "message", "Verified user not found."));
        }
        String blockErr = gameLoopService.getBlockMessageIfActive(userEntity);
        if (blockErr != null) {
            return new AuthResult(403, Map.of("success", false, "blocked", true, "message", blockErr));
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

    @Transactional
    public AuthResult forgotPassword(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return new AuthResult(400, Map.of("success", false, "message", "Please enter a valid Email ID."));
        }
        PlatformSettingsEntity settings = gameLoopService.getPlatformSettings();
        if (email.equals(settings.getAdminEmail().toLowerCase())) {
            return new AuthResult(400, Map.of(
                    "success", false,
                    "message", "Admin password cannot be reset from user recovery. Change it from Admin Settings."
            ));
        }
        UserEntity user = userRepo.findByEmail(email).orElse(null);
        if (user == null || !user.isEmailVerified()) {
            return new AuthResult(404, Map.of(
                    "success", false,
                    "message", "No verified user account found for this Email ID."
            ));
        }
        String blockErr = gameLoopService.getBlockMessageIfActive(user);
        if (blockErr != null) {
            return new AuthResult(403, Map.of("success", false, "message", blockErr));
        }

        String tempPassword = generateTemporaryPassword();
        try {
            emailOtpService.sendTemporaryPasswordEmail(email, tempPassword, settings);
        } catch (Exception ex) {
            return new AuthResult(500, Map.of(
                    "success", false,
                    "message", "Failed to send temporary password email: " + ex.getMessage()
            ));
        }

        user.setPasswordHash(passwordService.hashPassword(tempPassword));
        user.setMustChangePassword(true);
        userRepo.save(user);
        sessionRepo.deleteByEmail(email);

        return new AuthResult(200, Map.of(
                "success", true,
                "message", "A temporary password has been sent to " + email + ". Check your Inbox (or Spam) and sign in."
        ));
    }

    @Transactional
    public AuthResult changePassword(String authHeader, String currentPassword, String newPassword) {
        SessionEntity sess = resolveSession(authHeader).orElse(null);
        if (sess == null) {
            return new AuthResult(401, Map.of("success", false, "message", "Not authenticated."));
        }
        PasswordService.ValidationResult check = passwordService.validatePassword(newPassword);
        if (!check.valid()) {
            return new AuthResult(400, Map.of("success", false, "message", check.message()));
        }
        if (sess.getRole() == GameEnums.SessionRole.ADMIN) {
            PlatformSettingsEntity settings = gameLoopService.getPlatformSettings();
            if (currentPassword != null && !currentPassword.isEmpty()
                    && !passwordService.verifyPassword(currentPassword, settings.getAdminPasswordHash())) {
                return new AuthResult(400, Map.of("success", false, "message", "Current password is incorrect."));
            }
            gameLoopService.changeAdminPassword(newPassword);
            return new AuthResult(200, Map.of("success", true, "message", "Admin password updated successfully!"));
        }
        UserEntity user = userRepo.findByEmail(sess.getEmail()).orElse(null);
        if (user == null) {
            return new AuthResult(404, Map.of("success", false, "message", "User account not found."));
        }
        if (currentPassword == null || currentPassword.isEmpty()
                || !passwordService.verifyPassword(currentPassword, user.getPasswordHash())) {
            return new AuthResult(400, Map.of("success", false, "message", "Current password is incorrect."));
        }
        user.setPasswordHash(passwordService.hashPassword(newPassword));
        user.setMustChangePassword(false);
        userRepo.save(user);
        return new AuthResult(200, Map.of("success", true, "message", "Your password has been updated!"));
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

    public String generateTemporaryPassword() {
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lower = "abcdefghjkmnpqrstuvwxyz";
        String digits = "23456789";
        String symbols = "@#$%&*";
        StringBuilder sb = new StringBuilder("Gb@");
        sb.append(upper.charAt(RANDOM.nextInt(upper.length())));
        sb.append(lower.charAt(RANDOM.nextInt(lower.length())));
        sb.append(digits.charAt(RANDOM.nextInt(digits.length())));
        sb.append(digits.charAt(RANDOM.nextInt(digits.length())));
        sb.append(symbols.charAt(RANDOM.nextInt(symbols.length())));
        sb.append(upper.charAt(RANDOM.nextInt(upper.length())));
        sb.append(lower.charAt(RANDOM.nextInt(lower.length())));
        return sb.toString();
    }

    private String normalizeEmail(String rawEmail) {
        return rawEmail == null ? "" : rawEmail.trim().toLowerCase();
    }
}
