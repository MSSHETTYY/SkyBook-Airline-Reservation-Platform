package com.skybook.common.error;

import java.net.URI;
import java.util.Locale;

import org.springframework.http.HttpStatus;

/**
 * A stable, machine-readable error identifier that maps to an RFC 9457 problem type.
 *
 * <p>Each service defines its own codes (typically as an enum implementing this interface), so the
 * set of error codes is open for extension without changing this library. Clients should branch on
 * {@link #code()}, never on the human-readable title or detail.
 */
public interface ErrorCode {

    /** Prefix of every SkyBook problem type URI. URNs avoid implying a resolvable documentation host. */
    String PROBLEM_TYPE_PREFIX = "urn:skybook:problem:";

    /** Upper snake case identifier, e.g. {@code SEAT_UNAVAILABLE}. Part of the public API contract. */
    String code();

    /**
     * HTTP status code the problem is reported with. A plain {@code int} so that implementations in
     * framework-free domain code do not need to import Spring.
     */
    int status();

    default HttpStatus httpStatus() {
        return HttpStatus.valueOf(status());
    }

    /** Short, human-readable summary that does not change between occurrences. */
    String title();

    /** RFC 9457 {@code type} member, e.g. {@code urn:skybook:problem:seat-unavailable}. */
    default URI type() {
        return URI.create(PROBLEM_TYPE_PREFIX + code().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
