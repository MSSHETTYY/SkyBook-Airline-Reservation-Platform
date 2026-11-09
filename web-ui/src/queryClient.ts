import { QueryClient } from '@tanstack/react-query';

import type { ApiProblem } from './api/problem';

/** Client errors (4xx) are answers, not glitches: retrying them only delays the message. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  const status = (error as Partial<ApiProblem> | null)?.status ?? 0;
  if (status >= 400 && status < 500 && status !== 408 && status !== 429) return false;
  return failureCount < 2;
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: shouldRetry, refetchOnWindowFocus: false, staleTime: 10_000 },
      // Mutations are never retried automatically; the idempotency key makes a manual retry safe.
      mutations: { retry: false },
    },
  });
}
