import { CssBaseline, ThemeProvider } from '@mui/material';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { RenderResult } from '@testing-library/react';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { useCallback } from 'react';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router';

import { AppRoutes } from '../App';
import { MOCK_USERS, MockSessionProvider } from '../auth/MockSessionProvider';
import type { Role } from '../auth/session';
import { shouldRetry } from '../queryClient';
import { theme } from '../theme';

export function testQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: (count, error) => shouldRetry(count, error) && count < 1, retryDelay: 0, gcTime: Infinity },
      mutations: { retry: false },
    },
  });
}

interface Options {
  route?: string;
  role?: Role;
}

/** Current location, rendered so tests can assert on navigation. */
function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname + location.search}</output>;
}

function Providers({ children, route, role, onLogin }: Options & { children: ReactElement; onLogin?: (to: string)
  => void }) {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <QueryClientProvider client={testQueryClient()}>
        <MemoryRouter initialEntries={[route ?? '/']}>
          <LoginNavigator onLogin={onLogin}>
            {(toLogin) => (
              <MockSessionProvider navigateToLogin={toLogin} initialUser={role ? MOCK_USERS[role] : undefined}>
                {children}
                <LocationProbe />
              </MockSessionProvider>
            )}
          </LoginNavigator>
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>
  );
}

function LoginNavigator({ children, onLogin }: { children: (toLogin: (to: string) => void) => ReactElement;
  onLogin?: (to: string) => void }) {
  const navigate = useNavigate();
  const toLogin = useCallback(
    (to: string) => {
      onLogin?.(to);
      navigate(`/login?returnTo=${encodeURIComponent(to)}`);
    },
    [navigate, onLogin],
  );
  return children(toLogin);
}

/** Renders the whole app (routes, guards, layout) at `route`, signed in as `role` if given. */
export function renderApp(options: Options = {}): RenderResult {
  return render(
    <Providers {...options}>
      <AppRoutes mockAuth />
    </Providers>,
  );
}

/** Renders one component inside the providers, at `route` matched by `path`. */
export function renderWithProviders(ui: ReactElement, options: Options & { path?: string } = {}): RenderResult {
  return render(
    <Providers {...options}>
      <Routes>
        <Route path={options.path ?? '*'} element={ui} />
      </Routes>
    </Providers>,
  );
}
