import type { User } from 'oidc-client-ts';
import { WebStorageStateStore } from 'oidc-client-ts';
import type { ReactNode } from 'react';
import { useEffect, useMemo } from 'react';
import { AuthProvider, useAuth } from 'react-oidc-context';

import { setAccessTokenProvider } from '../api/client';
import type { Session } from './session';
import { rolesFromAccessToken, SessionContext } from './session';

interface Props {
  authority: string;
  clientId: string;
  children: ReactNode;
}

/**
 * Keycloak login with the authorization code flow + PKCE (public client, no secret in the browser).
 * Tokens stay in sessionStorage (cleared with the tab) and are renewed silently before they expire.
 */
export function OidcSessionProvider({ authority, clientId, children }: Props) {
  const origin = window.location.origin;
  return (
    <AuthProvider
      authority={authority}
      client_id={clientId}
      redirect_uri={`${origin}/`}
      post_logout_redirect_uri={`${origin}/`}
      scope="openid profile email"
      automaticSilentRenew
      userStore={new WebStorageStateStore({ store: window.sessionStorage })}
      onSigninCallback={(user: User | void) => {
        // Drop ?code=&state= from the address bar and return to where sign-in started.
        const returnTo = typeof user?.state === 'string' && user.state.startsWith('/') ? user.state : '/';
        window.history.replaceState({}, document.title, returnTo);
      }}
    >
      <OidcBridge>{children}</OidcBridge>
    </AuthProvider>
  );
}

function OidcBridge({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const token = auth.user?.access_token;

  useEffect(() => {
    setAccessTokenProvider(() => token);
  }, [token]);

  const session = useMemo<Session>(() => {
    const profile = auth.user?.profile;
    return {
      status: auth.isLoading ? 'loading' : auth.isAuthenticated ? 'authenticated' : 'anonymous',
      user: auth.isAuthenticated
        ? {
            name: profile?.name ?? profile?.preferred_username ?? 'Traveller',
            email: profile?.email ?? '',
            roles: rolesFromAccessToken(token),
          }
        : undefined,
      signIn: (returnTo) => void auth.signinRedirect({ state: returnTo ?? window.location.pathname }),
      signOut: () => void auth.signoutRedirect(),
    };
  }, [auth, token]);

  return <SessionContext.Provider value={session}>{children}</SessionContext.Provider>;
}
