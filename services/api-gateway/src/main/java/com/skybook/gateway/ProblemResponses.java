package com.skybook.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;

import com.skybook.common.web.CorrelationId;

import reactor.core.publisher.Mono;

/** Writes RFC 9457 problem documents from the reactive edge, matching the services' error format. */
final class ProblemResponses {

    private ProblemResponses() {
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        String correlationId = exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER);
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        // Only constants and the validated correlation ID are interpolated, so no escaping is needed.
        String body = """
                {"type":"urn:skybook:problem:%s","title":"%s","status":%d,"detail":"%s","code":"%s",\
                "instance":"%s","correlationId":%s,"timestamp":"%s"}"""
                .formatted(code.toLowerCase(Locale.ROOT).replace('_', '-'), status.getReasonPhrase(), status.value(),
                        detail, code, exchange.getRequest().getPath().value().replace("\"", ""),
                        CorrelationId.isValid(correlationId) ? "\"" + correlationId + "\"" : "null", Instant.now());
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
