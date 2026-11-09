package com.skybook.booking.application.port.out;

import com.skybook.booking.domain.model.CustomerTier;

/** Driven port: CRM. Implementations fall back to {@link CustomerTier#STANDARD} when the CRM is unavailable. */
public interface CustomerProfilePort {

    CustomerTier tierOf(String customerId);
}
