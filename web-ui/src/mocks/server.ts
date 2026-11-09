import { setupServer } from 'msw/node';

import { MockDb } from './db';
import { createHandlers } from './handlers';

/** Node mock API for Vitest; tests call resetServer() for a fresh database per test. */
export let db = new MockDb({ ticketingDelayMs: 0 });
export const server = setupServer(...createHandlers(db));

export function resetServer(next: MockDb = new MockDb({ ticketingDelayMs: 0 })): MockDb {
  db = next;
  server.resetHandlers(...createHandlers(db));
  return db;
}
