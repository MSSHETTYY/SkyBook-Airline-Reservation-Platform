package com.skybook.common.security;

/**
 * SkyBook realm roles (Keycloak realm roles, exposed to Spring Security as {@code ROLE_<name>}).
 *
 * <ul>
 *   <li>{@link #CUSTOMER} - books and manages their own bookings.</li>
 *   <li>{@link #AGENT} - services any booking, views inventory.</li>
 *   <li>{@link #ADMIN} - everything an agent can do plus flight management and manual refunds.</li>
 *   <li>{@link #SERVICE} - machine identity for service-to-service calls (OAuth2 client credentials).</li>
 * </ul>
 */
public final class Roles {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String AGENT = "AGENT";
    public static final String ADMIN = "ADMIN";
    public static final String SERVICE = "SERVICE";

    /** SpEL snippets for {@code @PreAuthorize}, kept here so role names are never mistyped. */
    public static final String IS_CUSTOMER = "hasRole('CUSTOMER')";
    public static final String IS_STAFF = "hasAnyRole('AGENT', 'ADMIN')";
    public static final String IS_ADMIN = "hasRole('ADMIN')";
    public static final String IS_SERVICE = "hasRole('SERVICE')";

    private Roles() {
    }
}
