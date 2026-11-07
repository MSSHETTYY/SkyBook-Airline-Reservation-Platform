package com.skybook.payment.provider;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stripe's webhook signature scheme: header {@code t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}.
 *
 * <p>Verification uses Stripe's own {@code Webhook.Signature.verifyHeader} (constant-time comparison,
 * replay tolerance). Signing is needed only to produce events for the fake provider and for tests, so the
 * fake path goes through exactly the same verification as real Stripe traffic.
 */
public final class StripeSignatures {

    private StripeSignatures() {
    }

    public static String sign(String payload, String secret, Instant timestamp) {
        long seconds = timestamp.getEpochSecond();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((seconds + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "t=" + seconds + ",v1=" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("Cannot sign webhook payload", ex);
        }
    }
}
