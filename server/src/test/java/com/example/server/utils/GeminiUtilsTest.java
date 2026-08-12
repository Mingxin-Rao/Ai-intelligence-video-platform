package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
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
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Driven against a local stub server: no network, no provider spend, and the
 * error branches are actually reachable.
 *
 * As with the Whisper client, the contract is that failures come back as
 * ❌-prefixed strings rather than exceptions — AiService's retry loop keys on
 * that marker, so throwing here would bypass retries entirely.
 */
class GeminiUtilsTest {

    private MockWebServer server;
    private GeminiUtils gemini;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        gemini = new GeminiUtils();
        ReflectionTestUtils.setField(gemini, "apiKey", "test-key");
        ReflectionTestUtils.setField(gemini, "modelName", "gemini-test-model");
        ReflectionTestUtils.setField(gemini, "baseUrl", server.url("/v1beta").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private String audioFile(String content) throws IOException {
        Path mp3 = tempDir.resolve("audio.mp3");
        Files.write(mp3, content.getBytes());
        return mp3.toString();
    }

    @Test
    @DisplayName("The summary text is extracted from the nested candidates structure")
    void extractsSummaryFromResponse() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {"candidates":[{"content":{"parts":[{"text":"This video explains distributed locks."}]}}]}
                """));

        String result = gemini.analyzeAudioDirectly(audioFile("audio-bytes"));

        assertThat(result).isEqualTo("This video explains distributed locks.");
    }

    @Test
    @DisplayName("The request posts the model, the prompt and the base64 audio inline")
    void requestCarriesModelPromptAndAudio() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}"));

        gemini.analyzeAudioDirectly(audioFile("audio-bytes"));

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        // Model and key both travel in the URL for this API
        assertThat(recorded.getPath())
                .isEqualTo("/v1beta/models/gemini-test-model:generateContent?key=test-key");

        JSONObject body = JSON.parseObject(recorded.getBody().readUtf8());
        var parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts");
        assertThat(parts.getJSONObject(0).getString("text")).contains("video content analyst");
        JSONObject inline = parts.getJSONObject(1).getJSONObject("inline_data");
        assertThat(inline.getString("mime_type")).isEqualTo("audio/mpeg");
        // The audio is inlined as base64 — decoding it back proves it was not truncated
        assertThat(new String(Base64.getDecoder().decode(inline.getString("data"))))
                .isEqualTo("audio-bytes");
    }

    @Test
    @DisplayName("A trailing slash on the base URL is normalised")
    void trailingSlashInBaseUrlIsNormalised() throws Exception {
        ReflectionTestUtils.setField(gemini, "baseUrl", server.url("/v1beta//").toString());
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}"));

        gemini.analyzeAudioDirectly(audioFile("x"));

        assertThat(server.takeRequest().getPath()).startsWith("/v1beta/models/");
    }

    @Test
    @DisplayName("An HTTP error is returned as a ❌ string, not thrown")
    void httpErrorIsReportedNotThrown() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"message\":\"API key not valid\"}}"));

        String result = gemini.analyzeAudioDirectly(audioFile("x"));

        assertThat(result).startsWith("❌").contains("API key not valid");
    }

    @Test
    @DisplayName("A response with no candidates is reported rather than throwing NPE")
    void emptyCandidatesIsReported() throws Exception {
        // Happens in practice when the model refuses or the audio yields nothing
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"candidates\":[]}"));

        assertThat(gemini.analyzeAudioDirectly(audioFile("x"))).startsWith("❌");
    }

    @Test
    @DisplayName("A malformed body is reported rather than throwing")
    void malformedBodyIsReported() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("not json at all"));

        assertThat(gemini.analyzeAudioDirectly(audioFile("x"))).startsWith("❌");
    }

    @Test
    @DisplayName("A missing audio file short-circuits before any request")
    void missingFileShortCircuits() {
        String result = gemini.analyzeAudioDirectly(tempDir.resolve("absent.mp3").toString());

        assertThat(result).startsWith("❌").contains("not found");
        assertThat(server.getRequestCount()).isZero();
    }
}
