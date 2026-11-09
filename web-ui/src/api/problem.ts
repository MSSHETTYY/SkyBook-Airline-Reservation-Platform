/**
 * Turns anything thrown by the generated clients into an RFC 9457 problem the UI can show.
 * The backend's `code` drives specific messages; `correlationId` is shown so support can find the logs.
 */
export interface ApiProblem {
  status: number;
  title: string;
  detail?: string;
  code?: string;
  correlationId?: string;
}

const NETWORK_PROBLEM: ApiProblem = {
  status: 0,
  title: 'Network error',
  detail: 'We could not reach SkyBook. Check your connection and try again.',
};

/** Friendlier wording for the codes a customer can actually act on. */
const MESSAGES: Record<string, string> = {
  SEAT_UNAVAILABLE: 'One of the seats you picked was just taken. Please choose another seat.',
  DUPLICATE_SEAT: 'Each passenger needs a different seat.',
  HOLD_EXPIRED: 'Your seat hold expired. Please start again.',
  FLIGHT_NOT_BOOKABLE: 'This flight is no longer open for booking.',
  NOT_CANCELLABLE: 'This booking can no longer be cancelled.',
  NOT_CHANGEABLE: 'This booking can no longer be changed.',
  REFUND_EXCEEDS_PAYMENT: 'The refund is larger than the amount still refundable.',
  CONCURRENT_MODIFICATION: 'Someone else changed this at the same time. Reload and try again.',
  UNAUTHENTICATED: 'Your session has ended. Please sign in again.',
  ACCESS_DENIED: 'You are not allowed to do this.',
  SERVICE_UNAVAILABLE: 'This service is temporarily unavailable. Please try again shortly.',
};

interface ErrorWithResponse {
  name?: string;
  response?: Response;
}

export async function toProblem(error: unknown): Promise<ApiProblem> {
  if (isProblem(error)) return error;
  const response = (error as ErrorWithResponse | null)?.response;
  if (response instanceof Response) {
    let body: Partial<ApiProblem> = {};
    try {
      body = (await response.clone().json()) as Partial<ApiProblem>;
    } catch {
      // Not JSON (e.g. a proxy's HTML error page): fall back to the status line.
    }
    const status = body.status ?? response.status;
    return {
      status,
      title: body.title ?? defaultTitle(status),
      detail: (body.code && MESSAGES[body.code]) ?? body.detail,
      code: body.code,
      correlationId: body.correlationId ?? response.headers.get('X-Correlation-Id') ?? undefined,
    };
  }
  if ((error as ErrorWithResponse | null)?.name === 'FetchError' || error instanceof TypeError) {
    return NETWORK_PROBLEM;
  }
  return { status: 0, title: 'Something went wrong', detail: error instanceof Error ? error.message : undefined };
}

export function isProblem(value: unknown): value is ApiProblem {
  return typeof value === 'object' && value !== null && 'status' in value && 'title' in value
    && !(value instanceof Error);
}

function defaultTitle(status: number): string {
  if (status === 401) return 'Please sign in';
  if (status === 403) return 'You do not have access to this';
  if (status === 404) return 'Not found';
  if (status === 409) return 'Conflict';
  if (status === 422) return 'Request not allowed';
  if (status === 429) return 'Too many requests';
  if (status >= 500) return 'Service unavailable';
  return 'Request failed';
}

/** Wraps an API call so TanStack Query errors are always ApiProblem (async parsing done once, here). */
export async function call<T>(fn: () => Promise<T>): Promise<T> {
  try {
    return await fn();
  } catch (error) {
    throw await toProblem(error);
  }
}

// Every query/mutation function goes through call(), so errors are always ApiProblem.
declare module '@tanstack/react-query' {
  interface Register {
    defaultError: ApiProblem;
  }
}
