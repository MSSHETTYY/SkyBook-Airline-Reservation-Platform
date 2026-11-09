import '@testing-library/jest-dom/vitest';

import { cleanup, configure } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';

import { resetServer, server } from '../mocks/server';

// findBy*/waitFor: 1 s is too tight on small CI runners, especially with coverage instrumentation.
configure({ asyncUtilTimeout: 5000 });

// Any request without a handler is a test bug, not something to silently let through.
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  cleanup();
  resetServer();
  window.sessionStorage.clear();
});
afterAll(() => server.close());
