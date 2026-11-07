package com.skybook.common.security;

import java.time.Clock;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.skybook.common.web.CorrelationIdFilter;

/**
 * Turns method-security failures ({@code @PreAuthorize}) into 401/403 problems.
 *
 * <p>Those exceptions are thrown inside the controller, so without this advice the catch-all handler of
 * {@link com.skybook.common.error.GlobalProblemHandler} would report them as 500. It is ordered first so
 * it wins over that catch-all; filter-level failures are still handled by {@link SkyBookHttpSecurity}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityProblemHandler {

    private final Clock clock;

    public SecurityProblemHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You are not allowed to perform this action", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> unauthenticated(AuthenticationException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid bearer token is required", request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail,
                                                  HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:skybook:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE));
        problem.setProperty("timestamp", Instant.now(clock).toString());
        return ResponseEntity.status(status).body(problem);
    }
}
