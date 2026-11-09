import { Alert, Box, CircularProgress, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { useEffect } from 'react';
import { useLocation } from 'react-router';

import type { Role } from './session';
import { hasAnyRole, useSession } from './session';

export function RequireAuth({ children }: { children: ReactNode }) {
  const { status, signIn } = useSession();
  const location = useLocation();
  const returnTo = location.pathname + location.search;

  useEffect(() => {
    if (status === 'anonymous') signIn(returnTo);
  }, [status, signIn, returnTo]);

  if (status !== 'authenticated') {
    return (
      <Box role="status" sx={{ display: 'flex', gap: 2, alignItems: 'center', py: 6, justifyContent: 'center' }}>
        <CircularProgress size={24} aria-hidden />
        <Typography>{status === 'loading' ? 'Checking your session...' : 'Redirecting to sign in...'}</Typography>
      </Box>
    );
  }
  return <>{children}</>;
}

/** UX only: hides pages the user cannot use. The API enforces the same roles server-side. */
export function RequireRole({ roles, children }: { roles: readonly Role[]; children: ReactNode }) {
  const { user } = useSession();
  return (
    <RequireAuth>
      {hasAnyRole(user, roles) ? (
        children
      ) : (
        <Alert severity="error" sx={{ my: 4 }}>
          You do not have access to this page.
        </Alert>
      )}
    </RequireAuth>
  );
}
