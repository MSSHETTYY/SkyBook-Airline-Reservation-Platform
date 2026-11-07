package com.skybook.payment.provider;

import java.util.Map;
import java.util.function.Supplier;

import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;
import com.skybook.payment.domain.PaymentErrorCode;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

/**
 * Decorates a {@link PaymentProvider} with a bulkhead (bounded concurrent provider calls, so a slow
 * provider cannot exhaust the request threads), a circuit breaker (fail fast while the provider is
 * down) and retries with backoff for transient failures. Retrying is safe because every provider call
 * carries an idempotency key. Failures are translated into client-facing problems here.
 */
public class ResilientPaymentProvider implements PaymentProvider {

    private final PaymentProvider delegate;
    private final Bulkhead bulkhead;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public ResilientPaymentProvider(PaymentProvider delegate, Bulkhead bulkhead, CircuitBreaker circuitBreaker,
                                    Retry retry) {
        this.delegate = delegate;
        this.bulkhead = bulkhead;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    @Override
    public ProviderIntent createIntent(Money amount, Map<String, String> metadata, String idempotencyKey) {
        return guarded(() -> delegate.createIntent(amount, metadata, idempotencyKey));
    }

    @Override
    public void cancelIntent(String providerPaymentId, String idempotencyKey) {
        guarded(() -> {
            delegate.cancelIntent(providerPaymentId, idempotencyKey);
            return null;
        });
    }

    @Override
    public ProviderRefund refund(String providerPaymentId, Money amount, String idempotencyKey) {
        return guarded(() -> delegate.refund(providerPaymentId, amount, idempotencyKey));
    }

    private <T> T guarded(Supplier<T> call) {
        Supplier<T> decorated = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, Bulkhead.decorateSupplier(bulkhead, call)));
        try {
            return decorated.get();
        } catch (ProviderRejectedException ex) {
            throw new SkyBookException(PaymentErrorCode.PROVIDER_REJECTED, "The payment provider rejected the request",
                    Map.of(), ex);
        } catch (ProviderUnavailableException | CallNotPermittedException | BulkheadFullException ex) {
            throw new SkyBookException(CommonErrorCode.SERVICE_UNAVAILABLE,
                    "Payments are temporarily unavailable. Please try again shortly.", Map.of(), ex);
        }
    }
}
