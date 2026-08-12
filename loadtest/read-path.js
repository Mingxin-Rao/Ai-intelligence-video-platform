// =============================================================================
// Load test: the authenticated read path (GET /media/list).
//
// This is the endpoint that matters under load: while a task is being processed
// the SPA polls it every 3 seconds, so it takes concurrency proportional to the
// number of users waiting, and it is the endpoint the Redis cache exists for.
//
// Run:
//   BASE_URL=http://localhost:9091 k6 run loadtest/read-path.js
//
// Registers a throwaway user in setup(), logs in once, then reuses the JWT
// across all VUs — measuring the read path, not the login path.
// =============================================================================
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:9091';

const listDuration = new Trend('list_duration_ms', true);
const listFailRate = new Rate('list_failed');

export const options = {
  // Ramp so the results show behaviour at increasing concurrency rather than a
  // single point, and hold at the top long enough for the numbers to settle.
  stages: [
    { duration: '20s', target: 10 },
    { duration: '30s', target: 50 },
    { duration: '20s', target: 50 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    // Fail the run rather than reporting a soft "looks fine".
    'http_req_failed': ['rate<0.01'],
    'list_duration_ms': ['p(95)<500'],
  },
};

export function setup() {
  const email = `loadtest_${Date.now()}@example.com`;
  const password = 'LoadTest!2345';
  const headers = { 'Content-Type': 'application/json' };

  const reg = http.post(`${BASE}/user/register`,
    JSON.stringify({ email, password, nickname: 'loadtest' }), { headers });
  if (reg.status !== 200) {
    throw new Error(`register failed: ${reg.status} ${reg.body}`);
  }

  const login = http.post(`${BASE}/user/login`,
    JSON.stringify({ email, password }), { headers });
  const token = login.json('token');
  if (!token) {
    throw new Error(`login returned no token: ${login.status} ${login.body}`);
  }
  return { token };
}

export default function (data) {
  const res = http.get(`${BASE}/media/list`, {
    headers: { Authorization: `Bearer ${data.token}` },
    tags: { name: 'media_list' },
  });

  listDuration.add(res.timings.duration);
  listFailRate.add(res.status !== 200);

  check(res, {
    'status is 200': (r) => r.status === 200,
    'body is a JSON array': (r) => Array.isArray(r.json()),
  });

  // Matches the SPA's 3s polling interval, so concurrency here reflects real
  // waiting users rather than an artificial hammer.
  sleep(3);
}
