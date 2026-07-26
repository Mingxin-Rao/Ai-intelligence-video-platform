package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class MediaService {

    // Inject the database operation interface (auto-proxied by MyBatis-Plus)
    @Autowired
    private MediaFileMapper mediaFileMapper;

    // Use the OS temp directory — portable across platforms (no more hardcoded Windows path)
    private final String UPLOAD_DIR = System.getProperty("java.io.tmpdir") + File.separator + "dovideo-uploads" + File.separator;

    public MediaService() {
        File dir = new File(UPLOAD_DIR);
        if (!dir.exists()) dir.mkdirs();
    }

    public String convertVideoToAudio(MultipartFile file) throws IOException, InterruptedException {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setFilename(file.getOriginalFilename());
        mediaFile.setStatus("PROCESSING"); // Status: processing
        mediaFile.setUploadTime(LocalDateTime.now());
        mediaFile.setFilePath(""); // Temporarily empty

        // After this step executes, MySQL will have one more row of data
        mediaFileMapper.insert(mediaFile);

        // --- Below is the original file processing logic ---
        String fileId = UUID.randomUUID().toString();
        String inputPath = UPLOAD_DIR + fileId + "_input.mp4";
        String outputPath = UPLOAD_DIR + fileId + "_output.mp3";

        File inputFile = new File(inputPath);
        file.transferTo(inputFile);

        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-i");
        command.add(inputFile.getAbsolutePath());
        command.add("-vn");
        command.add("-acodec");
        command.add("libmp3lame");
        command.add("-q:a");
        command.add("2");
        command.add(new File(outputPath).getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        if (process.waitFor() == 0) {
            inputFile.delete(); // Delete the original video

            // --- Database operation: update status to completed ---
            mediaFile.setStatus("COMPLETED");
            mediaFile.setFilePath(outputPath);
            mediaFileMapper.updateById(mediaFile); // Update this row by ID

            return outputPath;
        } else {
            // --- Database operation: record failure ---
            mediaFile.setStatus("FAILED");
            mediaFileMapper.updateById(mediaFile);
            throw new RuntimeException("FFmpeg conversion failed");
        }
    }
}