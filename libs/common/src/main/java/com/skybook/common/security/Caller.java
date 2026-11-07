package com.skybook.common.security;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The authenticated principal of the current request, reduced to what business code needs.
 *
 * @param subject stable user id ({@code sub} claim); used as the owner id of customer data
 * @param email   e-mail claim, if present
 * @param roles   realm roles without the {@code ROLE_} prefix
 */
public record Caller(String subject, String email, Set<String> roles) {

    public static Caller current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            throw new AuthenticationCredentialsNotFoundException("No JWT-authenticated caller");
        }
        Set<String> roles = jwtAuth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .collect(Collectors.toUnmodifiableSet());
        return new Caller(jwtAuth.getToken().getSubject(), jwtAuth.getToken().getClaimAsString("email"), roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** Agents and admins may act on any customer's data. */
    public boolean isStaff() {
        return hasRole(Roles.AGENT) || hasRole(Roles.ADMIN);
    }
}
