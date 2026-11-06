package com.skybook.common.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds a correlation ID to every inbound request.
 *
 * <p>A valid {@value CorrelationId#HEADER} supplied by the caller (normally the API gateway) is
 * reused; a missing or malformed one is replaced with a fresh ID, so untrusted input never reaches
 * the logs. The ID is echoed in the response so clients can quote it in support requests.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Request attribute holding the effective ID, for code that runs after the MDC is cleared. */
    public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Async and error dispatches reuse the ID chosen on the original dispatch.
        String correlationId = request.getAttribute(REQUEST_ATTRIBUTE) instanceof String existing
                ? existing
                : CorrelationId.acceptOrGenerate(request.getHeader(CorrelationId.HEADER));
        request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationId.MDC_KEY, correlationId)) {
            chain.doFilter(request, response);
        }
    }

    /** Also cover async dispatches and error dispatches so every log line is correlated. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
