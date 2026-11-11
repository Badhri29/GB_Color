package com.gbcolors.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Enforces strict password character rules and hashes credentials via BCrypt.
 */
@Service
public class PasswordService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s");
    private static final Pattern PRINTABLE_ASCII = Pattern.compile("^[\\x21-\\x7E]+$");
    private static final Pattern UPPER = Pattern.compile("[A-Z]");
    private static final Pattern LOWER = Pattern.compile("[a-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final Pattern SYMBOL = Pattern.compile("[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]");

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public record ValidationResult(boolean valid, String message) {}

    public ValidationResult validatePassword(String password) {
        if (password == null || password.isEmpty()) {
            return new ValidationResult(false, "Password is required.");
        }
        if (password.length() < 8 || password.length() > 32) {
            return new ValidationResult(false, "Password must be 8 to 32 characters long.");
        }
        if (WHITESPACE.matcher(password).find()) {
            return new ValidationResult(false, "Password must not contain spaces.");
        }
        if (!PRINTABLE_ASCII.matcher(password).matches()) {
            return new ValidationResult(false, "Password contains invalid characters. Use standard letters, numbers, and symbols only.");
        }
        if (!UPPER.matcher(password).find()) {
            return new ValidationResult(false, "Password must include at least 1 uppercase letter (A-Z).");
        }
        if (!LOWER.matcher(password).find()) {
            return new ValidationResult(false, "Password must include at least 1 lowercase letter (a-z).");
        }
        if (!DIGIT.matcher(password).find()) {
            return new ValidationResult(false, "Password must include at least 1 number (0-9).");
        }
        if (!SYMBOL.matcher(password).find()) {
            return new ValidationResult(false, "Password must include at least 1 special character (e.g., @, #, $, !).");
        }
        return new ValidationResult(true, "OK");
    }

    public String hashPassword(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean verifyPassword(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || !storedHash.startsWith("$2")) {
            return false;
        }
        return encoder.matches(rawPassword, storedHash);
    }
}
