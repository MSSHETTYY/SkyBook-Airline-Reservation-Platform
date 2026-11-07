package com.skybook.payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Payment settings ({@code skybook.payment.*}). Secrets come from Vault in deployed environments.
 *
 * @param provider          {@code stripe} (test-mode keys) or {@code fake} (no Stripe account needed)
 * @param stripeSecretKey   Stripe secret key ({@code sk_test_...}); required for {@code stripe}
 * @param stripeApiBase     override of the Stripe API base URL (tests use WireMock); blank for api.stripe.com
 * @param webhookSecret     webhook endpoint signing secret ({@code whsec_...})
 * @param webhookTolerance  maximum age of a webhook signature (replay protection)
 */
@ConfigurationProperties("skybook.payment")
public record PaymentProperties(String provider, String stripeSecretKey, String stripeApiBase, String webhookSecret,
                                Duration webhookTolerance) {

    public PaymentProperties {
        provider = provider == null || provider.isBlank() ? "fake" : provider;
        webhookTolerance = webhookTolerance == null ? Duration.ofMinutes(5) : webhookTolerance;
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException("skybook.payment.webhook-secret must be set");
        }
        if ("stripe".equals(provider) && (stripeSecretKey == null || stripeSecretKey.isBlank())) {
            throw new IllegalArgumentException("skybook.payment.stripe-secret-key must be set for the Stripe provider");
        }
    }

    public boolean fake() {
        return "fake".equals(provider);
    }
}
