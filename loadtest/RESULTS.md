# Load test results

Measured on the containerized stack (`docker compose up -d`), app reached at
`http://localhost:${APP_PORT}`. Numbers below are from k6's own output, not
estimates. Re-run with the commands in each section to reproduce.

Environment: Apple Silicon (arm64), all services on one host in Docker, single
app instance. These are therefore *relative* results — they show how the system
behaves under concurrency and where the caching/dedup wins are, not what
dedicated production hardware would deliver.

---

## 1. Authenticated read path — `GET /media/list`

The endpoint the SPA polls every 3s while a task runs, and the reason the Redis
cache layer exists.

```bash
BASE_URL=http://localhost:9091 k6 run loadtest/read-path.js
```

Profile: ramp 0→10 VUs (20s), →50 VUs (30s), hold 50 VUs (20s), ramp down (10s).
Each VU sleeps 3s between requests, matching the real polling interval.

| Metric | Result |
|---|---|
| Requests | 765 |
| Failed | **0.00%** (0 / 765) |
| Throughput | 9.14 req/s |
| Latency p50 | **10.5 ms** |
| Latency p90 | 36.9 ms |
| Latency p95 | **86.2 ms** |
| Latency max | 1.24 s (first request, cold JIT + cache miss) |
| Peak concurrency | 50 VUs |

Thresholds (`http_req_failed < 1%`, `p95 < 500ms`) both passed.

### Cache effectiveness, read from the app's own meters afterwards

```
dovideo_cache_access_total{result="hit"}   762
dovideo_cache_access_total{result="miss"}    1
```

**Hit rate 99.87%** — 762 of 763 reads were served from Redis, so the polling
loop cost the database a single query for the whole run. This is the concrete
justification for the cache layer, and the same expression drives the
`MediaListCacheHitRateLow` alert.

---

## 2. Content dedup — `POST /media/upload`

Quantifies what dedup saves. One upload of a 45 KB clip, then 19 repeats of the
identical bytes. Triggers no AI work, so the run costs nothing in provider spend.

```bash
BASE_URL=http://localhost:9091 k6 run loadtest/upload-dedup.js
```

| Metric | Result |
|---|---|
| Stored (first upload) | **681.7 ms** |
| Deduped (repeats), avg | **10.8 ms** |
| Deduped, p95 | 18.6 ms |
| Repeats correctly deduped | **19 / 19** |
| Failed requests | 0.00% |

**A duplicate upload short-circuits ~63× faster (681.7 ms → 10.8 ms, −98.4%)**,
because it returns before touching MinIO or inserting a row. The saving that
matters most is not the latency though: each dedup hit also avoids one paid AI
analysis downstream.

Confirmed in the meters: `dovideo_dedup_hit_total{source="file"} 19`.

---

## 3. Observability pipeline

Verified end to end rather than assumed:

- 16 custom `dovideo_*` series are present **at zero from startup** (meters are
  registered eagerly — a lazily-registered counter yields no series at all, and
  the ratio alerts would evaluate to "no data" and never fire).
- Prometheus target `dovideo-app` reports `health=up`.
- 7 alert rules load and evaluate; `AppDown` went `pending` during an app restart
  and returned to `inactive` once the container was healthy again, which is the
  alerting path proving itself.
- PromQL over the scraped data reproduces the app-side numbers:
  `sum(dovideo_cache_access_total{result="hit"}) / sum(dovideo_cache_access_total)`
  → `0.9987`.

---

## Not measured yet

- **Async dispatch RT** (`GET /debug/ai`). The endpoint is capped at 10/min by
  the global rate limiter and each admitted request costs a real provider call,
  so it is deliberately left out of the automated runs.
- **Multi-instance behaviour.** Everything above is one app instance; the
  horizontal-scaling design (stateless API, shared Redis locks, MQ consumers) is
  argued but not load-verified.
- **RocketMQ consumer throughput / backlog drain rate.** The
  `AiThreadPoolQueueBacklog` alert is wired but has not been driven into firing.
