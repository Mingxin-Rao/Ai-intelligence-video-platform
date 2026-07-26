package com.example.server.controller;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.interceptor.AuthInterceptor;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiService;
import com.example.server.strategy.AiAnalysisStrategy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate; // Import the Redis template
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit; // Import the time unit

@RestController
@RequestMapping("/debug")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class DebugController {

    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    @Qualifier("defaultAiStrategy")
    private AiAnalysisStrategy aiAnalysisStrategy;

    @Autowired
    private AiService aiService;


    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;

    @Autowired
    private org.redisson.api.RedissonClient redissonClient;

    /**
     * Ownership check: returns null if the media is missing or not owned by the current user.
     */
    private MediaFile ownedOrNull(Long id, HttpServletRequest request) {
        Long uid = AuthInterceptor.currentUserId(request);
        MediaFile file = mediaFileMapper.selectById(id);
        if (file == null || file.getUserId() == null || !file.getUserId().equals(uid)) {
            return null;
        }
        return file;
    }

    // AI summary endpoint (distributed lock + rate limiting + MQ).
    @GetMapping("/ai")
    public String aiAnalyze(@RequestParam Long id, HttpServletRequest request) {
        // [Redisson distributed lock] guards against rapid concurrent double-clicks.
        String lockKey = "lock:analyze:" + id;
        org.redisson.api.RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(0, -1, TimeUnit.SECONDS)) {
                return "⚠️ Task is being submitted, please don't click again!";
            }


            // Demo: globally cap analysis at 10 per minute (to keep costs under control).
            String limitKey = "limit:ai:global";
            org.redisson.api.RRateLimiter rateLimiter = redissonClient.getRateLimiter(limitKey);
            // Init: 10 tokens per minute (RateType.OVERALL = cluster-wide, PER_CLIENT = per instance).
            rateLimiter.trySetRate(org.redisson.api.RateType.OVERALL, 10, 1, org.redisson.api.RateIntervalUnit.MINUTES);

            // Try to acquire one token
            if (!rateLimiter.tryAcquire(1)) {
                return "⚠️ System busy (rate limited). Please try again in 1 minute!";
            }

            // Look up the record and verify ownership
            MediaFile file = ownedOrNull(id, request);
            if (file == null) return "❌ File not found or you don't have access to it";
            if (file.getAiSummary() != null && file.getAiSummary().contains("[MQ]")) {
                return "Task is already running in the background, no need to resubmit";
            }

            // Update status (the [MQ] placeholder lets the frontend detect the "processing" state).
            file.setAiSummary("⏳ [MQ] Queued — waiting to be scheduled...");
            mediaFileMapper.updateById(file);
            String userIdKey = (file.getUserId() == null) ? "anon" : String.valueOf(file.getUserId());
            redisTemplate.delete("media:list:user:" + userIdKey);

            // Send the message
            AnalysisTaskMsg msg = new AnalysisTaskMsg(id, "START_ANALYSIS");
            rocketMQTemplate.convertAndSend("video-analysis-topic", msg);

            return "✅ Task dispatched to RocketMQ!";

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ Submission failed: " + e.getMessage();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // Plain transcript extraction endpoint
    @GetMapping("/transcribe")
    public String transcribe(@RequestParam Long id, HttpServletRequest request) {
        // Ownership check
        MediaFile mediaFile = ownedOrNull(id, request);
        if (mediaFile == null) return "❌ File not found or you don't have access to it";

        // Invoke the asynchronous service
        aiService.asyncTranscribe(id);

        return "✅ Extraction task is running in the background. Please check back shortly.";
    }

    // Audio download endpoint
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam Long id, HttpServletRequest request) throws IOException {
        // Ownership check
        MediaFile mediaFile = ownedOrNull(id, request);
        if (mediaFile == null) return ResponseEntity.status(403).build();

        String inputPath = mediaFile.getFilePath();
        if (inputPath == null) return ResponseEntity.notFound().build();

        if (!inputPath.startsWith("http")) {
            if (!new File(inputPath).exists()) return ResponseEntity.notFound().build();
        }

        String outputMp3Path = System.getProperty("java.io.tmpdir") + File.separator + "download_" + UUID.randomUUID() + ".mp3";
        System.out.println("⬇ Download request, transcoding audio from source: " + inputPath);

        boolean success = runFfmpeg(inputPath, outputMp3Path);

        if (!success) return ResponseEntity.internalServerError().build();

        File mp3File = new File(outputMp3Path);
        Resource resource = new FileSystemResource(mp3File);

        String fileName = "audio.mp3";
        if (mediaFile.getFilename() != null) {
            fileName = mediaFile.getFilename().replaceAll("\\.[^.]+$", "") + ".mp3";
        }
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(resource);
    }

    private boolean runFfmpeg(String inputPath, String outputPath) {
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
            Process process = pb.start();
            return process.waitFor(15, TimeUnit.MINUTES) && process.exitValue() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
