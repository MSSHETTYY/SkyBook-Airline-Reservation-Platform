package com.skybook.common.web;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/**
 * Correlation-ID conventions shared by HTTP filters, message listeners and the outbox.
 *
 * <p>The correlation ID ties together every log line, event and downstream call caused by one
 * business interaction (e.g. a booking saga), and is distinct from an OpenTelemetry trace ID, which
 * is scoped to a single trace and may be sampled away.
 */
public final class CorrelationId {

    /** HTTP header and messaging header carrying the correlation ID. */
    public static final String HEADER = "X-Correlation-Id";

    /** SLF4J MDC key; referenced by the JSON log layout. */
    public static final String MDC_KEY = "correlationId";

    /** Upper bound for client-supplied values, to keep log lines and headers bounded. */
    static final int MAX_LENGTH = 64;

    /** Rejects anything that could inject log lines, headers or markup (CR/LF, quotes, spaces...). */
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._:-]{1," + MAX_LENGTH + "}");

    private CorrelationId() {
    }

    /** Creates a new random correlation ID. */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /** Whether a client-supplied value may be trusted and propagated verbatim. */
    public static boolean isValid(String candidate) {
        return candidate != null && SAFE_VALUE.matcher(candidate).matches();
    }

    /** Returns the candidate if it is valid, otherwise a newly generated ID. */
    public static String acceptOrGenerate(String candidate) {
        return isValid(candidate) ? candidate : generate();
    }

    /** Correlation ID bound to the current thread, if any. */
    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }
}
