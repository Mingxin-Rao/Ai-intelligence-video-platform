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

    /**
     * Both AI paths here are audio-only: the video is stripped to an MP3 first.
     * A silent video (e.g. a screen recording) therefore has nothing to analyse,
     * which is a property of the input rather than a processing failure — say so
     * instead of reporting a generic extraction error.
     */
    private static final String NO_AUDIO_MESSAGE =
            "❌ The uploaded video does not have an audio track, so a summary could not be generated.";

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

            // Distinguish "nothing to extract" from "extraction broke" up front,
            // otherwise a silent video reports a misleading failure.
            if (!hasAudioStream(videoPath)) {
                System.out.println("🔇 [AI Summary] No audio stream in " + videoPath);
                return NO_AUDIO_MESSAGE;
            }

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

            // A transcript is also audio-derived, so a silent video is a dead end.
            if (!hasAudioStream(inputPath)) {
                System.out.println("🔇 [AI Strategy] No audio stream in " + inputPath);
                return "❌ The uploaded video does not have an audio track, so no transcript could be extracted.";
            }

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

    /**
     * Whether the media has at least one audio stream, probed with ffprobe
     * (ships with FFmpeg, so no new dependency).
     *
     * Fails open: if the probe itself cannot run we return true so FFmpeg still
     * gets its chance — a broken probe must not block otherwise-valid videos.
     */
    private boolean hasAudioStream(String inputPath) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "error",
                    "-select_streams", "a",              // audio streams only
                    "-show_entries", "stream=index",
                    "-of", "csv=p=0",
                    inputPath);
            pb.redirectErrorStream(true);
            process = pb.start();

            String output;
            try (java.io.InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }

            // Remote URLs can be slow; bail out rather than hang the worker thread.
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return true;
            }
            // Empty stdout => ffprobe found no audio stream at all
            return !output.isEmpty();

        } catch (Exception e) {
            System.err.println("⚠️ ffprobe audio check failed, continuing anyway: " + e.getMessage());
            return true;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
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
