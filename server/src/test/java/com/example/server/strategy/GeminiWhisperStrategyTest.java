package com.example.server.strategy;

import com.example.server.metrics.AppMetrics;
import com.example.server.strategy.impl.GeminiWhisperStrategy;
import com.example.server.utils.GeminiUtils;
import com.example.server.utils.OpenAiWhisperUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Only the input guards are covered here — everything past them shells out to
 * FFmpeg, which belongs in an integration test rather than a unit test that would
 * then depend on FFmpeg being installed on whatever machine runs it.
 *
 * The guards are worth pinning down because they are what stops a bad path from
 * reaching a subprocess, and because they must report failure as a ❌ string:
 * AiService's retry loop keys on that marker.
 */
@ExtendWith(MockitoExtension.class)
class GeminiWhisperStrategyTest {

    @Mock
    private OpenAiWhisperUtils whisperUtils;
    @Mock
    private GeminiUtils geminiUtils;
    @Mock
    private AppMetrics metrics;

    @InjectMocks
    private GeminiWhisperStrategy strategy;

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("A null or empty path is refused before spawning anything")
    void nullOrEmptyPathIsRefused(String path) {
        String result = strategy.transcribe(path);

        assertThat(result).startsWith("❌");
        // Neither provider may be called with nothing to transcribe
        verify(whisperUtils, never()).transcribe(anyString());
    }

    @Test
    @DisplayName("A local path that does not exist is refused before spawning FFmpeg")
    void missingLocalFileIsRefused(@TempDir Path tempDir) {
        String absent = tempDir.resolve("not-there.mp4").toString();

        String result = strategy.transcribe(absent);

        assertThat(result).startsWith("❌").contains("not found");
        verify(whisperUtils, never()).transcribe(anyString());
    }

    @Test
    @DisplayName("An http path skips the local-existence check (FFmpeg reads URLs itself)")
    void httpPathSkipsLocalExistenceCheck() {
        // Pointing at a closed port: the guard must let this through to FFmpeg,
        // which then fails on its own — proving the check was skipped rather than
        // the path being rejected as "not found on disk".
        String result = strategy.transcribe("http://127.0.0.1:1/nope.mp4");

        assertThat(result).doesNotContain("not found on disk");
    }
}
