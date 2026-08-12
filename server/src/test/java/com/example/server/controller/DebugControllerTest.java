package com.example.server.controller;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.interceptor.AuthInterceptor;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.metrics.AppMetrics;
import com.example.server.service.AiService;
import com.example.server.strategy.AiAnalysisStrategy;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dispatch endpoint is where cost control and correctness meet: every
 * admitted request eventually spends money at the AI provider, so the lock (no
 * double submission), the rate limiter (no runaway spend) and the ownership
 * check (no analysing someone else's video) all have to hold before a message is
 * ever published.
 *
 * Lenient strictness: each test drives one specific guard and returns early, so
 * the stubs for the later guards legitimately go unused.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DebugControllerTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;

    @Mock
    private MediaFileMapper mediaFileMapper;
    @Mock
    private AiAnalysisStrategy aiAnalysisStrategy;
    @Mock
    private AiService aiService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private AppMetrics metrics;
    @Mock
    private RLock lock;
    @Mock
    private RRateLimiter rateLimiter;

    @InjectMocks
    private DebugController debugController;

    private MockHttpServletRequest authedRequest(long uid) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthInterceptor.ATTR_UID, uid);
        return request;
    }

    private MediaFile media(long id, long ownerId) {
        MediaFile f = new MediaFile();
        f.setId(id);
        f.setUserId(ownerId);
        f.setFilePath("http://minio:9000/media/abc.mp4");
        return f;
    }

    /** Happy path through both guards, so each test only overrides what it needs. */
    private void allowLockAndRateLimit() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(redissonClient.getRateLimiter(anyString())).thenReturn(rateLimiter);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rateLimiter.tryAcquire(anyLong())).thenReturn(true);
    }

    @Nested
    @DisplayName("AI dispatch guards")
    class Dispatch {

        @Test
        @DisplayName("A valid request publishes exactly one message and returns immediately")
        void validRequestPublishesOneMessage() throws Exception {
            allowLockAndRateLimit();
            when(lock.isHeldByCurrentThread()).thenReturn(true);
            when(mediaFileMapper.selectById(10L)).thenReturn(media(10L, USER_ID));

            String result = debugController.aiAnalyze(10L, authedRequest(USER_ID));

            assertThat(result).contains("dispatched");
            // The payload the consumer keys on
            ArgumentCaptor<Object> msg = ArgumentCaptor.forClass(Object.class);
            verify(rocketMQTemplate).convertAndSend(eq("video-analysis-topic"), msg.capture());
            assertThat(((AnalysisTaskMsg) msg.getValue()).getMediaId()).isEqualTo(10L);
            // Placeholder status is what lets the UI render "processing"
            ArgumentCaptor<MediaFile> saved = ArgumentCaptor.forClass(MediaFile.class);
            verify(mediaFileMapper).updateById(saved.capture());
            assertThat(saved.getValue().getAiSummary()).contains("[MQ]");
            // ...and the cached list must be dropped or the UI keeps the stale row
            verify(redisTemplate).delete("media:list:user:1");
            // The lock is always released
            verify(lock).unlock();
        }

        @Test
        @DisplayName("A concurrent double-click is refused without publishing")
        void concurrentSubmissionIsRefused() throws Exception {
            when(redissonClient.getLock(anyString())).thenReturn(lock);
            // Someone else already holds the lock for this media id
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

            String result = debugController.aiAnalyze(10L, authedRequest(USER_ID));

            assertThat(result).contains("don't click again");
            verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
            verify(metrics).recordLockContention();
            // Not our lock, so we must not release it
            verify(lock, never()).unlock();
        }

        @Test
        @DisplayName("Exceeding the rate limit is refused without publishing")
        void rateLimitedRequestIsRefused() throws Exception {
            allowLockAndRateLimit();
            when(rateLimiter.tryAcquire(anyLong())).thenReturn(false);
            when(lock.isHeldByCurrentThread()).thenReturn(true);

            String result = debugController.aiAnalyze(10L, authedRequest(USER_ID));

            assertThat(result).contains("rate limited");
            // The whole point of the limiter: no message, so no provider spend
            verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
            verify(metrics).recordRateLimited();
            verify(lock).unlock();
        }

        @Test
        @DisplayName("Analysing another user's video is refused")
        void cannotAnalyseAnotherUsersVideo() throws Exception {
            allowLockAndRateLimit();
            when(lock.isHeldByCurrentThread()).thenReturn(true);
            when(mediaFileMapper.selectById(10L)).thenReturn(media(10L, OTHER_USER_ID));

            String result = debugController.aiAnalyze(10L, authedRequest(USER_ID));

            assertThat(result).contains("don't have access");
            verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("A missing media row is refused")
        void missingMediaIsRefused() throws Exception {
            allowLockAndRateLimit();
            when(lock.isHeldByCurrentThread()).thenReturn(true);
            when(mediaFileMapper.selectById(404L)).thenReturn(null);

            String result = debugController.aiAnalyze(404L, authedRequest(USER_ID));

            assertThat(result).contains("not found");
            verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("A task already queued is not resubmitted")
        void alreadyQueuedTaskIsNotResubmitted() throws Exception {
            allowLockAndRateLimit();
            when(lock.isHeldByCurrentThread()).thenReturn(true);
            MediaFile inFlight = media(10L, USER_ID);
            // The [MQ] marker survives across requests, so idempotency holds even
            // after the lock has been released by the first submission.
            inFlight.setAiSummary("⏳ [MQ] Queued — waiting to be scheduled...");
            when(mediaFileMapper.selectById(10L)).thenReturn(inFlight);

            String result = debugController.aiAnalyze(10L, authedRequest(USER_ID));

            assertThat(result).contains("already running");
            verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }
    }

    @Nested
    @DisplayName("Transcript endpoint")
    class Transcribe {

        @Test
        @DisplayName("Own media starts an async transcription")
        void ownMediaStartsTranscription() {
            when(mediaFileMapper.selectById(20L)).thenReturn(media(20L, USER_ID));

            String result = debugController.transcribe(20L, authedRequest(USER_ID));

            assertThat(result).contains("running in the background");
            verify(aiService).asyncTranscribe(20L);
        }

        @Test
        @DisplayName("Another user's media does not start any work")
        void otherUsersMediaIsRefused() {
            when(mediaFileMapper.selectById(20L)).thenReturn(media(20L, OTHER_USER_ID));

            String result = debugController.transcribe(20L, authedRequest(USER_ID));

            assertThat(result).contains("don't have access");
            verify(aiService, never()).asyncTranscribe(anyLong());
        }
    }

    @Nested
    @DisplayName("Download endpoint")
    class Download {

        @Test
        @DisplayName("Another user's media is refused with 403, not a file")
        void otherUsersMediaIsForbidden() throws Exception {
            when(mediaFileMapper.selectById(30L)).thenReturn(media(30L, OTHER_USER_ID));

            var response = debugController.download(30L, authedRequest(USER_ID));

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            assertThat(response.getBody()).isNull();
        }

        @Test
        @DisplayName("A row with no stored path is a 404 rather than an FFmpeg attempt")
        void missingPathIsNotFound() throws Exception {
            MediaFile noPath = media(31L, USER_ID);
            noPath.setFilePath(null);
            when(mediaFileMapper.selectById(31L)).thenReturn(noPath);

            var response = debugController.download(31L, authedRequest(USER_ID));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("A local path that does not exist is a 404")
        void missingLocalFileIsNotFound() throws Exception {
            MediaFile localMissing = media(32L, USER_ID);
            localMissing.setFilePath("/definitely/not/here.mp4");
            when(mediaFileMapper.selectById(32L)).thenReturn(localMissing);

            var response = debugController.download(32L, authedRequest(USER_ID));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }
}
