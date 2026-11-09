package com.skybook.booking.adapter.out.partner;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import com.skybook.booking.adapter.out.support.RemoteCall;
import com.skybook.booking.application.port.out.LoyaltyPort;
import com.skybook.common.money.Money;

/**
 * {@link LoyaltyPort} for the loyalty programme. Non-critical: a confirmed booking must never fail because
 * miles could not be credited, so failures are logged (for a later reconciliation job) and swallowed.
 * The idempotency key per booking prevents double crediting when the call is repeated.
 */
public class LoyaltyRestAdapter implements LoyaltyPort {

    private static final Logger LOG = LoggerFactory.getLogger(LoyaltyRestAdapter.class);

    record AccrualRequest(String customerId, String bookingReference, BigDecimal amount, String currency) {
    }

    private final RestClient restClient;
    private final RemoteCall remote;

    public LoyaltyRestAdapter(RestClient restClient, RemoteCall remote) {
        this.restClient = restClient;
        this.remote = remote;
    }

    @Override
    public void accrueMiles(String customerId, String bookingReference, Money amountPaid) {
        try {
            remote.execute("accrueMiles", () -> restClient.post()
                    .uri("/loyalty/v1/accruals")
                    .header("Idempotency-Key", "accrual-" + bookingReference)
                    .body(new AccrualRequest(customerId, bookingReference, amountPaid.amount(),
                            amountPaid.currencyCode()))
                    .retrieve()
                    .onStatus(status -> status.isError(), remote::raise)
                    .toBodilessEntity());
        } catch (RuntimeException ex) {
            LOG.warn("Loyalty accrual for booking {} failed; to be reconciled later: {}", bookingReference,
                    ex.getMessage());
        }
    }
}
