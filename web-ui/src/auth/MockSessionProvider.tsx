import type { ReactNode } from 'react';
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';

import { setAccessTokenProvider } from '../api/client';
import type { Role, Session, SessionUser } from './session';
import { SessionContext } from './session';

const STORAGE_KEY = 'skybook.mock-session';

const MockSignInContext = createContext<((user: SessionUser | undefined) => void) | null>(null);

/** The role picker's way to sign in; only available in mock mode. */
export function useMockSignIn(): (user: SessionUser | undefined) => void {
  const signInAs = useContext(MockSignInContext);
  if (!signInAs) throw new Error('useMockSignIn requires the MockSessionProvider (VITE_AUTH_MODE=mock)');
  return signInAs;
}

export const MOCK_USERS: Record<Role, SessionUser> = {
  CUSTOMER: { name: 'Casey Customer', email: 'customer@skybook.test', roles: ['CUSTOMER'] },
  AGENT: { name: 'Avery Agent', email: 'agent@skybook.test', roles: ['AGENT'] },
  ADMIN: { name: 'Alex Admin', email: 'admin@skybook.test', roles: ['ADMIN', 'AGENT'] },
};

/** The bearer the MSW mock API understands: `mock.<role>`. */
export function mockToken(user: SessionUser): string {
  return `mock.${user.roles[0]}`;
}

function load(): SessionUser | undefined {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as SessionUser) : undefined;
  } catch {
    return undefined;
  }
}

interface Props {
  children: ReactNode;
  /** Where signIn() sends the user: the role picker page. */
  navigateToLogin: (returnTo: string) => void;
  initialUser?: SessionUser;
}

/** Demo / E2E sign-in without Keycloak. Never enabled in a production build (VITE_AUTH_MODE=oidc). */
export function MockSessionProvider({ children, navigateToLogin, initialUser }: Props) {
  const [user, setUser] = useState<SessionUser | undefined>(() => initialUser ?? load());

  useEffect(() => {
    setAccessTokenProvider(() => (user ? mockToken(user) : undefined));
  }, [user]);

  const signInAs = useCallback((next: SessionUser | undefined) => {
    setUser(next);
    try {
      if (next) window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
      else window.sessionStorage.removeItem(STORAGE_KEY);
    } catch {
      // Storage unavailable (private mode): the session lasts until reload, which is fine for a demo.
    }
  }, []);

  const session = useMemo<Session>(
    () => ({
      status: user ? 'authenticated' : 'anonymous',
      user,
      signIn: (returnTo) => navigateToLogin(returnTo ?? window.location.pathname),
      signOut: () => signInAs(undefined),
    }),
    [user, navigateToLogin, signInAs],
  );

  return (
    <MockSignInContext.Provider value={signInAs}>
      <SessionContext.Provider value={session}>{children}</SessionContext.Provider>
    </MockSignInContext.Provider>
  );
}
