package com.skybook.notification.domain;

/** Masks e-mail addresses for audit logs: keeps the first character and the domain. */
public final class EmailMasking {

    private EmailMasking() {
    }

    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
