package com.example.server.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class YtDlpUtils {

    @Value("${tool.ytdlp.path}")
    private String ytDlpPath;

    @Value("${tool.ffmpeg.dir}")
    private String ffmpegDir;

    public File downloadVideo(String url) throws Exception {
        String tempDir = System.getProperty("java.io.tmpdir");
        String outputName = UUID.randomUUID().toString() + ".mp4";
        String outputPath = tempDir + File.separator + outputName;

        System.out.println("⬇️ [yt-dlp] Starting download (smart mode): " + url);

        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);


        // Drop every "-f xxx" constraint and let yt-dlp pick the best compatible format itself.
        // By default it grabs bestvideo+bestaudio; against members-only limits it usually downgrades or errors out.
        // Spoofed headers (kept to avoid being banned outright).
        command.add("--user-agent");
        command.add("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
        command.add("--referer");
        command.add("https://www.bilibili.com/");

        // Force transcoding to mp4 (the only hard requirement).
        command.add("--recode-video");
        command.add("mp4");

        command.add("--ffmpeg-location");
        command.add(ffmpegDir);

        command.add("-o");
        command.add(outputPath);

        // Ignore certificate checks and playlists
        command.add("--no-check-certificate");
        command.add("--no-playlist");

        command.add(url);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder logs = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Print yt-dlp's key log lines to aid debugging.
                if (line.contains("ERROR") || line.contains("Downloading") || line.contains("[Merger]")) {
                    System.out.println("cmd > " + line);
                }
                logs.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            // On failure, throw so the frontend shows a red error.
            throw new RuntimeException("yt-dlp download failed: " + logs.toString());
        }

        File downloadedFile = new File(outputPath);
        if (!downloadedFile.exists()) {
            throw new RuntimeException("Download reported success but no file was generated");
        }

        System.out.println("✅ [yt-dlp] Download complete: " + (downloadedFile.length() / 1024) + "KB");
        return downloadedFile;
    }
}
