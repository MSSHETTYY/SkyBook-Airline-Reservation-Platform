package com.skybook.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import com.skybook.common.security.SkyBookHttpSecurity;

/**
 * The Stripe webhook is the only unauthenticated endpoint; it is authenticated by its HMAC signature
 * instead. Everything else requires a JWT and is restricted with @PreAuthorize.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return SkyBookHttpSecurity.apiDefaults(http)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/webhooks/stripe").permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
