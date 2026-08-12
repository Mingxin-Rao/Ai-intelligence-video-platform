package com.example.server.utils;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercised against a local stub server rather than the real provider: no network
 * access, no API spend, and the error branches (429, 500, malformed body) can
 * actually be reached, which is impossible to do reliably against a live API.
 *
 * The contract being pinned down is that every failure path returns a ❌-prefixed
 * string rather than throwing — AiService keys its retry loop on that marker, so
 * an exception escaping here would break retries instead of triggering them.
 */
class OpenAiWhisperUtilsTest {

    private MockWebServer server;
    private OpenAiWhisperUtils whisper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        whisper = new OpenAiWhisperUtils();
        ReflectionTestUtils.setField(whisper, "apiKey", "sk-test-key");
        ReflectionTestUtils.setField(whisper, "baseUrl", server.url("/v1").toString());
        ReflectionTestUtils.setField(whisper, "model", "whisper-1");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private String audioFile() throws IOException {
        Path mp3 = tempDir.resolve("clip.mp3");
        Files.write(mp3, "not really mp3 bytes".getBytes());
        return mp3.toString();
    }

    @Test
    @DisplayName("verbose_json segments become timestamped lines")
    void segmentsBecomeTimestampedLines() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {"text":"full text",
                 "segments":[
                   {"start":0.0,   "text":" Hello there"},
                   {"start":75.4,  "text":" Later line"}
                 ]}
                """));

        String result = whisper.transcribe(audioFile());

        // 75.4s -> 01:15, and leading whitespace from the provider is trimmed
        assertThat(result).contains("[00:00] Hello there");
        assertThat(result).contains("[01:15] Later line");
    }

    @Test
    @DisplayName("The request carries the key, model and multipart file")
    void requestIsBuiltCorrectly() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"text\":\"hi\"}"));

        whisper.transcribe(audioFile());

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        // Trailing slashes in the configured base URL must not double up
        assertThat(recorded.getPath()).isEqualTo("/v1/audio/transcriptions");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-test-key");
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("whisper-1");
        // verbose_json is what produces the timestamps above
        assertThat(body).contains("verbose_json");
        assertThat(body).contains("clip.mp3");
    }

    @Test
    @DisplayName("A trailing slash on the base URL is normalised")
    void trailingSlashInBaseUrlIsNormalised() throws Exception {
        ReflectionTestUtils.setField(whisper, "baseUrl", server.url("/v1///").toString());
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"text\":\"hi\"}"));

        whisper.transcribe(audioFile());

        assertThat(server.takeRequest().getPath()).isEqualTo("/v1/audio/transcriptions");
    }

    @Test
    @DisplayName("A response with no segments falls back to the plain text field")
    void fallsBackToPlainText() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"text\":\"  just text  \"}"));

        assertThat(whisper.transcribe(audioFile())).isEqualTo("just text");
    }

    @Test
    @DisplayName("Segments that are all blank fall back to the plain text field")
    void blankSegmentsFallBackToPlainText() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"text\":\"fallback\",\"segments\":[{\"start\":0.0,\"text\":\"   \"}]}"));

        assertThat(whisper.transcribe(audioFile())).isEqualTo("fallback");
    }

    @Test
    @DisplayName("An empty transcript is reported as an error, not as empty success")
    void emptyTranscriptIsAnError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"text\":\"\"}"));

        assertThat(whisper.transcribe(audioFile())).startsWith("❌");
    }

    @Test
    @DisplayName("An HTTP error is returned as a ❌ string carrying the status code")
    void httpErrorIsReportedNotThrown() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":\"rate limit\"}"));

        String result = whisper.transcribe(audioFile());

        // Must not throw: AiService retries on the ❌ marker, not on exceptions
        assertThat(result).startsWith("❌").contains("429");
    }

    @Test
    @DisplayName("A malformed body is reported rather than throwing")
    void malformedBodyIsReported() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("this is not json"));

        assertThat(whisper.transcribe(audioFile())).startsWith("❌");
    }

    @Test
    @DisplayName("A missing key short-circuits before any request is made")
    void missingKeyShortCircuits() throws Exception {
        ReflectionTestUtils.setField(whisper, "apiKey", "");

        String result = whisper.transcribe(audioFile());

        assertThat(result).startsWith("❌").contains("not configured");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("A missing audio file short-circuits before any request is made")
    void missingFileShortCircuits() {
        String result = whisper.transcribe(tempDir.resolve("absent.mp3").toString());

        assertThat(result).startsWith("❌").contains("not found");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("A file over the provider's 25MB limit is refused locally")
    void oversizedFileIsRefusedLocally() throws Exception {
        Path big = tempDir.resolve("big.mp3");
        Files.write(big, new byte[26 * 1024 * 1024]);

        String result = whisper.transcribe(big.toString());

        // Refused before upload: no point spending two minutes sending 26MB to be rejected
        assertThat(result).startsWith("❌").contains("25MB");
        assertThat(server.getRequestCount()).isZero();
    }
}
