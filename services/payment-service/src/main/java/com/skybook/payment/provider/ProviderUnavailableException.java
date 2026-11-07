package com.skybook.payment.provider;

import java.io.Serial;

/** Transient provider failure: retried with backoff and counted by the circuit breaker. */
public class ProviderUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
