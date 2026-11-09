package com.skybook.booking.application.port.in;

import com.skybook.booking.domain.model.Booking;
import com.skybook.common.money.Money;

/** Driving port: goodwill or correction refunds issued by an admin. */
public interface ManualRefundUseCase {

    Booking refund(String reference, Money amount, String note, String idempotencyKey);
}
