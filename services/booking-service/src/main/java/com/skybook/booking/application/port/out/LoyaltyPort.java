package com.skybook.booking.application.port.out;

import com.skybook.common.money.Money;

/** Driven port: loyalty programme. Non-critical: implementations never throw, failures are logged. */
public interface LoyaltyPort {

    void accrueMiles(String customerId, String bookingReference, Money amountPaid);
}
