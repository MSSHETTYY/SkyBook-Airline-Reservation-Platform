package com.skybook.common.security;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import com.skybook.common.web.CorrelationIdFilter;

/**
 * Security baseline shared by every SkyBook API: stateless bearer-token (JWT) authentication, Keycloak
 * realm roles, and RFC 9457 problem responses for 401/403. Services add their own authorisation rules
 * on top and must finish with {@code anyRequest()}.
 *
 * <p>CSRF protection is disabled deliberately: the APIs accept only bearer tokens in the
 * {@code Authorization} header (never cookies), so a cross-site request cannot carry credentials.
 */
public final class SkyBookHttpSecurity {

    private SkyBookHttpSecurity() {
    }

    public static HttpSecurity apiDefaults(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint((request, response, ex) -> writeProblem(request, response,
                                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid bearer token is required")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) -> writeProblem(request, response,
                                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid bearer token is required"))
                        .accessDeniedHandler((request, response, ex) -> writeProblem(request, response,
                                HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You are not allowed to perform this action")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/error").permitAll());
    }

    /**
     * Writes a minimal problem document. Only constant strings and the (validated, safe) correlation ID
     * are interpolated, so no JSON escaping is needed.
     */
    static void writeProblem(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                             String code, String detail) throws IOException {
        Object correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.getWriter().write("""
                {"type":"urn:skybook:problem:%s","title":"%s","status":%d,"detail":"%s","code":"%s",\
                "correlationId":%s,"timestamp":"%s"}"""
                .formatted(code.toLowerCase(Locale.ROOT).replace('_', '-'), status.getReasonPhrase(),
                        status.value(), detail, code,
                        correlationId instanceof String id ? "\"" + id + "\"" : "null", Instant.now()));
    }
}
