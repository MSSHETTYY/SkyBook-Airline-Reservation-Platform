import { CssBaseline, ThemeProvider } from '@mui/material';
import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';

import { App } from './App';
import { config } from './config';
import { createQueryClient } from './queryClient';
import { theme } from './theme';

async function enableMocking(): Promise<void> {
  // Compared against the raw env value (a literal after Vite's replacement), so production builds
  // drop this branch and the mock API is not even emitted as a chunk.
  if (import.meta.env.VITE_API_MOCKING !== 'true') return;
  const { worker } = await import('./mocks/browser');
  await worker.start({ onUnhandledRequest: 'bypass', quiet: true });
}

void enableMocking().then(() => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <QueryClientProvider client={createQueryClient()}>
          <BrowserRouter>
            <App config={config} />
          </BrowserRouter>
        </QueryClientProvider>
      </ThemeProvider>
    </StrictMode>,
  );
});
