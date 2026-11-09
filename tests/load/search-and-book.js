// k6 load test: the search -> seat map -> book -> pay journey at peak load (docs/nfr.md).
//
//   k6 run tests/load/search-and-book.js                      # full profile: ramps to 500 virtual users
//   k6 run -e PROFILE=smoke tests/load/search-and-book.js     # 5 users for 30 s, a quick sanity check
//
// Objectives enforced as thresholds (the run fails if they are missed):
//   * flight search p95 < 300 ms, seat map p95 < 300 ms
//   * booking creation p95 < 800 ms (two synchronous partner calls: inventory hold + payment intent)
//   * < 1% failed requests overall; 409 SEAT_UNAVAILABLE is an expected business outcome, not a failure
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { randomIntBetween, randomItem, uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const GATEWAY = __ENV.GATEWAY || 'http://localhost:8080';
const KEYCLOAK = __ENV.KEYCLOAK || 'http://localhost:8180';
const PROFILE = __ENV.PROFILE || 'peak';
const ROUTES = [['DXB', 'LHR'], ['LHR', 'DXB'], ['DXB', 'BOM'], ['BOM', 'DXB'], ['LHR', 'JFK']];

const seatConflicts = new Counter('seat_conflicts');
const bookingsCreated = new Counter('bookings_created');
const journeyOk = new Rate('journey_success');

const PROFILES = {
  smoke: { executor: 'constant-vus', vus: 5, duration: '30s' },
  // Browsing dominates real traffic: every user searches, about one in five books.
  peak: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '2m', target: 100 },
      { duration: '3m', target: 500 },
      { duration: '5m', target: 500 },
      { duration: '2m', target: 0 },
    ],
    gracefulRampDown: '30s',
  },
};

export const options = {
  scenarios: { journey: PROFILES[PROFILE] },
  thresholds: {
    'http_req_duration{name:search}': ['p(95)<300'],
    'http_req_duration{name:seatmap}': ['p(95)<300'],
    'http_req_duration{name:create_booking}': ['p(95)<800'],
    http_req_failed: ['rate<0.01'],
    journey_success: ['rate>0.99'],
  },
  // 409 is a legitimate answer under contention; do not count it as an HTTP failure.
  summaryTrendStats: ['avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 409));

function isoDate(daysAhead) {
  const d = new Date(Date.now() + daysAhead * 86_400_000);
  return d.toISOString().slice(0, 10);
}

export function setup() {
  const res = http.post(`${KEYCLOAK}/realms/skybook/protocol/openid-connect/token`, {
    grant_type: 'password',
    client_id: 'skybook-test-client',
    username: 'customer@skybook.test',
    password: __ENV.CUSTOMER_PASSWORD || 'Customer123!',
  });
  check(res, { 'token issued': (r) => r.status === 200 });
  return { token: res.json('access_token') };
}

export default function (data) {
  const [origin, destination] = randomItem(ROUTES);
  const date = isoDate(randomIntBetween(1, 13));
  let ok = true;

  const flights = group('search', () => {
    const res = http.get(`${GATEWAY}/api/v1/flights?origin=${origin}&destination=${destination}&date=${date}`,
      { tags: { name: 'search' } });
    ok = check(res, { 'search 200': (r) => r.status === 200 }) && ok;
    return res.status === 200 ? res.json() : [];
  });
  sleep(randomIntBetween(1, 3)); // think time: reading the results

  if (flights.length === 0) {
    journeyOk.add(ok);
    return;
  }
  const flight = randomItem(flights);
  const seats = group('seat map', () => {
    const res = http.get(`${GATEWAY}/api/v1/flights/${flight.flightId}/seats`, { tags: { name: 'seatmap' } });
    ok = check(res, { 'seat map 200': (r) => r.status === 200 }) && ok;
    return res.status === 200 ? res.json('seats').filter((s) => s.available) : [];
  });
  sleep(randomIntBetween(2, 5));

  if (Math.random() < 0.2 && seats.length > 0) {
    group('book', () => {
      const seat = randomItem(seats).seatNumber;
      const res = http.post(`${GATEWAY}/api/v1/bookings`, JSON.stringify({
        flightId: flight.flightId,
        contactEmail: 'customer@skybook.test',
        passengers: [{ firstName: 'Load', lastName: 'Test', dateOfBirth: '1990-01-01', seatNumber: seat }],
      }), {
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}`,
          'Idempotency-Key': uuidv4() },
        tags: { name: 'create_booking' },
      });
      if (res.status === 409) {
        seatConflicts.add(1); // someone else took the seat between the map and the booking
        return;
      }
      ok = check(res, { 'booking 201': (r) => r.status === 201 }) && ok;
      if (res.status === 201) bookingsCreated.add(1);
    });
  }
  journeyOk.add(ok);
}
