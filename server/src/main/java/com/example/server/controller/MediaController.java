package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.MediaFile;
import com.example.server.interceptor.AuthInterceptor;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils; // Make sure this is imported
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/media")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class MediaController {

    @Autowired(required = false)
    private MediaFileMapper mediaFileMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MinioUtils minioUtils;

    @Autowired
    private YtDlpUtils ytDlpUtils;


    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        // Identity comes from the verified token, not a client param
        Long userId = AuthInterceptor.currentUserId(request);
        try {
            System.out.println("Uploading to MinIO...");
            String fileUrl = minioUtils.uploadFile(file);
            System.out.println("MinIO upload succeeded, URL: " + fileUrl);

            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename(file.getOriginalFilename());
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setUserId(userId);

            mediaFileMapper.insert(mediaFile);
            clearListCache(userId);

            return "Upload successful";

        } catch (Exception e) {
            e.printStackTrace();
            return "Upload failed: " + e.getMessage();
        }
    }

    // === Video link upload endpoint (fixed: uses HTTP status codes to signal success/failure) ===
    @PostMapping("/upload-url")
    public org.springframework.http.ResponseEntity<String> uploadUrl(@RequestParam("url") String url,
                                                                     HttpServletRequest request) {
        Long userId = AuthInterceptor.currentUserId(request);
        File tempFile = null;
        try {
            System.out.println("🔗 Received link upload request: " + url);

            // Download via yt-dlp
            tempFile = ytDlpUtils.downloadVideo(url);

            // Upload to MinIO
            String fileUrl = minioUtils.uploadLocalFile(tempFile);

            // Persist to the database
            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename("WEB_" + tempFile.getName());
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setUserId(userId);

            mediaFileMapper.insert(mediaFile);
            clearListCache(userId);

            // Return 200 on success
            return org.springframework.http.ResponseEntity.ok("Upload successful");

        } catch (Exception e) {
            e.printStackTrace();
            // Return 500 on failure so the frontend fetch throws and surfaces the error.
            return org.springframework.http.ResponseEntity.status(500).body("Upload failed: " + e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    // List endpoint: returns only the signed-in user's media
    @GetMapping("/list")
    public List<MediaFile> getList(HttpServletRequest request) {
        Long userId = AuthInterceptor.currentUserId(request);
        String cacheKey = "media:list:user:" + userId;

        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                System.out.println("Redis cache hit, returning directly!");
                return objectMapper.readValue(json, new TypeReference<List<MediaFile>>(){});
            }
        } catch (Exception e) {
            System.err.println("Redis read failed: " + e.getMessage());
        }

        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        query.eq("user_id", userId);
        List<MediaFile> list = mediaFileMapper.selectList(query.orderByDesc("id"));

        try {
            String jsonToWrite = objectMapper.writeValueAsString(list);
            redisTemplate.opsForValue().set(cacheKey, jsonToWrite, 30, TimeUnit.MINUTES);
            System.out.println("Written to Redis cache");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return list;
    }

    // Delete endpoint: only the owner may delete
    @DeleteMapping("/delete")
    public String delete(@RequestParam("id") Long id, HttpServletRequest request) {
        Long userId = AuthInterceptor.currentUserId(request);

        MediaFile media = mediaFileMapper.selectById(id);
        if (media == null) return "File not found";

        // Ownership check: userId comes from the token, unforgeable
        if (media.getUserId() == null || !media.getUserId().equals(userId)) {
            return "You are not allowed to delete this file";
        }

        if (media.getFilePath() != null && media.getFilePath().startsWith("http")) {
            minioUtils.removeFile(media.getFilePath());
        }

        mediaFileMapper.deleteById(id);
        clearListCache(userId);

        // Note: the frontend matches this exact string to detect success — keep it in sync with App.vue.
        return "Deleted successfully";
    }

    // Clear a user's list cache
    private void clearListCache(Long userId) {
        if (userId == null) return;
        String cacheKey = "media:list:user:" + userId;
        redisTemplate.delete(cacheKey);
        System.out.println("Cache cleared: " + cacheKey);
    }
}
