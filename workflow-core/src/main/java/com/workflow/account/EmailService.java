package com.workflow.account;

import com.workflow.security.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends transactional email — currently the account-verification message.
 *
 * <p>SMTP is optional: when no {@link JavaMailSender} is configured ({@code spring.mail.host}
 * unset) the verification link is logged at WARN instead of sent, so closed-beta works
 * without an email provider. A send failure is logged, never propagated — it must not
 * break registration.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;   // null when SMTP is not configured
    private final String appBaseUrl;
    private final String from;

    public EmailService(ObjectProvider<JavaMailSender> mailSender,
                        @Value("${workflow.app-base-url:http://localhost:5173}") String appBaseUrl,
                        @Value("${workflow.mail.from:no-reply@ai-workflow.local}") String from) {
        this.mailSender = mailSender.getIfAvailable();
        this.appBaseUrl = appBaseUrl;
        this.from = from;
    }

    /** Sends (or, with no SMTP, logs) the email-verification link for a freshly registered user. */
    public void sendVerificationEmail(User user, String token) {
        String link = appBaseUrl + "/api/auth/verify-email?token=" + token;
        if (mailSender == null) {
            log.warn("SMTP not configured — verification link for {}: {}", user.getEmail(), link);
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(user.getEmail());
            msg.setSubject("Подтвердите ваш email — AI-Workflow");
            msg.setText("Здравствуйте!\n\n"
                + "Подтвердите регистрацию в AI-Workflow, перейдя по ссылке:\n"
                + link + "\n\n"
                + "Если вы не регистрировались — просто проигнорируйте это письмо.");
            mailSender.send(msg);
            log.info("Verification email sent to {}", user.getEmail());
        } catch (Exception e) {
            log.error("Failed to send verification email to {}: {}", user.getEmail(), e.getMessage());
        }
    }
}
