import { act, render, screen, waitFor } from '@testing-library/react-native';
import { Text } from 'react-native';

import { setTokenProvider } from '../src/api';
import { AuthProvider, toStored, useAuth } from '../src/auth';

const mockStore = new Map<string, string>();
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async (key: string) => mockStore.get(key) ?? null),
  setItemAsync: jest.fn(async (key: string, value: string) => void mockStore.set(key, value)),
  deleteItemAsync: jest.fn(async (key: string) => void mockStore.delete(key)),
}));
jest.mock('expo-web-browser', () => ({ maybeCompleteAuthSession: jest.fn() }));

const mockPromptAsync = jest.fn();
let mockResponse: unknown = null;
const mockExchange = jest.fn();
const mockRefresh = jest.fn();
jest.mock('expo-auth-session', () => ({
  makeRedirectUri: () => 'skybook://auth',
  useAutoDiscovery: () => ({ tokenEndpoint: 'http://kc/token', authorizationEndpoint: 'http://kc/auth' }),
  useAuthRequest: () => [{ codeVerifier: 'verifier-123' }, mockResponse, mockPromptAsync],
  exchangeCodeAsync: (...args: unknown[]) => mockExchange(...args),
  refreshAsync: (...args: unknown[]) => mockRefresh(...args),
}));

let mockProvider: () => Promise<string | undefined> = async () => undefined;
jest.mock('../src/api', () => ({
  setTokenProvider: jest.fn((p: () => Promise<string | undefined>) => {
    mockProvider = p;
  }),
}));

function Probe() {
  const auth = useAuth();
  return <Text>{auth.ready ? (auth.signedIn ? 'signed-in' : 'signed-out') : 'loading'}</Text>;
}

beforeEach(() => {
  mockStore.clear();
  mockResponse = null;
  jest.clearAllMocks();
});

describe('AuthProvider', () => {
  it('exchanges the authorization code with the PKCE verifier and stores the tokens securely', async () => {
    mockResponse = { type: 'success', params: { code: 'code-1' } };
    mockExchange.mockResolvedValue({ accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 300 });

    render(<AuthProvider><Probe /></AuthProvider>);

    expect(await screen.findByText('signed-in')).toBeTruthy();
    expect(mockExchange).toHaveBeenCalledWith(
      expect.objectContaining({ code: 'code-1', extraParams: { code_verifier: 'verifier-123' } }), expect.anything());
    expect(JSON.parse(mockStore.get('skybook.tokens')!).accessToken).toBe('access-1');
    expect(await mockProvider()).toBe('access-1');
    expect(setTokenProvider).toHaveBeenCalled();
  });

  it('refreshes an expiring token, and signs out when refreshing fails', async () => {
    mockStore.set('skybook.tokens', JSON.stringify({ accessToken: 'old', refreshToken: 'r1', expiresAt: Date.now() + 1000 }));
    mockRefresh.mockResolvedValueOnce({ accessToken: 'new', expiresIn: 300 });
    render(<AuthProvider><Probe /></AuthProvider>);
    expect(await screen.findByText('signed-in')).toBeTruthy();

    expect(await act(() => mockProvider())).toBe('new');
    expect(JSON.parse(mockStore.get('skybook.tokens')!).refreshToken).toBe('r1');

    mockStore.set('skybook.tokens', JSON.stringify({ accessToken: 'x', refreshToken: 'r1', expiresAt: 0 }));
    screen.unmount();
    render(<AuthProvider><Probe /></AuthProvider>);
    await screen.findByText('signed-in');
    mockRefresh.mockRejectedValueOnce(new Error('invalid_grant'));
    expect(await act(() => mockProvider())).toBeUndefined();
    await waitFor(() => expect(screen.getByText('signed-out')).toBeTruthy());
  });

  it('starts signed out, prompts for sign-in, and signs out', async () => {
    let auth: ReturnType<typeof useAuth> | undefined;
    function Capture() {
      auth = useAuth();
      return null;
    }
    render(<AuthProvider><Probe /><Capture /></AuthProvider>);
    expect(await screen.findByText('signed-out')).toBeTruthy();
    expect(await mockProvider()).toBeUndefined();
    act(() => auth!.signIn());
    expect(mockPromptAsync).toHaveBeenCalled();
    await act(() => auth!.signOut());
    expect(mockStore.has('skybook.tokens')).toBe(false);
    expect(toStored({ accessToken: 'a' }, 0).expiresAt).toBe(300_000);
  });

  it('requires the mockProvider', () => {
    jest.spyOn(console, 'error').mockImplementation(() => undefined);
    expect(() => render(<Probe />)).toThrow('useAuth must be used inside AuthProvider');
  });
});
