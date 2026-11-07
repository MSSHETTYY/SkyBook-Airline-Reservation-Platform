package com.skybook.payment.provider;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.skybook.common.money.Money;
import com.skybook.payment.domain.RefundStatus;

/**
 * Stand-in for Stripe when no Stripe test keys are configured (local demos, E2E tests, CI). It honours
 * idempotency keys like Stripe does, and payment outcomes are delivered through the same signed-webhook
 * path as real Stripe events ({@code PaymentSimulationService}). Never active in production
 * ({@code skybook.payment.provider=stripe}).
 */
public class FakePaymentProvider implements PaymentProvider {

    private final Map<String, ProviderIntent> intentsByKey = new ConcurrentHashMap<>();
    private final Map<String, ProviderRefund> refundsByKey = new ConcurrentHashMap<>();

    @Override
    public ProviderIntent createIntent(Money amount, Map<String, String> metadata, String idempotencyKey) {
        return intentsByKey.computeIfAbsent(idempotencyKey, key -> {
            String id = "pi_fake_" + UUID.randomUUID().toString().replace("-", "");
            return new ProviderIntent(id, id + "_secret_" + UUID.randomUUID().toString().replace("-", ""));
        });
    }

    @Override
    public void cancelIntent(String providerPaymentId, String idempotencyKey) {
        // Nothing to cancel remotely.
    }

    @Override
    public ProviderRefund refund(String providerPaymentId, Money amount, String idempotencyKey) {
        return refundsByKey.computeIfAbsent(idempotencyKey,
                key -> new ProviderRefund("re_fake_" + UUID.randomUUID().toString().replace("-", ""),
                        RefundStatus.SUCCEEDED));
    }
}
