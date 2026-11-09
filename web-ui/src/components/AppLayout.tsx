import FlightTakeoffIcon from '@mui/icons-material/FlightTakeoff';
import { AppBar, Box, Button, Container, Link, Stack, Toolbar, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { NavLink, Link as RouterLink } from 'react-router';

import { hasAnyRole, STAFF, useSession } from '../auth/session';

/**
 * Page chrome: skip link (WCAG 2.4.1), landmarks (header / nav / main / footer) and a navigation
 * that only offers what the signed-in user can use.
 */
export function AppLayout({ children }: { children: ReactNode }) {
  const { user, status, signIn, signOut } = useSession();
  const staff = hasAnyRole(user, STAFF);
  const admin = hasAnyRole(user, ['ADMIN']);

  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      <Link
        href="#main"
        sx={{
          position: 'absolute',
          left: 8,
          top: -48,
          zIndex: 2000,
          bgcolor: 'background.paper',
          p: 1,
          '&:focus': { top: 8 },
        }}
      >
        Skip to main content
      </Link>
      <AppBar position="static" component="header">
        <Toolbar sx={{ gap: 2, flexWrap: 'wrap' }}>
          <Stack
            direction="row"
            spacing={1}
            alignItems="center"
            component={RouterLink}
            to="/"
            sx={{ color: 'inherit', textDecoration: 'none' }}
          >
            <FlightTakeoffIcon aria-hidden />
            <Typography variant="h6" component="span">
              SkyBook
            </Typography>
          </Stack>
          <Box component="nav" aria-label="Main" sx={{ flexGrow: 1 }}>
            <Stack direction="row" spacing={1} component="ul" sx={{ listStyle: 'none', m: 0, p: 0, flexWrap: 'wrap' }}>
              <NavItem to="/" end>
                Search flights
              </NavItem>
              {user && <NavItem to="/bookings">My bookings</NavItem>}
              {staff && <NavItem to="/admin/bookings">Servicing</NavItem>}
              {admin && <NavItem to="/admin/flights">Flights</NavItem>}
            </Stack>
          </Box>
          {user ? (
            <Stack direction="row" spacing={1} alignItems="center">
              <Typography variant="body2" component="span">
                {user.name}
              </Typography>
              <Button color="inherit" variant="outlined" onClick={signOut}>
                Sign out
              </Button>
            </Stack>
          ) : (
            <Button color="inherit" variant="outlined" onClick={() => signIn()} disabled={status === 'loading'}>
              Sign in
            </Button>
          )}
        </Toolbar>
      </AppBar>
      <Container component="main" id="main" tabIndex={-1} maxWidth="lg" sx={{ py: 4, flexGrow: 1, outline: 'none' }}>
        {children}
      </Container>
      <Box component="footer" sx={{ py: 3, textAlign: 'center', color: 'text.secondary' }}>
        <Typography variant="body2">SkyBook demo - test data only, no real flights or payments.</Typography>
      </Box>
    </Box>
  );
}

function NavItem({ to, end, children }: { to: string; end?: boolean; children: ReactNode }) {
  return (
    <li>
      <Button
        component={NavLink}
        to={to}
        end={end}
        color="inherit"
        sx={{ '&.active': { textDecoration: 'underline', textUnderlineOffset: 6, fontWeight: 700 } }}
      >
        {children}
      </Button>
    </li>
  );
}
