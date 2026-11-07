package com.skybook.gateway;

import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import com.skybook.common.security.KeycloakRealmRoleConverter;

/**
 * Edge security: validate JWTs early so unauthenticated traffic never reaches the services, apply
 * CORS for the SPA, and add standard security headers. The services validate tokens again and apply
 * fine-grained RBAC (defence in depth, ADR-0008); the gateway only distinguishes public from
 * authenticated routes.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain edgeSecurity(ServerHttpSecurity http) {
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> { })
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/flights", "/api/v1/flights/*",
                                "/api/v1/flights/*/seats").permitAll()
                        // Stripe calls the webhook; it is authenticated by its signature, not a JWT.
                        .pathMatchers(HttpMethod.POST, "/api/v1/payments/webhooks/stripe").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .pathMatchers("/fallback/**").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                new ReactiveJwtAuthenticationConverterAdapter(jwtConverter)))
                        .authenticationEntryPoint((exchange, ex) -> ProblemResponses.write(exchange,
                                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid bearer token is required")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((exchange, ex) -> ProblemResponses.write(exchange,
                                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid bearer token is required"))
                        .accessDeniedHandler((exchange, ex) -> ProblemResponses.write(exchange,
                                HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You are not allowed to perform this action")))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        cors.setExposedHeaders(List.of("Location", "X-Correlation-Id", "Retry-After", "X-RateLimit-Remaining"));
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
