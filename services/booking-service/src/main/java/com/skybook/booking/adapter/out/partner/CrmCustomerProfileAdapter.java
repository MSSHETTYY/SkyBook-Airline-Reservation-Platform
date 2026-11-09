package com.skybook.booking.adapter.out.partner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import com.skybook.booking.adapter.out.support.RemoteCall;
import com.skybook.booking.application.port.out.CustomerProfilePort;
import com.skybook.booking.domain.model.CustomerTier;

/**
 * {@link CustomerProfilePort} backed by the CRM. Graceful degradation: any failure (timeout, open circuit,
 * unknown customer, unknown tier) falls back to STANDARD, which charges the normal fees - the safe default.
 */
public class CrmCustomerProfileAdapter implements CustomerProfilePort {

    private static final Logger LOG = LoggerFactory.getLogger(CrmCustomerProfileAdapter.class);

    record CustomerProfile(String customerId, String tier) {
    }

    private final RestClient restClient;
    private final RemoteCall remote;

    public CrmCustomerProfileAdapter(RestClient restClient, RemoteCall remote) {
        this.restClient = restClient;
        this.remote = remote;
    }

    @Override
    public CustomerTier tierOf(String customerId) {
        try {
            CustomerProfile profile = remote.execute("tierOf", () -> restClient.get()
                    .uri("/crm/v1/customers/{customerId}", customerId)
                    .retrieve()
                    .onStatus(status -> status.isError(), remote::raise)
                    .body(CustomerProfile.class));
            return profile == null || profile.tier() == null ? CustomerTier.STANDARD
                    : CustomerTier.valueOf(profile.tier());
        } catch (RuntimeException ex) {
            LOG.warn("CRM lookup failed for a customer; using tier STANDARD: {}", ex.getMessage());
            return CustomerTier.STANDARD;
        }
    }
}
