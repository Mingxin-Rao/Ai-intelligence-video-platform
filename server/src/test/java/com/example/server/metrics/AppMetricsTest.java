package com.example.server.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Metric names and tags are a contract: the Prometheus alert rules and the
 * Grafana panels reference them by string. Renaming a meter silently breaks
 * every alert that depends on it, with no compiler error to catch it — so the
 * names are asserted here.
 */
class AppMetricsTest {

    private MeterRegistry registry;
    private AppMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AppMetrics(registry);
    }

    @Test
    @DisplayName("All meters exist from startup, at zero, before anything happens")
    void allMetersAreRegisteredEagerly() {
        // A lazily-registered counter yields no time series, and a Prometheus
        // alert dividing by it evaluates to "no data" rather than 0 — so it never
        // fires. Eager registration is what makes the ratio alerts work.
        assertThat(registry.get("dovideo.ai.retry").counter().count()).isZero();
        assertThat(registry.get("dovideo.ratelimit.rejected").counter().count()).isZero();
        assertThat(registry.get("dovideo.lock.contention").counter().count()).isZero();
        assertThat(registry.get("dovideo.cache.access").tag("result", "hit").counter().count()).isZero();
        assertThat(registry.get("dovideo.cache.access").tag("result", "miss").counter().count()).isZero();
        assertThat(registry.get("dovideo.dedup.hit").tag("source", "file").counter().count()).isZero();
        assertThat(registry.get("dovideo.dedup.hit").tag("source", "link").counter().count()).isZero();
        assertThat(registry.get("dovideo.media.failure").tag("reason", "no_audio").counter().count()).isZero();
        assertThat(registry.get("dovideo.ai.task").tag("outcome", "success").timer().count()).isZero();
        assertThat(registry.get("dovideo.ai.task").tag("outcome", "failure").timer().count()).isZero();
    }

    @Test
    @DisplayName("An unknown tag value is ignored rather than creating a rogue series")
    void unknownTagValueIsIgnored() {
        metrics.recordMediaFailure("something_unexpected");
        metrics.recordDedupHit("ftp");

        // Cardinality stays bounded: only the declared reasons exist as series
        assertThat(registry.find("dovideo.media.failure").tag("reason", "something_unexpected").counter()).isNull();
        assertThat(registry.find("dovideo.dedup.hit").tag("source", "ftp").counter()).isNull();
    }

    @Test
    @DisplayName("AI task duration is recorded and tagged by outcome")
    void recordsTaskDurationTaggedByOutcome() {
        metrics.recordAiTask(AppMetrics.OUTCOME_SUCCESS, Duration.ofSeconds(12));
        metrics.recordAiTask(AppMetrics.OUTCOME_SUCCESS, Duration.ofSeconds(8));
        metrics.recordAiTask(AppMetrics.OUTCOME_FAILURE, Duration.ofSeconds(3));

        var success = registry.get("dovideo.ai.task").tag("outcome", "success").timer();
        var failure = registry.get("dovideo.ai.task").tag("outcome", "failure").timer();

        // Count doubles as the success/failure tally the failure-rate alert divides
        assertThat(success.count()).isEqualTo(2);
        assertThat(failure.count()).isEqualTo(1);
        assertThat(success.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(20.0);
    }

    @Test
    @DisplayName("Retries, rate-limit rejections and lock contention are counted separately")
    void countsOperationalSignalsSeparately() {
        metrics.recordAiRetry();
        metrics.recordAiRetry();
        metrics.recordRateLimited();
        metrics.recordLockContention();

        assertThat(registry.get("dovideo.ai.retry").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("dovideo.ratelimit.rejected").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("dovideo.lock.contention").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Dedup hits are split by upload source")
    void splitsDedupHitsBySource() {
        metrics.recordDedupHit(AppMetrics.SOURCE_FILE);
        metrics.recordDedupHit(AppMetrics.SOURCE_LINK);
        metrics.recordDedupHit(AppMetrics.SOURCE_LINK);

        assertThat(registry.get("dovideo.dedup.hit").tag("source", "file").counter().count())
                .isEqualTo(1.0);
        // Link dedup is the more valuable one: it also avoids the download
        assertThat(registry.get("dovideo.dedup.hit").tag("source", "link").counter().count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("Cache accesses are split into hit and miss so a ratio can be derived")
    void splitsCacheAccessIntoHitAndMiss() {
        metrics.recordCacheAccess(true);
        metrics.recordCacheAccess(true);
        metrics.recordCacheAccess(false);

        assertThat(registry.get("dovideo.cache.access").tag("result", "hit").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("dovideo.cache.access").tag("result", "miss").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Failure reasons stay distinguishable (user input vs outage)")
    void keepsFailureReasonsDistinct() {
        metrics.recordMediaFailure(AppMetrics.REASON_NO_AUDIO);
        metrics.recordMediaFailure(AppMetrics.REASON_EXTRACTION);
        metrics.recordMediaFailure(AppMetrics.REASON_AI_ERROR);

        // A silent upload is not an incident; FFmpeg dying repeatedly is. Alerting
        // on an undifferentiated failure counter would page on the former.
        assertThat(registry.get("dovideo.media.failure").tag("reason", "no_audio").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("dovideo.media.failure").tag("reason", "extraction_failed").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("dovideo.media.failure").tag("reason", "ai_error").counter().count())
                .isEqualTo(1.0);
    }
}
