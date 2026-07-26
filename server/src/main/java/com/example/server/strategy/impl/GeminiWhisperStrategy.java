package com.example.server.strategy.impl;

import com.example.server.strategy.AiAnalysisStrategy;
import com.example.server.utils.GeminiUtils;
import com.example.server.utils.OpenAiWhisperUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Default AI strategy: summary via Gemini (direct audio), transcript via OpenAI Whisper.
 * (Formerly AliyunDeepSeekStrategy — renamed to match what it actually does.)
 */
@Component("defaultAiStrategy")
public class GeminiWhisperStrategy implements AiAnalysisStrategy {

    @Autowired
    private OpenAiWhisperUtils whisperUtils;

    @Autowired
    private GeminiUtils geminiUtils;

    @Override
    public String transcribe(String videoPath) {
        return processVideoToText(videoPath);
    }

    @Override
    public String generateSummary(String videoPath) {
        // Prepare a temporary MP3 path
        String tempAudioPath = System.getProperty("java.io.tmpdir") + File.separator + "summary_" + UUID.randomUUID() + ".mp3";

        try {
            System.out.println("🎵 [AI Summary] Extracting audio: " + videoPath);

            //    Call extractAudio (passing the input and output paths).
            boolean success = extractAudio(videoPath, tempAudioPath);
            if (!success) {
                return "❌ Audio extraction failed, cannot generate summary";
            }

            //    Call Gemini's direct-audio method (hand the temp audio path to GeminiUtils).
            return geminiUtils.analyzeAudioDirectly(tempAudioPath);

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ Summary generation error: " + e.getMessage();
        } finally {
            //    Delete the temp audio file afterwards to free up space.
            File tempFile = new File(tempAudioPath);
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }



    private String processVideoToText(String inputPath) {
        // Basic validation
        if (inputPath == null || inputPath.isEmpty()) return "❌ Path is empty";

        // Error out if a local path is missing; skip the check for http links and let FFmpeg handle them.
        if (!inputPath.startsWith("http")) {
            File localFile = new File(inputPath);
            if (!localFile.exists()) return "❌ File not found on disk: " + inputPath;
        }

        // Prepare a temporary MP3 path (inside the system temp directory).
        String outputMp3Path = System.getProperty("java.io.tmpdir") + File.separator + "temp_" + UUID.randomUUID() + ".mp3";

        try {
            System.out.println("🎵 [AI Strategy] Processing video source: " + inputPath);

            //    Extract audio (FFmpeg natively supports HTTP URLs, so pass it straight through).
            boolean success = extractAudio(inputPath, outputMp3Path);
            if (!success) return "FFmpeg conversion failed (possibly a network timeout or a corrupted file)";

            //    Speech-to-text (OpenAI Whisper, returns timestamped structured text)
            String text = whisperUtils.transcribe(outputMp3Path);
            return text;

        } catch (Exception e) {
            e.printStackTrace();
            return "Processing error: " + e.getMessage();
        } finally {
            // Clean up the temp file
            File mp3 = new File(outputMp3Path);
            if (mp3.exists()) mp3.delete();
        }
    }

    // === FFmpeg helper ===
    private boolean extractAudio(String inputPath, String outputPath) {
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-y");
            command.add("-i");
            command.add(inputPath);
            command.add("-vn");
            command.add("-acodec");
            command.add("libmp3lame");
            command.add("-q:a");
            command.add("2");
            command.add(outputPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            process = pb.start();
            // Network streams can be slow, so allow plenty of time
            boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.MINUTES);

            if (finished) {
                return process.exitValue() == 0;
            } else {
                process.destroyForcibly();
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
