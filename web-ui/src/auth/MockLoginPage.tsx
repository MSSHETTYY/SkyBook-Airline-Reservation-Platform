import { Alert, Button, Paper, Stack, Typography } from '@mui/material';
import { useNavigate, useSearchParams } from 'react-router';

import { PageTitle } from '../components/PageTitle';
import { MOCK_USERS, useMockSignIn } from './MockSessionProvider';
import type { Role } from './session';

const DESCRIPTIONS: Record<Role, string> = {
  CUSTOMER: 'Search, book, pay, change and cancel your own trips',
  AGENT: 'Service any booking',
  ADMIN: 'Agent rights plus flight management and goodwill refunds',
};

/** Demo sign-in (mock mode only). With Keycloak, signIn() redirects to the Keycloak login page instead. */
export function MockLoginPage() {
  const signInAs = useMockSignIn();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const returnTo = params.get('returnTo');
  const target = returnTo?.startsWith('/') && !returnTo.startsWith('//') ? returnTo : '/';

  return (
    <>
      <PageTitle title="Sign in" />
      <Alert severity="info" sx={{ mb: 3 }}>
        Demo mode: there is no identity provider. Pick a role to continue.
      </Alert>
      <Stack spacing={2} sx={{ maxWidth: 520 }}>
        {(Object.keys(MOCK_USERS) as Role[]).map((role) => (
          <Paper key={role} sx={{ p: 2 }} variant="outlined">
            <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={2}>
              <div>
                <Typography variant="h3" component="h2">
                  {MOCK_USERS[role].name}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {DESCRIPTIONS[role]}
                </Typography>
              </div>
              <Button
                variant="contained"
                onClick={() => {
                  signInAs(MOCK_USERS[role]);
                  navigate(target, { replace: true });
                }}
              >
                Continue as {role.toLowerCase()}
              </Button>
            </Stack>
          </Paper>
        ))}
      </Stack>
    </>
  );
}
