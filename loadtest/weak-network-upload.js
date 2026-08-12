// =============================================================================
// Weak-network comparison: monolithic upload vs resumable chunked upload.
//
// Failure model — Toxiproxy `limit_data`, which severs any connection once it has
// carried more than N bytes upstream. That is the phenomenon chunking exists to
// survive: on a degraded link, the longer a single transfer runs, the less likely
// it is to complete. It is not packet loss; it is "long transfers get cut", which
// is what actually breaks a multi-GB PUT.
//
// With the threshold set between the chunk size and the file size, a monolithic
// PUT can never finish while any individual chunk always can. Both arms get the
// same retry budget, so the difference measured is resumability, not persistence.
//
// Setup (see loadtest/RESULTS.md for the exact commands):
//   proxy 9099 -> app:9090, toxic limit_data bytes=8388608 (8 MiB), toxicity 1.0
//   payload 20 MB, chunk size 5 MiB => 4 chunks
//
// Run:
//   BASE_URL=http://localhost:9099 k6 run loadtest/weak-network-upload.js
//
// Note: the server treats fileMd5 as an opaque key and does not verify it against
// the bytes, so this test passes a unique id per iteration. That keeps each
// iteration a genuinely new upload rather than a dedup hit — dedup is measured
// separately in upload-dedup.js.
// =============================================================================
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:9099';
const payload = open('./assets/large.bin', 'b');

const CHUNK_SIZE = 5 * 1024 * 1024;
const RETRY_BUDGET = 4;

const monolithicSuccess = new Rate('monolithic_success');
const chunkedSuccess = new Rate('chunked_success');
const monolithicBytes = new Counter('monolithic_bytes_sent');
const chunkedBytes = new Counter('chunked_bytes_sent');
const chunkedDuration = new Trend('chunked_duration_ms', true);
const monolithicDuration = new Trend('monolithic_duration_ms', true);

export const options = {
  scenarios: {
    monolithic: {
      executor: 'per-vu-iterations', vus: 1, iterations: 10,
      exec: 'monolithicUpload', startTime: '0s',
    },
    chunked: {
      executor: 'per-vu-iterations', vus: 1, iterations: 10,
      exec: 'chunkedUpload', startTime: '90s',
    },
  },
  // Each request gets its own connection, so the byte budget applies per transfer
  // attempt rather than accumulating across a keep-alive session.
  noConnectionReuse: true,
  thresholds: {
    // Resumable uploads must survive a link that monolithic ones cannot
    'chunked_success': ['rate>0.9'],
  },
};

export function setup() {
  const email = `weaknet_${Date.now()}@example.com`;
  const password = 'LoadTest!2345';
  const headers = { 'Content-Type': 'application/json' };
  // Registration goes through the same degraded proxy but is tiny, so it survives
  const reg = http.post(`${BASE}/user/register`,
    JSON.stringify({ email, password, nickname: 'weaknet' }), { headers });
  if (reg.status !== 200) throw new Error(`register failed: ${reg.status} ${reg.body}`);
  const login = http.post(`${BASE}/user/login`, JSON.stringify({ email, password }), { headers });
  const token = login.json('token');
  if (!token) throw new Error(`login returned no token: ${login.body}`);
  return { token };
}

// ---------------------------------------------------------------------------
// Arm A: one PUT of the whole file, restarting from zero on failure.
// ---------------------------------------------------------------------------
export function monolithicUpload(data) {
  const auth = { Authorization: `Bearer ${data.token}` };
  const started = Date.now();
  let ok = false;

  for (let attempt = 0; attempt < RETRY_BUDGET && !ok; attempt++) {
    const res = http.post(`${BASE}/media/upload`,
      { file: http.file(payload, `${uuidv4()}.bin`, 'application/octet-stream') },
      { headers: auth, tags: { name: 'monolithic' } });
    // Every attempt re-sends the entire file, whether or not it got close last time
    monolithicBytes.add(payload.byteLength);
    ok = res.status === 200 && (res.body || '').includes('Upload successful');
  }

  monolithicSuccess.add(ok);
  monolithicDuration.add(Date.now() - started);
}

// ---------------------------------------------------------------------------
// Arm B: init -> chunks -> merge, re-sending only the chunks that did not land.
// ---------------------------------------------------------------------------
export function chunkedUpload(data) {
  const auth = { Authorization: `Bearer ${data.token}` };
  const fileMd5 = uuidv4().replace(/-/g, '');
  const started = Date.now();

  const initBody = { fileMd5, totalSize: String(payload.byteLength) };
  const initRes = http.post(`${BASE}/media/upload/init`, initBody, { headers: auth });
  if (initRes.status !== 200) { chunkedSuccess.add(false); return; }

  const init = initRes.json();
  const totalChunks = init.totalChunks;
  let missing = init.missingChunks || [];

  for (let pass = 0; pass < RETRY_BUDGET && missing.length > 0; pass++) {
    const stillMissing = [];
    for (const index of missing) {
      const start = index * CHUNK_SIZE;
      const end = Math.min(start + CHUNK_SIZE, payload.byteLength);
      const slice = payload.slice(start, end);

      const res = http.post(`${BASE}/media/upload/chunk`,
        {
          file: http.file(slice, `${fileMd5}-${index}`, 'application/octet-stream'),
          fileMd5: fileMd5,
          chunkIndex: String(index),
        },
        { headers: auth, tags: { name: 'chunk' } });

      // Only the bytes actually attempted are counted, which is the whole point:
      // a lost chunk costs one chunk, not the file.
      chunkedBytes.add(end - start);
      const landed = res.status === 200 && (res.json() || {}).status === 'OK';
      if (!landed) stillMissing.push(index);
    }
    missing = stillMissing;
  }

  let ok = false;
  if (missing.length === 0) {
    const mergeRes = http.post(`${BASE}/media/upload/merge`,
      { fileMd5, fileName: `${fileMd5}.bin`, totalChunks: String(totalChunks) },
      { headers: auth, tags: { name: 'merge' } });
    ok = mergeRes.status === 200 && ['COMPLETE', 'INSTANT'].includes((mergeRes.json() || {}).status);
  }

  chunkedSuccess.add(ok);
  chunkedDuration.add(Date.now() - started);
}
