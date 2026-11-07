package com.skybook.common.error;

import org.springframework.http.HttpStatus;

/** Error codes that are meaningful to every SkyBook service. */
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    CONFLICT(HttpStatus.CONFLICT, "Request conflicts with the current state of the resource"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "The resource was modified concurrently; reload and retry"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency key reused for a different request"),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT, "Business rule violated"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "A required service is temporarily unavailable");

    private final HttpStatus httpStatus;
    private final String title;

    CommonErrorCode(HttpStatus httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int status() {
        return httpStatus.value();
    }

    @Override
    public String title() {
        return title;
    }
}
