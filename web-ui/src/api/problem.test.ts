import { shouldRetry } from '../queryClient';
import { call, isProblem, toProblem } from './problem';

function responseError(body: unknown, status: number, headers: Record<string, string> = {}) {
  const response = new Response(typeof body === 'string' ? body : JSON.stringify(body), { status, headers });
  return Object.assign(new Error('Response returned an error code'), { name: 'ResponseError', response });
}

describe('toProblem', () => {
  it('reads RFC 9457 bodies and replaces known codes with customer-friendly text', async () => {
    const problem = await toProblem(
      responseError({ title: 'Conflict', status: 409, code: 'SEAT_UNAVAILABLE', correlationId: 'c-1' }, 409),
    );
    expect(problem).toEqual({
      status: 409,
      title: 'Conflict',
      code: 'SEAT_UNAVAILABLE',
      correlationId: 'c-1',
      detail: expect.stringContaining('just taken'),
    });
  });

  it('keeps the server detail for codes without a custom message', async () => {
    const problem = await toProblem(responseError({ status: 422, title: 'No', detail: 'Server says no', code: 'X' },
      422));
    expect(problem.detail).toBe('Server says no');
  });

  it('falls back to the status line when the body is not JSON', async () => {
    const problem = await toProblem(responseError('<html>bad gateway</html>', 502, { 'X-Correlation-Id': 'gw-9' }));
    expect(problem).toMatchObject({ status: 502, title: 'Service unavailable', correlationId: 'gw-9' });
  });

  it.each([
    [401, 'Please sign in'],
    [403, 'You do not have access to this'],
    [404, 'Not found'],
    [409, 'Conflict'],
    [422, 'Request not allowed'],
    [429, 'Too many requests'],
    [418, 'Request failed'],
  ])('titles status %i as "%s"', async (status, title) => {
    expect((await toProblem(responseError('', status))).title).toBe(title);
  });

  it('reports network failures and unexpected errors', async () => {
    expect((await toProblem(Object.assign(new Error('x'), { name: 'FetchError' }))).status).toBe(0);
    expect((await toProblem(new TypeError('Failed to fetch'))).title).toBe('Network error');
    expect(await toProblem(new Error('boom'))).toMatchObject({ title: 'Something went wrong', detail: 'boom' });
  });

  it('passes problems through and wraps rejected calls', async () => {
    const problem = { status: 404, title: 'Not found' };
    expect(await toProblem(problem)).toBe(problem);
    expect(isProblem(new Error('x'))).toBe(false);
    await expect(call(() => Promise.reject(responseError({ status: 404 }, 404)))).rejects.toMatchObject({
      status: 404,
    });
    await expect(call(() => Promise.resolve(42))).resolves.toBe(42);
  });
});

describe('shouldRetry', () => {
  it('does not retry client errors except timeouts and rate limiting', () => {
    expect(shouldRetry(0, { status: 404, title: '' })).toBe(false);
    expect(shouldRetry(0, { status: 429, title: '' })).toBe(true);
    expect(shouldRetry(0, { status: 503, title: '' })).toBe(true);
    expect(shouldRetry(2, { status: 503, title: '' })).toBe(false);
  });
});
