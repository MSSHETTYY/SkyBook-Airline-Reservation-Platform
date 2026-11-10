import Constants from 'expo-constants';

export interface MobileConfig {
  apiBaseUrl: string;
  oidcIssuer: string;
  oidcClientId: string;
}

/**
 * From app.json `extra` (overridable per build profile). Defaults target the Android emulator, which
 * reaches the developer machine at 10.0.2.2; use the machine's LAN IP for a physical phone.
 */
export function readConfig(extra: Record<string, unknown> | undefined = Constants.expoConfig?.extra): MobileConfig {
  const text = (key: string, fallback: string) =>
    typeof extra?.[key] === 'string' && extra[key] !== '' ? (extra[key] as string) : fallback;
  return {
    apiBaseUrl: text('apiBaseUrl', 'http://10.0.2.2:8080').replace(/\/$/, ''),
    oidcIssuer: text('oidcIssuer', 'http://10.0.2.2:8180/realms/skybook'),
    oidcClientId: text('oidcClientId', 'skybook-mobile'),
  };
}

export const config = readConfig();
