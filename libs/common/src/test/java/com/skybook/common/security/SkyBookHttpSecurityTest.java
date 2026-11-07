package com.skybook.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.skybook.common.test.TestJwtIssuer;
import com.skybook.common.web.CorrelationIdFilter;

@SpringJUnitWebConfig(SkyBookHttpSecurityTest.Config.class)
class SkyBookHttpSecurityTest {

    private static final TestJwtIssuer ISSUER = new TestJwtIssuer();

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class Config {

        @Bean
        JwtDecoder jwtDecoder() {
            return ISSUER.decoder();
        }

        @Bean
        SecurityFilterChain api(HttpSecurity http) throws Exception {
            return SkyBookHttpSecurity.apiDefaults(http)
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/public").permitAll()
                            .anyRequest().authenticated())
                    .build();
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @RestController
    static class TestController {

        @GetMapping("/public")
        String open() {
            return "open";
        }

        @GetMapping("/me")
        Caller me() {
            return Caller.current();
        }

        @GetMapping("/staff-only")
        @PreAuthorize(Roles.IS_STAFF)
        String staff() {
            return "staff";
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(),
                        context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void publicEndpointNeedsNoToken() throws Exception {
        mockMvc.perform(get("/public")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    }

    @Test
    void missingTokenIsA401Problem() throws Exception {
        mockMvc.perform(get("/me").header("X-Correlation-Id", "corr-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").value("corr-401"));
    }

    @Test
    void invalidTokenIsA401Problem() throws Exception {
        String foreignToken = new TestJwtIssuer().token("mallory", Roles.ADMIN);
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + foreignToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void validTokenExposesCallerWithRealmRoles() throws Exception {
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + ISSUER.token("alice", Roles.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@example.test"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }

    @Test
    void methodSecurityDeniesWithA403Problem() throws Exception {
        mockMvc.perform(get("/staff-only").header("Authorization", "Bearer " + ISSUER.token("alice", Roles.CUSTOMER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/staff-only").header("Authorization", "Bearer " + ISSUER.token("bob", Roles.AGENT)))
                .andExpect(status().isOk());
    }

    @Test
    void roleConverterToleratesMissingOrMalformedClaims() {
        KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();
        assertThat(converter.convert(jwt(Map.of("sub", "x")))).isEmpty();
        assertThat(converter.convert(jwt(Map.of("realm_access", "nope")))).isEmpty();
        assertThat(converter.convert(jwt(Map.of("realm_access", Map.of("roles", "nope"))))).isEmpty();
        assertThat(converter.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("AGENT", 7))))))
                .extracting(Object::toString).containsExactly("ROLE_AGENT");
    }

    @Test
    void callerRequiresJwtAuthentication() {
        assertThatThrownBy(Caller::current).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        Caller caller = new Caller("s", null, Set.of(Roles.ADMIN));
        assertThat(caller.isStaff()).isTrue();
        assertThat(new Caller("s", null, Set.of(Roles.CUSTOMER)).isStaff()).isFalse();
        assertThat(CorrelationIdFilter.REQUEST_ATTRIBUTE).isNotBlank();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    }
}
