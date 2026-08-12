package com.example.server.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Business metrics, kept in one place so metric names and tags cannot drift
 * apart across call sites.
 *
 * The generic JVM/HTTP metrics Actuator ships with say nothing about whether
 * this system is actually working: once a task is handed to RocketMQ it leaves
 * the HTTP request entirely, so a failing consumer looks like a healthy server
 * while users watch a spinner forever. These meters exist to answer the
 * questions that matter operationally — are tasks succeeding, how long do they
 * take, are we being throttled, is the cache earning its keep.
 *
 * Every meter is registered eagerly in the constructor rather than on first use.
 * Micrometer registers lazily by default, which means a counter that has not
 * fired yet produces no time series at all — and a Prometheus alert that divides
 * by it silently evaluates to "no data" instead of zero, so it never fires. The
 * ratio alerts in observability/alerts.yml depend on these existing from startup.
 */
@Component
public class AppMetrics {

    // Tag values are a closed set: unbounded tag values would blow up cardinality.
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";

    public static final String SOURCE_FILE = "file";
    public static final String SOURCE_LINK = "link";

    public static final String REASON_NO_AUDIO = "no_audio";
    public static final String REASON_EXTRACTION = "extraction_failed";
    public static final String REASON_AI_ERROR = "ai_error";

    private static final String AI_TASK = "dovideo.ai.task";
    private static final String DEDUP_HIT = "dovideo.dedup.hit";
    private static final String CACHE_ACCESS = "dovideo.cache.access";
    private static final String MEDIA_FAILURE = "dovideo.media.failure";

    private final Map<String, Timer> aiTaskTimers;
    private final Counter aiRetry;
    private final Counter rateLimited;
    private final Counter lockContention;
    private final Map<String, Counter> dedupHits;
    private final Map<Boolean, Counter> cacheAccess;
    private final Map<String, Counter> mediaFailures;

    public AppMetrics(MeterRegistry registry) {
        this.aiTaskTimers = Map.of(
                OUTCOME_SUCCESS, aiTaskTimer(registry, OUTCOME_SUCCESS),
                OUTCOME_FAILURE, aiTaskTimer(registry, OUTCOME_FAILURE));

        this.aiRetry = Counter.builder("dovideo.ai.retry")
                .description("Retry attempts against the AI provider")
                .register(registry);
        this.rateLimited = Counter.builder("dovideo.ratelimit.rejected")
                .description("Submissions rejected by the rate limiter")
                .register(registry);
        this.lockContention = Counter.builder("dovideo.lock.contention")
                .description("Duplicate submissions blocked by the distributed lock")
                .register(registry);

        this.dedupHits = Map.of(
                SOURCE_FILE, dedupCounter(registry, SOURCE_FILE),
                SOURCE_LINK, dedupCounter(registry, SOURCE_LINK));

        this.cacheAccess = Map.of(
                Boolean.TRUE, cacheCounter(registry, "hit"),
                Boolean.FALSE, cacheCounter(registry, "miss"));

        this.mediaFailures = Map.of(
                REASON_NO_AUDIO, failureCounter(registry, REASON_NO_AUDIO),
                REASON_EXTRACTION, failureCounter(registry, REASON_EXTRACTION),
                REASON_AI_ERROR, failureCounter(registry, REASON_AI_ERROR));
    }

    /**
     * End-to-end duration of one analysis task, tagged by outcome. The timer's
     * count doubles as the success/failure tally, so no separate counter is needed.
     * Drives both the failure-rate and the P95-latency alerts.
     */
    public void recordAiTask(String outcome, Duration duration) {
        Timer timer = aiTaskTimers.get(outcome);
        if (timer != null) {
            timer.record(duration);
        }
    }

    /** A retry attempt against the AI provider — a proxy for upstream instability. */
    public void recordAiRetry() {
        aiRetry.increment();
    }

    /** A submission rejected by the global rate limiter (i.e. cost ceiling hit). */
    public void recordRateLimited() {
        rateLimited.increment();
    }

    /** A concurrent duplicate submission blocked by the distributed lock. */
    public void recordLockContention() {
        lockContention.increment();
    }

    /**
     * A duplicate upload short-circuited by dedup. Quantifies what dedup saves:
     * every hit is one avoided store plus one avoided paid AI analysis (and for
     * links, one avoided download).
     */
    public void recordDedupHit(String source) {
        Counter counter = dedupHits.get(source);
        if (counter != null) {
            counter.increment();
        }
    }

    /** Cache hit/miss on the media list — shows whether the Redis layer pays off. */
    public void recordCacheAccess(boolean hit) {
        cacheAccess.get(hit).increment();
    }

    /**
     * Why media processing failed. Separating "the video has no audio" from
     * "FFmpeg broke" is the difference between a user-input issue and an outage,
     * and stops the former from paging anyone.
     */
    public void recordMediaFailure(String reason) {
        Counter counter = mediaFailures.get(reason);
        if (counter != null) {
            counter.increment();
        }
    }

    private static Timer aiTaskTimer(MeterRegistry registry, String outcome) {
        return Timer.builder(AI_TASK)
                .description("AI analysis task duration and outcome")
                .tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    private static Counter dedupCounter(MeterRegistry registry, String source) {
        return Counter.builder(DEDUP_HIT)
                .description("Uploads short-circuited as duplicate content")
                .tag("source", source)
                .register(registry);
    }

    private static Counter cacheCounter(MeterRegistry registry, String result) {
        return Counter.builder(CACHE_ACCESS)
                .description("Media list cache accesses")
                .tag("result", result)
                .register(registry);
    }

    private static Counter failureCounter(MeterRegistry registry, String reason) {
        return Counter.builder(MEDIA_FAILURE)
                .description("Media processing failures by reason")
                .tag("reason", reason)
                .register(registry);
    }
}
