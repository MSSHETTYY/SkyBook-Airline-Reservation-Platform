import { setupWorker } from 'msw/browser';

import { MockDb } from './db';
import { createHandlers } from './handlers';

/** Browser mock API for `npm run dev:mock` and E2E. State lives until the page is reloaded. */
export const db = new MockDb();
export const worker = setupWorker(...createHandlers(db));
