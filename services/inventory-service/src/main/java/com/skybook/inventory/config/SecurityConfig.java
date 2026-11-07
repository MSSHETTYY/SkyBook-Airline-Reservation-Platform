package com.skybook.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import com.skybook.common.security.SkyBookHttpSecurity;

/**
 * Flight search and seat maps are public (anonymous shoppers); everything else needs a token and is
 * further restricted with @PreAuthorize: holds and seat releases to the SERVICE role (booking-service),
 * flight management to staff.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return SkyBookHttpSecurity.apiDefaults(http)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/flights", "/api/v1/flights/*",
                                "/api/v1/flights/*/seats").permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
