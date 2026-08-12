package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Gemini multimodal helper: sends audio directly to Gemini and generates a summary report.
 * (Formerly DeepSeekUtils — it actually calls Google Gemini, so it has been renamed for clarity.)
 */
@Component
public class GeminiUtils {

    @Value("${ai.gemini.api-key}")
    private String apiKey;

    // The model name now comes from config, defaulting to the previously hardcoded value.
    @Value("${ai.gemini.model-name:gemini-3-flash-preview}")
    private String modelName;

    // Configurable like the OpenAI client's base URL: lets the endpoint be pointed
    // at a proxy or a compatible gateway, and makes this class testable against a
    // local stub instead of only against the real API.
    @Value("${ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
    private String baseUrl;

    // Multimodal processing needs a longer timeout because uploading audio takes time.
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public String analyzeAudioDirectly(String audioFilePath) {
        File file = new File(audioFilePath);
        if (!file.exists()) return "❌ Audio file not found";

        // 1. Encode the audio as Base64
        String base64Audio;
        try {
            byte[] fileContent = Files.readAllBytes(file.toPath());
            base64Audio = Base64.getEncoder().encodeToString(fileContent);
        } catch (IOException e) {
            return "❌ Failed to read audio: " + e.getMessage();
        }

        //    Build the multimodal request body (model name comes from ai.gemini.model-name).
        //    Note: use an audio-capable Gemini model (e.g. gemini-1.5-flash / 2.0-flash); gemma has no audio.
        String url = baseUrl.replaceAll("/+$", "") + "/models/"
                + modelName + ":generateContent?key=" + apiKey;

        JSONObject payload = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject contentObj = new JSONObject();
        JSONArray parts = new JSONArray();

        // Text instruction (prompt) part
        JSONObject textPart = new JSONObject();
        textPart.put("text", "You are a professional video content analyst. Listen to this audio and produce a detailed written summary report, including the core arguments and detailed explanations. Please respond in English.");
        parts.add(textPart);

        // Audio data part
        JSONObject audioPart = new JSONObject();
        JSONObject inlineData = new JSONObject();
        inlineData.put("mime_type", "audio/mpeg"); // Use this MIME type for mp3
        inlineData.put("data", base64Audio);
        audioPart.put("inline_data", inlineData);
        parts.add(audioPart);

        contentObj.put("parts", parts);
        contents.add(contentObj);
        payload.put("contents", contents);

        RequestBody body = RequestBody.create(
                payload.toJSONString(),
                MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String resBody = response.body().string();
            if (!response.isSuccessful()) return "❌ Google analysis failed: " + resBody;

            JSONObject json = JSON.parseObject(resBody);
            return json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text");
        } catch (Exception e) {
            return "❌ Request exception: " + e.getMessage();
        }
    }
}
