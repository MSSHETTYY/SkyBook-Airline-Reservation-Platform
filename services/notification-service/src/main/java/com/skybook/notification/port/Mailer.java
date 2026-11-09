package com.skybook.notification.port;

/** Sends an HTML e-mail; throws on delivery failure so the caller can retry. */
public interface Mailer {

    void send(String to, String subject, String html);
}
