package com.skybook.payment.provider;

import java.util.Locale;
import java.util.Map;

import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.skybook.common.money.Money;
import com.skybook.payment.domain.RefundStatus;

/**
 * Stripe adapter (test mode keys). Amounts are sent in minor units; every call uses Stripe idempotency
 * keys, so retries by our Resilience4j layer are safe. Card details are never seen here: the browser
 * confirms the PaymentIntent with Stripe directly (Stripe Elements), which keeps SkyBook out of
 * cardholder-data scope (PCI-DSS SAQ A).
 */
public class StripePaymentProvider implements PaymentProvider {

    private final StripeClient stripe;

    public StripePaymentProvider(StripeClient stripe) {
        this.stripe = stripe;
    }

    @Override
    public ProviderIntent createIntent(Money amount, Map<String, String> metadata, String idempotencyKey) {
        PaymentIntentCreateParams.Builder params = PaymentIntentCreateParams.builder()
                .setAmount(amount.minorUnits())
                .setCurrency(amount.currencyCode().toLowerCase(Locale.ROOT))
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true).build());
        metadata.forEach(params::putMetadata);
        PaymentIntent intent = call(() -> stripe.paymentIntents().create(params.build(), options(idempotencyKey)));
        return new ProviderIntent(intent.getId(), intent.getClientSecret());
    }

    @Override
    public void cancelIntent(String providerPaymentId, String idempotencyKey) {
        call(() -> stripe.paymentIntents().cancel(providerPaymentId, options(idempotencyKey)));
    }

    @Override
    public ProviderRefund refund(String providerPaymentId, Money amount, String idempotencyKey) {
        Refund refund = call(() -> stripe.refunds().create(RefundCreateParams.builder()
                .setPaymentIntent(providerPaymentId)
                .setAmount(amount.minorUnits())
                .build(), options(idempotencyKey)));
        return new ProviderRefund(refund.getId(), toStatus(refund.getStatus()));
    }

    static RefundStatus toStatus(String stripeStatus) {
        return switch (stripeStatus == null ? "" : stripeStatus) {
            case "succeeded" -> RefundStatus.SUCCEEDED;
            case "failed", "canceled" -> RefundStatus.FAILED;
            default -> RefundStatus.PENDING;
        };
    }

    private static RequestOptions options(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    @FunctionalInterface
    private interface StripeCall<T> {
        T execute() throws StripeException;
    }

    private static <T> T call(StripeCall<T> call) {
        try {
            return call.execute();
        } catch (ApiConnectionException | RateLimitException | ApiException ex) {
            throw new ProviderUnavailableException("Stripe unavailable: " + ex.getMessage(), ex);
        } catch (StripeException ex) {
            throw new ProviderRejectedException("Stripe rejected the request: " + ex.getMessage(), ex);
        }
    }
}
