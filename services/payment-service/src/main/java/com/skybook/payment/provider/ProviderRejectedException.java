package com.skybook.payment.provider;

import java.io.Serial;

/** Permanent provider failure (invalid request, authentication): never retried. */
public class ProviderRejectedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ProviderRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
