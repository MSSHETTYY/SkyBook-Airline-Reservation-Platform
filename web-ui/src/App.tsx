import { Button, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { lazy, Suspense, useCallback } from 'react';
import { Link as RouterLink, Route, Routes, useNavigate } from 'react-router';

import { RequireAuth, RequireRole } from './auth/guards';
import { MockLoginPage } from './auth/MockLoginPage';
import { MockSessionProvider } from './auth/MockSessionProvider';
import { OidcSessionProvider } from './auth/OidcSessionProvider';
import { STAFF } from './auth/session';
import { AppLayout } from './components/AppLayout';
import { LoadingState } from './components/LoadingState';
import { PageTitle } from './components/PageTitle';
import type { AppConfig } from './config';
import { BookFlightPage } from './features/booking/BookFlightPage';
import { BookingDetailsPage } from './features/booking/BookingDetailsPage';
import { ChangeDatePage } from './features/booking/ChangeDatePage';
import { MyBookingsPage } from './features/booking/MyBookingsPage';
import { SearchPage } from './features/search/SearchPage';

// Staff screens are a separate chunk: most visitors are customers and never download them.
const AdminBookingsPage = lazy(() =>
  import('./features/admin/AdminBookingsPage').then((m) => ({ default: m.AdminBookingsPage })),
);
const AdminFlightsPage = lazy(() =>
  import('./features/admin/AdminFlightsPage').then((m) => ({ default: m.AdminFlightsPage })),
);

export function AppRoutes({ mockAuth }: { mockAuth: boolean }) {
  return (
    <AppLayout>
      <Suspense fallback={<LoadingState />}>
        <Routes>
          <Route path="/" element={<SearchPage />} />
          {mockAuth && <Route path="/login" element={<MockLoginPage />} />}
          <Route path="/flights/:flightId/book" element={<RequireAuth><BookFlightPage /></RequireAuth>} />
          <Route path="/bookings" element={<RequireAuth><MyBookingsPage /></RequireAuth>} />
          <Route path="/bookings/:reference" element={<RequireAuth><BookingDetailsPage /></RequireAuth>} />
          <Route path="/bookings/:reference/change" element={<RequireAuth><ChangeDatePage /></RequireAuth>} />
          <Route path="/admin/bookings" element={<RequireRole roles={STAFF}><AdminBookingsPage /></RequireRole>} />
          <Route path="/admin/flights" element={<RequireRole roles={['ADMIN']}><AdminFlightsPage /></RequireRole>} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </Suspense>
    </AppLayout>
  );
}

function NotFound() {
  return (
    <>
      <PageTitle title="Page not found" />
      <Typography sx={{ mb: 2 }}>The page you are looking for does not exist.</Typography>
      <Button component={RouterLink} to="/" variant="contained">
        Search flights
      </Button>
    </>
  );
}

/** Picks the sign-in mechanism from configuration; everything below only sees `useSession()`. */
export function SessionProvider({ config, children }: { config: AppConfig; children: ReactNode }) {
  const navigate = useNavigate();
  const toLogin = useCallback(
    (returnTo: string) => navigate(`/login?returnTo=${encodeURIComponent(returnTo)}`),
    [navigate],
  );
  if (config.authMode === 'mock') {
    return <MockSessionProvider navigateToLogin={toLogin}>{children}</MockSessionProvider>;
  }
  return (
    <OidcSessionProvider authority={config.oidcAuthority} clientId={config.oidcClientId}>
      {children}
    </OidcSessionProvider>
  );
}

export function App({ config }: { config: AppConfig }) {
  return (
    <SessionProvider config={config}>
      <AppRoutes mockAuth={config.authMode === 'mock'} />
    </SessionProvider>
  );
}
