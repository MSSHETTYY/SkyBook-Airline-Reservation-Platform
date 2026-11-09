import { readConfig } from '../config';
import { hasAnyRole, rolesFromAccessToken } from './session';

function jwt(claims: object): string {
  const encode = (value: object) =>
    btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${encode({ alg: 'RS256' })}.${encode(claims)}.signature`;
}

describe('rolesFromAccessToken', () => {
  it('keeps only the SkyBook realm roles', () => {
    const token = jwt({ realm_access: { roles: ['offline_access', 'CUSTOMER', 'uma_authorization'] } });
    expect(rolesFromAccessToken(token)).toEqual(['CUSTOMER']);
    expect(rolesFromAccessToken(jwt({ realm_access: { roles: ['ADMIN', 'AGENT'] } }))).toEqual(['AGENT', 'ADMIN']);
  });

  it('returns no roles for missing or malformed tokens', () => {
    expect(rolesFromAccessToken(undefined)).toEqual([]);
    expect(rolesFromAccessToken('not-a-jwt')).toEqual([]);
    expect(rolesFromAccessToken(jwt({ sub: 'x' }))).toEqual([]);
  });

  it('checks roles', () => {
    expect(hasAnyRole({ name: '', email: '', roles: ['AGENT'] }, ['AGENT', 'ADMIN'])).toBe(true);
    expect(hasAnyRole({ name: '', email: '', roles: ['CUSTOMER'] }, ['ADMIN'])).toBe(false);
    expect(hasAnyRole(undefined, ['CUSTOMER'])).toBe(false);
  });
});

describe('readConfig', () => {
  it('defaults to OIDC against the local Keycloak and same-origin API', () => {
    expect(readConfig({} as ImportMetaEnv, {})).toEqual({
      authMode: 'oidc',
      apiMocking: false,
      apiBaseUrl: '',
      oidcAuthority: 'http://localhost:8180/realms/skybook',
      oidcClientId: 'skybook-web',
      stripePublishableKey: undefined,
    });
  });

  it('lets the deployment override build-time values, ignoring blanks', () => {
    const build = { VITE_OIDC_AUTHORITY: 'http://localhost:8180/realms/skybook', VITE_OIDC_CLIENT_ID: 'skybook-web' };
    const config = readConfig(build as ImportMetaEnv, {
      VITE_OIDC_AUTHORITY: 'https://login.skybook.example/realms/skybook',
      VITE_OIDC_CLIENT_ID: '',
    });
    expect(config.oidcAuthority).toBe('https://login.skybook.example/realms/skybook');
    expect(config.oidcClientId).toBe('skybook-web');
  });

  it('reads overrides', () => {
    const config = readConfig({
      VITE_AUTH_MODE: 'mock',
      VITE_API_MOCKING: 'true',
      VITE_API_BASE_URL: 'https://api.example/',
      VITE_STRIPE_PUBLISHABLE_KEY: ' pk_test_1 ',
    } as ImportMetaEnv);
    expect(config).toMatchObject({
      authMode: 'mock',
      apiMocking: true,
      apiBaseUrl: 'https://api.example',
      stripePublishableKey: 'pk_test_1',
    });
  });
});
