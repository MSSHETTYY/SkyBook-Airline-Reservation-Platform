package com.skybook.common.error;

import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Base class for expected, client-facing failures. The message becomes the RFC 9457 {@code detail},
 * so it must be safe to show to API consumers (no personal data, no internals).
 *
 * <p>Unexpected failures should simply propagate as ordinary exceptions; they are reported as a
 * generic {@link CommonErrorCode#INTERNAL_ERROR} without leaking their message.
 */
public class SkyBookException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;
    private final transient Map<String, Object> properties;

    public SkyBookException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, Map.of(), null);
    }

    /**
     * Creates an exception.
     *
     * @param errorCode  machine-readable code; determines HTTP status and problem type
     * @param detail     client-safe explanation of this occurrence
     * @param properties extension members added to the problem body, e.g. {@code flightId}
     * @param cause      underlying cause, logged but never exposed to clients
     */
    public SkyBookException(ErrorCode errorCode, String detail, Map<String, ?> properties, Throwable cause) {
        super(detail, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(properties)));
    }

    public final ErrorCode getErrorCode() {
        return errorCode;
    }

    /** Extension members for the problem body, in insertion order. */
    public final Map<String, Object> getProperties() {
        return properties;
    }
}
