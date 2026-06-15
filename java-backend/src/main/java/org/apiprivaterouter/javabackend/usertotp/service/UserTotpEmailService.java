package org.apiprivaterouter.javabackend.usertotp.service;

import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.usertotp.repository.UserTotpRepository;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.security.SecureRandom;

@Service
public class UserTotpEmailService {

    private static final List<String> SMTP_SETTING_KEYS = List.of(
            "smtp_host",
            "smtp_port",
            "smtp_username",
            "smtp_password",
            "smtp_from",
            "smtp_from_name",
            "smtp_use_tls"
    );

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final UserTotpRepository repository;

    public UserTotpEmailService(UserTotpRepository repository) {
        this.repository = repository;
    }

    public String generateCode() {
        int value = SECURE_RANDOM.nextInt(0, 1_000_000);
        return String.format("%06d", value);
    }

    public void sendVerifyCode(String email, String siteName, String code) {
        SmtpConfig smtpConfig = loadSmtpConfig();
        try {
            JavaMailSenderImpl sender = buildSender(smtpConfig);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setTo(email);
            helper.setFrom(resolveFromAddress(smtpConfig), resolveFromName(smtpConfig, siteName));
            helper.setSubject("[" + resolveSiteName(siteName) + "] Email Verification Code");
            helper.setText(buildVerifyCodeEmailBody(code, resolveSiteName(siteName)), true);
            sender.send(message);
        } catch (MailAuthenticationException ex) {
            throw new HttpStatusException(503, "email verification delivery authentication failed");
        } catch (MailSendException ex) {
            throw new HttpStatusException(503, "email verification delivery failed");
        } catch (MailException | MessagingException | UnsupportedEncodingException ex) {
            throw new HttpStatusException(503, "email verification delivery is not configured");
        }
    }

    public void sendPasswordResetEmail(String email, String siteName, String resetUrl) {
        SmtpConfig smtpConfig = loadSmtpConfig();
        try {
            JavaMailSenderImpl sender = buildSender(smtpConfig);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setTo(email);
            helper.setFrom(resolveFromAddress(smtpConfig), resolveFromName(smtpConfig, siteName));
            helper.setSubject("[" + resolveSiteName(siteName) + "] Password Reset Request");
            helper.setText(buildPasswordResetEmailBody(resetUrl, resolveSiteName(siteName)), true);
            sender.send(message);
        } catch (MailAuthenticationException ex) {
            throw new HttpStatusException(503, "email verification delivery authentication failed");
        } catch (MailSendException ex) {
            throw new HttpStatusException(503, "email verification delivery failed");
        } catch (MailException | MessagingException | UnsupportedEncodingException ex) {
            throw new HttpStatusException(503, "email verification delivery is not configured");
        }
    }

    private SmtpConfig loadSmtpConfig() {
        Map<String, String> settings = repository.getSettings(SMTP_SETTING_KEYS);
        String host = trimToNull(settings.get("smtp_host"));
        if (host == null) {
            throw new HttpStatusException(503, "email verification delivery is not configured");
        }
        int port = parsePort(settings.get("smtp_port"));
        String username = trimToNull(settings.get("smtp_username"));
        String password = trimToNull(settings.get("smtp_password"));
        String from = trimToNull(settings.get("smtp_from"));
        if (from == null) {
            throw new HttpStatusException(503, "email verification delivery is not configured");
        }
        String fromName = trimToNull(settings.get("smtp_from_name"));
        boolean useTls = "true".equalsIgnoreCase(trimToNull(settings.get("smtp_use_tls")));
        return new SmtpConfig(host, port, username, password, from, fromName, useTls);
    }

    private JavaMailSenderImpl buildSender(SmtpConfig config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.host());
        sender.setPort(config.port());
        sender.setProtocol("smtp");
        if (config.username() != null) {
            sender.setUsername(config.username());
        }
        if (config.password() != null) {
            sender.setPassword(config.password());
        }
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.auth", config.username() != null && config.password() != null ? "true" : "false");
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "20000");
        properties.put("mail.smtp.writetimeout", "20000");
        properties.put("mail.smtp.starttls.enable", config.useTls() ? "true" : "false");
        properties.put("mail.smtp.starttls.required", config.useTls() ? "true" : "false");
        properties.put("mail.smtp.ssl.enable", Boolean.toString(!config.useTls() && config.port() == 465));
        properties.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        return sender;
    }

    private String resolveFromAddress(SmtpConfig config) {
        return config.from();
    }

    private String resolveFromName(SmtpConfig config, String siteName) {
        String fromName = trimToNull(config.fromName());
        if (fromName != null) {
            return fromName;
        }
        return resolveSiteName(siteName);
    }

    private String resolveSiteName(String siteName) {
        String resolved = trimToNull(siteName);
        return resolved == null ? "api-private-router" : resolved;
    }

    private int parsePort(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return 587;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 587;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String buildVerifyCodeEmailBody(String code, String siteName) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background-color: #f5f5f5; margin: 0; padding: 20px; }
                        .container { max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
                        .header { background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); color: white; padding: 30px; text-align: center; }
                        .header h1 { margin: 0; font-size: 24px; }
                        .content { padding: 40px 30px; text-align: center; }
                        .code { font-size: 36px; font-weight: bold; letter-spacing: 8px; color: #333; background-color: #f8f9fa; padding: 20px 30px; border-radius: 8px; display: inline-block; margin: 20px 0; font-family: monospace; }
                        .info { color: #666; font-size: 14px; line-height: 1.6; margin-top: 20px; }
                        .footer { background-color: #f8f9fa; padding: 20px; text-align: center; color: #999; font-size: 12px; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header">
                            <h1>%s</h1>
                        </div>
                        <div class="content">
                            <p style="font-size: 18px; color: #333;">Your verification code is:</p>
                            <div class="code">%s</div>
                            <div class="info">
                                <p>This code will expire in <strong>15 minutes</strong>.</p>
                                <p>If you did not request this code, please ignore this email.</p>
                            </div>
                        </div>
                        <div class="footer">
                            <p>This is an automated message, please do not reply.</p>
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(siteName, code);
    }

    private String buildPasswordResetEmailBody(String resetUrl, String siteName) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background-color: #f5f5f5; margin: 0; padding: 20px; }
                        .container { max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
                        .header { background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); color: white; padding: 30px; text-align: center; }
                        .header h1 { margin: 0; font-size: 24px; }
                        .content { padding: 40px 30px; text-align: center; }
                        .button { display: inline-block; background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); color: white; padding: 14px 32px; text-decoration: none; border-radius: 8px; font-size: 16px; font-weight: 600; margin: 20px 0; }
                        .info { color: #666; font-size: 14px; line-height: 1.6; margin-top: 20px; }
                        .link-fallback { color: #666; font-size: 12px; word-break: break-all; margin-top: 20px; padding: 15px; background-color: #f8f9fa; border-radius: 4px; }
                        .footer { background-color: #f8f9fa; padding: 20px; text-align: center; color: #999; font-size: 12px; }
                        .warning { color: #e74c3c; font-weight: 500; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header">
                            <h1>%s</h1>
                        </div>
                        <div class="content">
                            <p style="font-size: 18px; color: #333;">Password reset request</p>
                            <p style="color: #666;">You requested to reset your password. Click the button below to continue:</p>
                            <a href="%s" class="button">Reset Password</a>
                            <div class="info">
                                <p>This link will expire in <strong>30 minutes</strong>.</p>
                                <p class="warning">If you did not request a password reset, please ignore this email.</p>
                            </div>
                            <div class="link-fallback">
                                <p>If the button does not work, open this link directly:</p>
                                <p>%s</p>
                            </div>
                        </div>
                        <div class="footer">
                            <p>This is an automated message, please do not reply.</p>
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(siteName, resetUrl, resetUrl);
    }

    private record SmtpConfig(
            String host,
            int port,
            String username,
            String password,
            String from,
            String fromName,
            boolean useTls
    ) {
    }
}
