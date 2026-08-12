// =============================================================================
// Load test: content dedup on the upload path.
//
// Quantifies what dedup actually saves. The first upload of a file stores it and
// inserts a row; every later upload of the same bytes must short-circuit before
// touching MinIO or the database — which also means no second AI task and no
// second paid provider call.
//
// Deliberately triggers no AI work: it only exercises /media/upload, so the run
// costs nothing in provider spend.
//
// Run:
//   BASE_URL=http://localhost:9091 k6 run loadtest/upload-dedup.js
// =============================================================================
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:9091';
const payload = open('./assets/sample.mp4', 'b');

const firstUploadMs = new Trend('first_upload_ms', true);
const dedupedUploadMs = new Trend('deduped_upload_ms', true);
const dedupedCount = new Counter('deduped_responses');
const storedCount = new Counter('stored_responses');

export const options = {
  scenarios: {
    // Sequential: the point is to compare a cold store against repeat uploads,
    // not to race them.
    dedup: { executor: 'per-vu-iterations', vus: 1, iterations: 20 },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    // Every repeat upload must be recognised as duplicate; one miss is a bug.
    'deduped_responses': ['count>=19'],
  },
};

export function setup() {
  const email = `dedup_${Date.now()}@example.com`;
  const password = 'LoadTest!2345';
  const headers = { 'Content-Type': 'application/json' };

  const reg = http.post(`${BASE}/user/register`,
    JSON.stringify({ email, password, nickname: 'dedup' }), { headers });
  if (reg.status !== 200) throw new Error(`register failed: ${reg.status} ${reg.body}`);

  const login = http.post(`${BASE}/user/login`,
    JSON.stringify({ email, password }), { headers });
  const token = login.json('token');
  if (!token) throw new Error(`login returned no token: ${login.body}`);
  return { token };
}

export default function (data) {
  const res = http.post(`${BASE}/media/upload`,
    { file: http.file(payload, 'sample.mp4', 'video/mp4') },
    { headers: { Authorization: `Bearer ${data.token}` }, tags: { name: 'media_upload' } });

  const body = res.body || '';
  const wasDeduped = body.includes('duplicate');

  if (wasDeduped) {
    dedupedUploadMs.add(res.timings.duration);
    dedupedCount.add(1);
  } else {
    firstUploadMs.add(res.timings.duration);
    storedCount.add(1);
  }

  check(res, {
    'status is 200': (r) => r.status === 200,
    'upload succeeded': () => body.includes('Upload successful'),
  });
}
