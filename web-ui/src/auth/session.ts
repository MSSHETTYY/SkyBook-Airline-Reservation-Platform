import { createContext, useContext } from 'react';

export type Role = 'CUSTOMER' | 'AGENT' | 'ADMIN';

export const ROLES: readonly Role[] = ['CUSTOMER', 'AGENT', 'ADMIN'];

export interface SessionUser {
  name: string;
  email: string;
  roles: Role[];
}

/**
 * What the app needs to know about the signed-in user, independent of how they signed in
 * (Keycloak via OIDC in real deployments, a role picker in mock mode for demos and E2E tests).
 */
export interface Session {
  status: 'loading' | 'authenticated' | 'anonymous';
  user?: SessionUser;
  signIn: (returnTo?: string) => void;
  signOut: () => void;
}

export const SessionContext = createContext<Session | null>(null);

export function useSession(): Session {
  const session = useContext(SessionContext);
  if (!session) throw new Error('useSession must be used inside a SessionProvider');
  return session;
}

export function hasAnyRole(user: SessionUser | undefined, roles: readonly Role[]): boolean {
  return !!user && roles.some((role) => user.roles.includes(role));
}

/** Staff = can service any booking. */
export const STAFF: readonly Role[] = ['AGENT', 'ADMIN'];

/**
 * Reads realm roles from a Keycloak access token. The token is only decoded for display decisions
 * (which menu items to show); every API call is still authorised by the backend, which verifies it.
 */
export function rolesFromAccessToken(token: string | undefined): Role[] {
  if (!token) return [];
  try {
    const payload = token.split('.')[1];
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
    const claims = JSON.parse(json) as { realm_access?: { roles?: unknown } };
    const roles = claims.realm_access?.roles;
    return Array.isArray(roles) ? ROLES.filter((role) => roles.includes(role)) : [];
  } catch {
    return [];
  }
}
