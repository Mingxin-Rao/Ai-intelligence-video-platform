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

## 4. Async dispatch vs synchronous equivalent

The claim this measures: moving transcoding and the LLM call off the request path
turns a long wait into an immediate response.

Measured directly, both sides from real runs — the dispatch RT with `curl`, and
the "how long would the user have waited synchronously" side from the app's own
`dovideo_ai_task_seconds` timer, which covers FFmpeg extraction plus the provider
round-trip plus persistence. No synthetic baseline endpoint was built; the timer
*is* the synchronous cost.

| Input | Work duration (sync equivalent) | Dispatch RT (async) |
|---|---|---|
| 60s video, 640x480, 852 KB | **7.4 s** avg over 3 runs (max 7.9 s) | 81 ms, 157 ms (417 ms cold) |
| 5 min video, 720p, 5.4 MB | **12.3 s** | **73 ms** |

**~100-170x faster response.** Five times the video length cost only 1.7x the
work time, so duration is dominated by the provider round-trip rather than by
FFmpeg or by input size.

Not reproduced: the previously quoted ~60 s synchronous baseline. On this setup
the synchronous cost of a one-minute clip is 7.4 s, not 60 s. A real video with
speech would plausibly cost the model more thinking time than a synthetic tone
does, but that has not been measured, so the defensible figure here is the one
above.

The dispatch endpoint does around seven network round-trips before returning
(Redisson lock, rate limiter, MySQL read, MySQL write, cache invalidation, MQ
publish, unlock), which is why it lands near 80 ms rather than in single-digit
milliseconds on Docker Desktop for macOS.

### Two defects this measurement exposed

Neither was visible from reading the code:

1. `brokerIP1 = 127.0.0.1` in `rocketmq/broker.conf` was correct while the app ran
   on the host, but the broker advertises that address to the NameServer and
   clients then dial it directly — so from inside the app container it resolved to
   the app itself. Every publish failed with "No route info of this topic", i.e.
   the async path had been broken since containerization, and nothing failed at
   startup to say so.
2. The `[MQ] Queued` placeholder was written before publishing and never rolled
   back, so one transient broker error left the row carrying the marker forever
   and the idempotency check rejected every retry as "already running" — a
   permanently unretryable task.

---

## 5. Weak network: monolithic upload vs resumable chunked upload

### Failure model

Toxiproxy `limit_data`, which severs a connection once it has carried more than a
set number of bytes upstream. This is deliberately *not* packet loss — it models
the thing that actually breaks large uploads: the longer a single transfer runs on
a degraded link, the less likely it is to finish. With the threshold between the
chunk size and the file size, a monolithic PUT cannot complete while any single
chunk always can.

```bash
docker compose up -d toxiproxy
curl -X POST http://localhost:8474/proxies -H 'Content-Type: application/json' \
  -d '{"name":"app","listen":"0.0.0.0:9099","upstream":"app:9090","enabled":true}'
curl -X POST http://localhost:8474/proxies/app/toxics -H 'Content-Type: application/json' \
  -d '{"name":"cut_long_transfers","type":"limit_data","stream":"upstream",
       "toxicity":1.0,"attributes":{"bytes":8388608}}'

BASE_URL=http://localhost:9099 k6 run loadtest/weak-network-upload.js
```

Parameters: 20 MB payload, 5 MiB chunks (4 chunks), 8 MiB connection budget,
`noConnectionReuse` so the budget applies per transfer attempt, retry budget of 4
for **both** arms — so what is measured is resumability, not persistence.

### Results (10 uploads per arm)

| | Monolithic | Chunked + resume |
|---|---|---|
| **Success rate** | **0%** (0/10) | **100%** (10/10) |
| Bytes sent | **838,860,800** (800 MB) | **209,715,200** (200 MB) |
| Avg duration | 739 ms | 636 ms |

Two things to read here. The success rate is the headline: **0% → 100%**. But the
bytes tell the more interesting story — the monolithic arm pushed **800 MB to
deliver nothing**, because every one of its 40 attempts re-sent the whole file
before being cut. The chunked arm sent exactly 200 MB, i.e. 20 MB × 10 uploads
with **zero retransmission**: every chunk fit under the budget, so nothing had to
be sent twice.

The 0% is deterministic by construction (toxicity 1.0 means *every* connection
over 8 MiB is cut), which is the point — it isolates the mechanism rather than
producing a noisy probability. A partial toxicity would land somewhere between.

### End-to-end resume, verified by hand

```
init                      -> missing [0, 1, 2, 3]
upload only chunks 0, 2   (simulating an interrupted transfer)
init again                -> uploaded [0, 2], missing [1, 3]   <- resumes, does not restart
merge early               -> INCOMPLETE, missing [1, 3]        <- refuses to compose a gapped object
upload 1, 3; merge        -> COMPLETE
```

The second `init` is the whole feature: state is keyed by uploader + content hash
in a Redis Set, so it survives a page reload and a client restart, and re-sending
an already-delivered chunk is absorbed by the Set rather than corrupting the count.

### Known gap

The server treats `fileMd5` as an opaque key and never verifies it against the
bytes received. A client could therefore claim a hash that does not match its
content and poison its own dedup entry. Not exploitable across users (the key is
scoped per user), but it means dedup trusts the client — worth closing by hashing
server-side during merge.

---

## Not measured yet
- **Multi-instance behaviour.** Everything above is one app instance; the
  horizontal-scaling design (stateless API, shared Redis locks, MQ consumers) is
  argued but not load-verified.
- **RocketMQ consumer throughput / backlog drain rate.** The
  `AiThreadPoolQueueBacklog` alert is wired but has not been driven into firing.
