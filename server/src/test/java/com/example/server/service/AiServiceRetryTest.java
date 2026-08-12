package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.metrics.AppMetrics;
import com.example.server.strategy.AiAnalysisStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retry/backoff behaviour of the async AI pipeline, with the real AI provider
 * mocked out — no network, no API spend.
 *
 * Note: exercising the real backoff means these tests sleep for the same 1s + 2s
 * the production path does (~4s total). That is deliberate: stubbing out the
 * sleep would stop the test from proving the retry loop actually runs.
 */
@ExtendWith(MockitoExtension.class)
class AiServiceRetryTest {

    @Mock
    private MediaFileMapper mediaFileMapper;

    @Mock
    private AiAnalysisStrategy aiAnalysisStrategy;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private AppMetrics metrics;

    @InjectMocks
    private AiService aiService;

    private MediaFile mediaFile(long id) {
        MediaFile f = new MediaFile();
        f.setId(id);
        f.setUserId(7L);
        f.setFilePath("http://minio:9000/media/abc.mp4");
        f.setStatus("COMPLETED");
        return f;
    }

    @Test
    @DisplayName("Transient AI failures are retried up to 3 times before giving up")
    void retriesThreeTimesThenGivesUp() {
        MediaFile file = mediaFile(1L);
        when(mediaFileMapper.selectById(1L)).thenReturn(file);
        // The strategy signals failure with a ❌-prefixed string (not an exception)
        when(aiAnalysisStrategy.generateSummary(anyString())).thenReturn("❌ upstream 503");

        aiService.asyncAnalyze(1L);

        verify(aiAnalysisStrategy, times(3)).generateSummary(anyString());
        // The media row stays usable so the user can retry from the UI
        assertThat(file.getStatus()).isEqualTo("COMPLETED");
        assertThat(file.getAiSummary()).startsWith("❌");
        verify(mediaFileMapper).updateById(file);
    }

    @Test
    @DisplayName("A retry that succeeds stops early and persists the good summary")
    void stopsRetryingOnceSuccessful() {
        MediaFile file = mediaFile(2L);
        when(mediaFileMapper.selectById(2L)).thenReturn(file);
        when(aiAnalysisStrategy.generateSummary(anyString()))
                .thenReturn("❌ transient network blip")
                .thenReturn("This video explains distributed locks.");

        aiService.asyncAnalyze(2L);

        // Exactly two calls: no wasted third attempt (each attempt costs API spend)
        verify(aiAnalysisStrategy, times(2)).generateSummary(anyString());
        assertThat(file.getAiSummary()).isEqualTo("This video explains distributed locks.");
    }

    @Test
    @DisplayName("The list cache is invalidated after analysis so polling sees the result")
    void invalidatesCacheSoFrontendPollingSeesResult() {
        MediaFile file = mediaFile(3L);
        when(mediaFileMapper.selectById(3L)).thenReturn(file);
        when(aiAnalysisStrategy.generateSummary(anyString())).thenReturn("summary text");

        aiService.asyncAnalyze(3L);

        // Without this delete the cached list would keep serving the "queued"
        // placeholder for up to 30 minutes and the UI would spin forever.
        verify(redisTemplate).delete("media:list:user:7");
    }

    @Test
    @DisplayName("A missing media row is a no-op, not a crash")
    void missingMediaRowIsNoOp() {
        when(mediaFileMapper.selectById(404L)).thenReturn(null);

        aiService.asyncAnalyze(404L);

        verify(aiAnalysisStrategy, never()).generateSummary(anyString());
        verify(mediaFileMapper, never()).updateById(org.mockito.ArgumentMatchers.any(MediaFile.class));
    }
}
