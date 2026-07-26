package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * Converts audio to a structured transcript via OpenAI's speech-to-text (Whisper) API.
 *
 * Note: this calls the REST endpoint directly with OkHttp (same pattern as the Gemini call),
 * so no new dependency is required. Swap the base URL to use any OpenAI-compatible provider.
 */
@Component
public class OpenAiWhisperUtils {

    @Value("${ai.openai.api-key:}")
    private String apiKey;

    @Value("${ai.openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${ai.openai.transcribe-model:whisper-1}")
    private String model;

    // OpenAI's hard 25MB per-file limit
    private static final long MAX_BYTES = 25L * 1024 * 1024;

    // Transcription can be slow; give the read a generous timeout
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build();

    /**
     * Transcribe a local audio file into timestamped, structured text.
     * On failure returns a string starting with ❌ (the frontend uses that marker to detect errors).
     */
    public String transcribe(String audioFilePath) {
        if (apiKey == null || apiKey.isBlank()) {
            return "❌ OpenAI API key not configured (set ai.openai.api-key in application.properties)";
        }

        File mp3 = new File(audioFilePath);
        if (!mp3.exists()) return "❌ Audio file not found: " + audioFilePath;
        if (mp3.length() > MAX_BYTES) {
            return "❌ Audio is " + (mp3.length() / 1024 / 1024) + "MB, over OpenAI's 25MB limit. Try a shorter video.";
        }

        try {
            byte[] audioBytes = Files.readAllBytes(mp3.toPath());
            // Build the multipart form: file + model + response_format
            RequestBody fileBody = RequestBody.create(audioBytes, MediaType.parse("audio/mpeg"));
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", mp3.getName(), fileBody)
                    .addFormDataPart("model", model)
                    // verbose_json returns segments + timestamps, used to build structured text
                    .addFormDataPart("response_format", "verbose_json")
                    .build();

            Request request = new Request.Builder()
                    .url(baseUrl.replaceAll("/+$", "") + "/audio/transcriptions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String resBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    return "❌ Transcription failed (" + response.code() + "): " + resBody;
                }
                return formatTranscript(resBody);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "❌ Transcription error: " + e.getMessage();
        }
    }

    /**
     * Turn verbose_json segments into "[mm:ss] text" lines; fall back to plain text if there are none.
     */
    private String formatTranscript(String resBody) {
        JSONObject json = JSON.parseObject(resBody);
        JSONArray segments = json.getJSONArray("segments");

        if (segments == null || segments.isEmpty()) {
            String text = json.getString("text");
            return (text == null || text.isBlank()) ? "❌ Empty transcript returned" : text.trim();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            JSONObject seg = segments.getJSONObject(i);
            String text = seg.getString("text");
            if (text == null || text.isBlank()) continue;
            int t = (int) seg.getDoubleValue("start");
            sb.append(String.format("[%02d:%02d] %s%n", t / 60, t % 60, text.trim()));
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? json.getString("text") : out;
    }
}
