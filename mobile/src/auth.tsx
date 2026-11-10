import {
  exchangeCodeAsync,
  makeRedirectUri,
  refreshAsync,
  useAuthRequest,
  useAutoDiscovery,
} from 'expo-auth-session';
import * as SecureStore from 'expo-secure-store';
import * as WebBrowser from 'expo-web-browser';
import type { ReactNode } from 'react';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';

import { setTokenProvider } from './api';
import { config } from './config';

WebBrowser.maybeCompleteAuthSession();

const STORAGE_KEY = 'skybook.tokens';
/** Refresh a little before expiry so a request never leaves with an expired token. */
const EXPIRY_MARGIN_MS = 30_000;

export interface StoredTokens {
  accessToken: string;
  refreshToken?: string;
  expiresAt: number;
}

export interface AuthState {
  ready: boolean;
  signedIn: boolean;
  signIn: () => void;
  signOut: () => Promise<void>;
}

export const AuthContext = createContext<AuthState | null>(null);

export function useAuth(): AuthState {
  const auth = useContext(AuthContext);
  if (!auth) throw new Error('useAuth must be used inside AuthProvider');
  return auth;
}

export function toStored(result: { accessToken: string; refreshToken?: string; expiresIn?: number },
  now: number = Date.now()): StoredTokens {
  return {
    accessToken: result.accessToken,
    refreshToken: result.refreshToken,
    expiresAt: now + (result.expiresIn ?? 300) * 1000,
  };
}

/**
 * Keycloak sign-in with the authorization code flow + PKCE (public client `skybook-mobile`, no secret in the
 * app). Tokens live in the device keychain/keystore (expo-secure-store), never in plain storage.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const discovery = useAutoDiscovery(config.oidcIssuer);
  const redirectUri = makeRedirectUri({ scheme: 'skybook', path: 'auth' });
  const [request, response, promptAsync] = useAuthRequest(
    { clientId: config.oidcClientId, redirectUri, scopes: ['openid', 'profile', 'email'], usePKCE: true },
    discovery,
  );
  const [tokens, setTokens] = useState<StoredTokens | null>(null);
  const [ready, setReady] = useState(false);
  const tokensRef = useRef<StoredTokens | null>(null);

  const save = useCallback(async (next: StoredTokens | null) => {
    tokensRef.current = next;
    setTokens(next);
    if (next) await SecureStore.setItemAsync(STORAGE_KEY, JSON.stringify(next));
    else await SecureStore.deleteItemAsync(STORAGE_KEY);
  }, []);

  useEffect(() => {
    void SecureStore.getItemAsync(STORAGE_KEY)
      .then((raw) => {
        const stored = raw ? (JSON.parse(raw) as StoredTokens) : null;
        tokensRef.current = stored;
        setTokens(stored);
      })
      .catch(() => undefined)
      .finally(() => setReady(true));
  }, []);

  useEffect(() => {
    if (response?.type !== 'success' || !discovery || !request?.codeVerifier) return;
    void exchangeCodeAsync(
      {
        clientId: config.oidcClientId,
        code: response.params.code,
        redirectUri,
        extraParams: { code_verifier: request.codeVerifier },
      },
      discovery,
    ).then((result) => save(toStored(result)));
  }, [response, discovery, request, redirectUri, save]);

  useEffect(() => {
    setTokenProvider(async () => {
      const current = tokensRef.current;
      if (!current) return undefined;
      if (current.expiresAt - EXPIRY_MARGIN_MS > Date.now()) return current.accessToken;
      if (!current.refreshToken || !discovery) {
        await save(null);
        return undefined;
      }
      try {
        const refreshed = await refreshAsync(
          { clientId: config.oidcClientId, refreshToken: current.refreshToken },
          discovery,
        );
        const next = toStored({ ...refreshed, refreshToken: refreshed.refreshToken ?? current.refreshToken });
        await save(next);
        return next.accessToken;
      } catch {
        await save(null); // refresh token expired or revoked: sign in again
        return undefined;
      }
    });
  }, [discovery, save]);

  const value = useMemo<AuthState>(
    () => ({
      ready: ready && !!discovery,
      signedIn: !!tokens,
      signIn: () => void promptAsync(),
      signOut: () => save(null),
    }),
    [ready, discovery, tokens, promptAsync, save],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
