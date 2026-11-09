/** Build-time configuration (Vite inlines `import.meta.env`); read once, typed, with safe defaults. */
export interface AppConfig {
  authMode: 'oidc' | 'mock';
  apiMocking: boolean;
  apiBaseUrl: string;
  oidcAuthority: string;
  oidcClientId: string;
  stripePublishableKey: string | undefined;
}

/**
 * Deploy-time overrides written by the container at start-up (public/config.js, see web-ui/docker):
 * the same image runs in every environment, only this file differs. Wins over build-time values.
 */
export type RuntimeConfig = Partial<Record<keyof ImportMetaEnv, string>>;

declare global {
  interface Window {
    __SKYBOOK_CONFIG__?: RuntimeConfig;
  }
}

function runtimeConfig(): RuntimeConfig {
  return (typeof window === 'undefined' ? undefined : window.__SKYBOOK_CONFIG__) ?? {};
}

export function readConfig(buildEnv: ImportMetaEnv = import.meta.env, runtime: RuntimeConfig = runtimeConfig()):
  AppConfig {
  // Blank runtime values are ignored, so an unset container variable keeps the build-time default.
  const overrides = Object.fromEntries(Object.entries(runtime).filter(([, v]) => typeof v === 'string' && v !== ''));
  const env = { ...buildEnv, ...overrides } as ImportMetaEnv;
  return {
    authMode: env.VITE_AUTH_MODE === 'mock' ? 'mock' : 'oidc',
    apiMocking: env.VITE_API_MOCKING === 'true',
    // Empty = same origin (dev proxy, or the gateway serving the SPA behind one ingress).
    apiBaseUrl: (env.VITE_API_BASE_URL ?? '').replace(/\/$/, ''),
    oidcAuthority: env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8180/realms/skybook',
    oidcClientId: env.VITE_OIDC_CLIENT_ID ?? 'skybook-web',
    stripePublishableKey: env.VITE_STRIPE_PUBLISHABLE_KEY?.trim() || undefined,
  };
}

export const config = readConfig();
