import { useCallback, useRef } from 'react';

import { newIdempotencyKey } from './client';

/**
 * Same payload → same Idempotency-Key, so a double click or a retry after a timeout cannot create
 * two bookings. A changed payload (e.g. the user picked another seat after a conflict) gets a new
 * key, because the server rejects a key reused with a different body (IDEMPOTENCY_KEY_REUSED).
 */
export function useIdempotencyKey(): (payload: unknown) => string {
  const last = useRef<{ fingerprint: string; key: string } | null>(null);
  return useCallback((payload: unknown) => {
    const fingerprint = JSON.stringify(payload);
    if (last.current?.fingerprint !== fingerprint) {
      last.current = { fingerprint, key: newIdempotencyKey() };
    }
    return last.current.key;
  }, []);
}
