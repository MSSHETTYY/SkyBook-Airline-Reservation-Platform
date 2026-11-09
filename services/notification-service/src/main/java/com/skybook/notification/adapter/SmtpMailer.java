package com.skybook.notification.adapter;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.skybook.notification.port.Mailer;

/** SMTP delivery (Mailpit locally, a real relay in production). */
@Component
class SmtpMailer implements Mailer {

    private final JavaMailSender mailSender;
    private final String from;

    SmtpMailer(JavaMailSender mailSender, @Value("${skybook.notification.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MessagingException ex) {
            throw new MailSendException("Could not build e-mail", ex);
        }
    }
}
