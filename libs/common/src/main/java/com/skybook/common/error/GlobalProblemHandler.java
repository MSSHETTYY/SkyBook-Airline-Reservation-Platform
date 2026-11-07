package com.skybook.common.error;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.skybook.common.web.CorrelationId;
import com.skybook.common.web.CorrelationIdFilter;

/**
 * Translates exceptions into RFC 9457 ({@code application/problem+json}) responses.
 *
 * <p>Every problem body carries the standard members plus three extensions that clients and
 * support staff rely on: {@code code} (stable error identifier), {@code correlationId} and
 * {@code timestamp}. Spring MVC's own exceptions (unsupported media type, unreadable body, ...)
 * are handled by {@link ResponseEntityExceptionHandler} and enriched the same way.
 */
@RestControllerAdvice
public class GlobalProblemHandler extends ResponseEntityExceptionHandler {

    static final String CODE = "code";
    static final String CORRELATION_ID = "correlationId";
    static final String TIMESTAMP = "timestamp";
    static final String ERRORS = "errors";

    private static final Logger LOG = LoggerFactory.getLogger(GlobalProblemHandler.class);

    private final Clock clock;

    public GlobalProblemHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(SkyBookException.class)
    public ResponseEntity<ProblemDetail> handleSkyBookException(SkyBookException ex, HttpServletRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        if (errorCode.httpStatus().is5xxServerError()) {
            LOG.error("Request failed with {}", errorCode.code(), ex);
        } else {
            LOG.debug("Request rejected with {}: {}", errorCode.code(), ex.getMessage());
        }
        ProblemDetail problem = problemFor(errorCode, ex.getMessage());
        ex.getProperties().forEach(problem::setProperty);
        return respond(problem, request);
    }

    /** Bean Validation failures on {@code @Validated} method parameters (path variables, query params). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(GlobalProblemHandler::toFieldViolation)
                .toList();
        return respond(validationProblem(violations), request);
    }

    /**
     * A concurrent update won the optimistic-locking race. The client should reload and retry; this is
     * an expected outcome under contention, not a server fault.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                              HttpServletRequest request) {
        LOG.info("Optimistic locking conflict: {}", ex.getMessage());
        return respond(problemFor(CommonErrorCode.CONCURRENT_MODIFICATION,
                "The resource was changed by another request. Reload it and try again."), request);
    }

    /**
     * Last-resort handler. The exception message may contain internals or personal data, so it is
     * logged (correlated via MDC) but never returned to the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        LOG.error("Unhandled exception", ex);
        return respond(problemFor(CommonErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Quote the correlationId when contacting support."), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getAllErrors().stream()
                .map(error -> new FieldViolation(
                        error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName(),
                        error.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(ex, validationProblem(violations), headers, status, request);
    }

    /**
     * Single choke point for every response built by {@link ResponseEntityExceptionHandler}. Enriching
     * here rather than in {@code handleExceptionInternal} also covers handlers that pass a {@code null}
     * body and let the framework derive the {@link ProblemDetail} afterwards.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && request instanceof NativeWebRequest nativeRequest) {
            HttpServletRequest servletRequest = nativeRequest.getNativeRequest(HttpServletRequest.class);
            if (servletRequest != null) {
                enrich(problem, servletRequest);
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<ProblemDetail> respond(ProblemDetail problem, HttpServletRequest request) {
        enrich(problem, request);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private void enrich(ProblemDetail problem, HttpServletRequest request) {
        if (problem.getInstance() == null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        if (problem.getProperties() == null || !problem.getProperties().containsKey(CODE)) {
            problem.setProperty(CODE, codeForFrameworkProblem(problem.getStatus()));
        }
        problem.setProperty(CORRELATION_ID, correlationId(request));
        problem.setProperty(TIMESTAMP, Instant.now(clock).toString());
    }

    private static ProblemDetail problemFor(ErrorCode errorCode, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.httpStatus(), detail);
        problem.setType(errorCode.type());
        problem.setTitle(errorCode.title());
        problem.setProperty(CODE, errorCode.code());
        return problem;
    }

    private static ProblemDetail validationProblem(List<FieldViolation> violations) {
        List<FieldViolation> sorted = violations.stream().sorted(FieldViolation.ORDER).toList();
        ProblemDetail problem = problemFor(CommonErrorCode.VALIDATION_FAILED,
                "%d field(s) failed validation".formatted(sorted.size()));
        // Rejected values are deliberately omitted: they may contain personal or payment data.
        problem.setProperty(ERRORS, sorted.stream()
                .map(v -> Map.of("field", v.field(), "message", v.message()))
                .toList());
        return problem;
    }

    /** Framework-generated problems carry no SkyBook code; map them onto the closest common one. */
    private static String codeForFrameworkProblem(int status) {
        return switch (status) {
            case 400 -> CommonErrorCode.MALFORMED_REQUEST.code();
            case 404 -> CommonErrorCode.RESOURCE_NOT_FOUND.code();
            case 409 -> CommonErrorCode.CONFLICT.code();
            default -> HttpStatusCode.valueOf(status).is5xxServerError()
                    ? CommonErrorCode.INTERNAL_ERROR.code()
                    : "HTTP_" + status;
        };
    }

    private static String correlationId(HttpServletRequest request) {
        if (request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) instanceof String id) {
            return id;
        }
        return CorrelationId.current().orElse(null);
    }

    private static FieldViolation toFieldViolation(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        // "search.arg0" / "search.origin" -> drop the method name for a client-friendly path.
        int firstDot = path.indexOf('.');
        return new FieldViolation(firstDot >= 0 ? path.substring(firstDot + 1) : path, violation.getMessage());
    }

    private record FieldViolation(String field, String message) {

        private static final Comparator<FieldViolation> ORDER =
                Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message);

        FieldViolation {
            message = message == null ? "is invalid" : message;
        }
    }
}
