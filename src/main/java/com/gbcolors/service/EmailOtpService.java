package com.gbcolors.service;

import com.gbcolors.model.PlatformSettingsEntity;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages ephemeral 6-digit registration OTPs and sends HTML emails via Gmail SMTP.
 */
@Service
public class EmailOtpService {

    private static final long OTP_TTL_MS = 10 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record OtpEntry(String otp, long expiresAt) {}

    private final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();

    public String generateAndStoreOtp(String email) {
        String otp = String.valueOf(100000 + RANDOM.nextInt(900000));
        otpStore.put(email, new OtpEntry(otp, System.currentTimeMillis() + OTP_TTL_MS));
        return otp;
    }

    public String verifyOtp(String email, String submittedOtp) {
        OtpEntry entry = otpStore.get(email);
        if (entry == null || entry.expiresAt() < System.currentTimeMillis()) {
            return "OTP expired or not requested. Please request a new OTP.";
        }
        if (!entry.otp().equals(submittedOtp)) {
            return "Invalid OTP code. Check the email sent from GB_Colors.";
        }
        return null;
    }

    public void consumeOtp(String email) {
        otpStore.remove(email);
    }

    public void sendRegistrationOtp(String userEmail, String otpCode, PlatformSettingsEntity settings) throws Exception {
        String adminEmail = settings.getAdminEmail() == null ? "" : settings.getAdminEmail();
        String subject = "GB_Colors Verification Code: " + otpCode;
        String htmlBody = """
                <div style="font-family: Arial, sans-serif; background-color: #070d1e; color: #f8fafc; padding: 30px; border-radius: 12px; max-width: 520px; margin: 0 auto; border: 1px solid #1e293b;">
                    <div style="text-align: center; margin-bottom: 24px;">
                        <h1 style="color: #f59e0b; margin: 0; font-size: 26px; letter-spacing: 1px;">GB_<span style="color: #10b981;">Colors</span></h1>
                        <p style="color: #94a3b8; font-size: 13px; margin-top: 6px;">Account Email Verification</p>
                    </div>
                    <div style="background-color: #111a30; padding: 24px; border-radius: 10px; text-align: center; border: 1px solid rgba(255, 255, 255, 0.08);">
                        <p style="color: #cbd5e1; font-size: 14px; margin-bottom: 16px;">Use the 6-digit OTP code below to verify your email and complete your GB_Colors registration:</p>
                        <div style="font-size: 36px; font-weight: 800; letter-spacing: 8px; color: #f59e0b; background: #070d1e; padding: 14px 20px; border-radius: 8px; display: inline-block; font-family: monospace; border: 1px dashed #f59e0b;">
                            %s
                        </div>
                        <p style="color: #94a3b8; font-size: 12px; margin-top: 18px;">This code is valid for 10 minutes. Do not share it with anyone.</p>
                    </div>
                    <div style="text-align: center; margin-top: 24px; color: #64748b; font-size: 11px;">
                        <p>Sent by GB_Colors Official Admin (%s)</p>
                        <p>To: %s</p>
                    </div>
                </div>
                """.formatted(otpCode, adminEmail, userEmail);

        sendHtmlEmail(userEmail, subject, htmlBody, settings);
        System.out.println("📧 [LIVE EMAIL SENT] To: " + userEmail + " | OTP Delivered!");
    }

    public void sendTemporaryPasswordEmail(String userEmail, String tempPassword, PlatformSettingsEntity settings) throws Exception {
        String adminEmail = settings.getAdminEmail() == null ? "" : settings.getAdminEmail();
        String subject = "GB_Colors Temporary Password";
        String htmlBody = """
                <div style="font-family: Arial, sans-serif; background-color: #070d1e; color: #f8fafc; padding: 30px; border-radius: 12px; max-width: 520px; margin: 0 auto; border: 1px solid #1e293b;">
                    <div style="text-align: center; margin-bottom: 24px;">
                        <h1 style="color: #f59e0b; margin: 0; font-size: 26px; letter-spacing: 1px;">GB_<span style="color: #10b981;">Colors</span></h1>
                        <p style="color: #94a3b8; font-size: 13px; margin-top: 6px;">Password Reset — Temporary Password</p>
                    </div>
                    <div style="background-color: #111a30; padding: 24px; border-radius: 10px; text-align: center; border: 1px solid rgba(255, 255, 255, 0.08);">
                        <p style="color: #cbd5e1; font-size: 14px; margin-bottom: 16px;">Use this temporary password to sign in to your verified GB_Colors account. You can change your password anytime from Profile.</p>
                        <div style="font-size: 26px; font-weight: 800; letter-spacing: 2px; color: #10b981; background: #070d1e; padding: 14px 20px; border-radius: 8px; display: inline-block; font-family: monospace; border: 1px dashed #10b981;">
                            %s
                        </div>
                        <p style="color: #94a3b8; font-size: 12px; margin-top: 18px;">Keep this password private and update it after signing in.</p>
                    </div>
                    <div style="text-align: center; margin-top: 24px; color: #64748b; font-size: 11px;">
                        <p>Sent by GB_Colors Official Admin (%s)</p>
                        <p>Account: %s</p>
                    </div>
                </div>
                """.formatted(tempPassword, adminEmail, userEmail);

        sendHtmlEmail(userEmail, subject, htmlBody, settings);
        System.out.println("📧 [TEMP PASSWORD SENT] To: " + userEmail);
    }

    private void sendHtmlEmail(String toEmail, String subject, String htmlBody, PlatformSettingsEntity settings) throws Exception {
        String smtpUser = settings.getSmtpUser() == null ? "" : settings.getSmtpUser().trim();
        String smtpPass = settings.getSmtpPass() == null ? "" : settings.getSmtpPass().replaceAll("\\s+", "");
        if (smtpUser.isEmpty() || smtpPass.isEmpty()) {
            throw new IllegalStateException("SMTP Email Service is not configured. Admin must save Gmail SMTP credentials first.");
        }

        String host = settings.getSmtpHost() == null || settings.getSmtpHost().isBlank()
                ? "smtp.gmail.com" : settings.getSmtpHost().trim();
        int port = settings.getSmtpPort() > 0 ? settings.getSmtpPort() : 587;
        String fromName = settings.getSmtpFromName() == null || settings.getSmtpFromName().isBlank()
                ? "GB_Colors" : settings.getSmtpFromName().trim();

        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(port);
        mailSender.setUsername(smtpUser);
        mailSender.setPassword(smtpPass);

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", port != 465 ? "true" : "false");
        if (port == 465) {
            props.put("mail.smtp.ssl.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout", "8000");
        props.put("mail.smtp.timeout", "8000");
        props.put("mail.smtp.writetimeout", "8000");

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        helper.setFrom(new InternetAddress(smtpUser, fromName));
        helper.setTo(toEmail);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);

        mailSender.send(message);
    }
}
