package com.skybook.booking.adapter.out.inventory;

import java.io.Serial;

/** A transient inventory failure (I/O, timeout, 5xx, 429): retried, and counted by the circuit breaker. */
public class InventoryUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InventoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
