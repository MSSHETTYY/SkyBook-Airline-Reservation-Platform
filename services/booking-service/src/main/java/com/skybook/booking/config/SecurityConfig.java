package com.skybook.booking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import com.skybook.common.security.SkyBookHttpSecurity;

/** Every booking endpoint requires a token; fine-grained rules are @PreAuthorize on the controllers. */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return SkyBookHttpSecurity.apiDefaults(http)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .build();
    }
}
