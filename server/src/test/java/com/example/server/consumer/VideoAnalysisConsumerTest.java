package com.example.server.consumer;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The MQ listener's whole job is to hand work off and get out of the way: the
 * consumer thread must not run the multi-minute analysis itself, or RocketMQ stops
 * consuming while one video is transcoded.
 *
 * The executor is stubbed to run inline so the assertions are deterministic —
 * with the real pool the test would race the worker thread.
 */
@ExtendWith(MockitoExtension.class)
class VideoAnalysisConsumerTest {

    @Mock
    private AiService aiService;
    @Mock
    private MediaFileMapper mediaFileMapper;

    @InjectMocks
    private VideoAnalysisConsumer consumer;

    /** Runs submitted work on the calling thread, making the async hand-off observable. */
    private final Executor inlineExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils
                .setField(consumer, "aiTaskExecutor", inlineExecutor);
    }

    @Test
    @DisplayName("A message dispatches the analysis to the thread pool")
    void messageDispatchesAnalysis() {
        consumer.onMessage(new AnalysisTaskMsg(42L, "START_ANALYSIS"));

        verify(aiService).asyncAnalyze(42L);
    }

    @Test
    @DisplayName("A failure inside the task is recorded on the row, not rethrown at the listener")
    void failureIsRecordedNotRethrown() {
        doThrow(new RuntimeException("provider exploded")).when(aiService).asyncAnalyze(anyLong());
        MediaFile file = new MediaFile();
        file.setId(42L);
        when(mediaFileMapper.selectById(42L)).thenReturn(file);

        // Must not throw: an escaping exception would make RocketMQ redeliver the
        // message and re-run an expensive task that is going to fail again.
        consumer.onMessage(new AnalysisTaskMsg(42L, "START_ANALYSIS"));

        ArgumentCaptor<MediaFile> saved = ArgumentCaptor.forClass(MediaFile.class);
        verify(mediaFileMapper).updateById(saved.capture());
        // The user sees why it failed instead of an eternal spinner
        assertThat(saved.getValue().getAiSummary()).startsWith("❌").contains("provider exploded");
    }

    @Test
    @DisplayName("A failure for a row that no longer exists does not throw")
    void failureForDeletedRowDoesNotThrow() {
        doThrow(new RuntimeException("boom")).when(aiService).asyncAnalyze(anyLong());
        // The user may have deleted the media while the task was queued
        when(mediaFileMapper.selectById(99L)).thenReturn(null);

        consumer.onMessage(new AnalysisTaskMsg(99L, "START_ANALYSIS"));

        verify(mediaFileMapper).selectById(99L);
    }
}
