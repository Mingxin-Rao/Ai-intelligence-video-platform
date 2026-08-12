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
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
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
            // Content fingerprint: MD5 of the bytes. Drives both dedup and the MinIO object name.
            String md5;
            try (InputStream in = file.getInputStream()) {
                md5 = DigestUtils.md5DigestAsHex(in);
            }

            // Dedup: same user + same content => reuse the existing record, skip re-upload and the duplicate row.
            MediaFile existing = findByUserAndMd5(userId, md5);
            if (existing != null) {
                System.out.println("♻️ Duplicate content (md5=" + md5 + "), reusing record id=" + existing.getId());
                return "Upload successful (duplicate content — reused existing file)";
            }

            String objectName = md5 + suffixOf(file.getOriginalFilename());
            System.out.println("Uploading to MinIO as " + objectName + " ...");
            String fileUrl = minioUtils.uploadFile(file, objectName);
            System.out.println("MinIO upload succeeded, URL: " + fileUrl);

            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename(file.getOriginalFilename());
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setUserId(userId);
            mediaFile.setVideoMd5(md5);

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

            // Links dedup on the source video id, NOT a content hash. The same video
            // has many URL forms, and yt-dlp may resolve "best format" differently
            // between runs, so the downloaded bytes are not a stable identity.
            // Resolving the id costs one metadata request and lets us skip the
            // entire download when we already have the video.
            String sourceId = ytDlpUtils.extractSourceId(url);
            if (sourceId != null) {
                MediaFile existing = findByUserAndSourceId(userId, sourceId);
                if (existing != null) {
                    System.out.println("♻️ Duplicate link (" + sourceId + "), reusing record id=" + existing.getId());
                    // Returns before downloading a single byte
                    return org.springframework.http.ResponseEntity.ok(
                            "Upload successful (duplicate video — reused existing file)");
                }
            }

            // Download via yt-dlp
            tempFile = ytDlpUtils.downloadVideo(url);

            // Name the object after the source id so the same video maps to one object.
            // If the id could not be resolved, keep the random temp name — this one
            // simply cannot be deduped rather than failing the upload.
            String objectName = (sourceId != null)
                    ? sanitizeObjectName(sourceId) + ".mp4"
                    : tempFile.getName();
            String fileUrl = minioUtils.uploadLocalFile(tempFile, objectName);

            // Persist to the database
            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename("WEB_" + objectName);
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setUserId(userId);
            mediaFile.setSourceVideoId(sourceId);

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

    // Return the most recent record this user owns with the given content MD5, or null.
    // Used for direct file uploads, where the bytes ARE the identity.
    private MediaFile findByUserAndMd5(Long userId, String md5) {
        if (userId == null || md5 == null) return null;
        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        query.eq("user_id", userId).eq("video_md5", md5).orderByDesc("id").last("LIMIT 1");
        return mediaFileMapper.selectOne(query);
    }

    // Return the most recent record this user owns for the given source video id, or null.
    // Used for link imports, where the upstream id is the stable identity.
    private MediaFile findByUserAndSourceId(Long userId, String sourceId) {
        if (userId == null || sourceId == null) return null;
        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        query.eq("user_id", userId).eq("source_video_id", sourceId).orderByDesc("id").last("LIMIT 1");
        return mediaFileMapper.selectOne(query);
    }

    // "youtube:dQw4w9WgXcQ" -> "youtube_dQw4w9WgXcQ"; keeps object keys URL-safe.
    private String sanitizeObjectName(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // Extract the file extension (including the dot), or "" if none.
    private String suffixOf(String filename) {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf("."));
        }
        return "";
    }
}
