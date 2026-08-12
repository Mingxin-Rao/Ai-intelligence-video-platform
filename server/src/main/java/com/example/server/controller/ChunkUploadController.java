package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.MediaFile;
import com.example.server.interceptor.AuthInterceptor;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.metrics.AppMetrics;
import com.example.server.utils.MinioUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Resumable chunked upload.
 *
 * A single PUT of a multi-GB file has to survive one uninterrupted connection for
 * the whole transfer; on a flaky link that fails, and the client starts over from
 * zero. Splitting the file lets a failure cost one chunk instead of the whole
 * upload, and lets the client ask what is still missing before resuming.
 *
 * Chunk state lives in a Redis Set keyed by uploader + content hash:
 *   - Set membership is idempotent by construction, which matters because a weak
 *     network is exactly when a client re-sends a chunk it already delivered. The
 *     bookkeeping stays correct without any dedupe logic here.
 *   - Membership and difference are O(1)/O(n) in Redis, so "what is missing?" is
 *     one round-trip rather than a database scan.
 *   - The data is short-lived scratch state, discarded on merge. Writing hundreds
 *     of rows per upload to MySQL for something thrown away minutes later would
 *     cost far more than it is worth.
 *
 * Chunks are staged as individual MinIO objects and merged server-side with
 * composeObject, so the merge never pulls bytes back through the application.
 */
@RestController
@RequestMapping("/media/upload")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class ChunkUploadController {

    /**
     * 5 MiB. MinIO's server-side compose requires every part except the last to be
     * at least 5 MiB, so this is the floor for merging without a rewrite.
     */
    public static final long CHUNK_SIZE = 5L * 1024 * 1024;

    /** Abandoned uploads must not pin Redis keys (or staged chunks) forever. */
    private static final long STATE_TTL_HOURS = 24;

    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MinioUtils minioUtils;

    @Autowired
    private AppMetrics metrics;

    /**
     * Start or resume an upload.
     *
     * Returns INSTANT when the content is already owned by this user (nothing to
     * transfer at all), otherwise RESUME with the indices still outstanding.
     */
    @PostMapping("/init")
    public Map<String, Object> init(@RequestParam("fileMd5") String fileMd5,
                                    @RequestParam("totalSize") long totalSize,
                                    HttpServletRequest request) {
        Long userId = AuthInterceptor.currentUserId(request);
        Map<String, Object> result = new HashMap<>();

        if (fileMd5 == null || fileMd5.isBlank() || totalSize <= 0) {
            result.put("status", "ERROR");
            result.put("msg", "fileMd5 and a positive totalSize are required");
            return result;
        }

        int totalChunks = (int) Math.ceil((double) totalSize / CHUNK_SIZE);

        // Whole-file dedup: if this user already has the content, skip the transfer
        // entirely rather than re-uploading bytes we can prove we already hold.
        MediaFile existing = findByUserAndMd5(userId, fileMd5);
        if (existing != null) {
            metrics.recordDedupHit(AppMetrics.SOURCE_FILE);
            result.put("status", "INSTANT");
            result.put("mediaId", existing.getId());
            result.put("totalChunks", totalChunks);
            result.put("chunkSize", CHUNK_SIZE);
            return result;
        }

        Set<String> uploaded = redisTemplate.opsForSet().members(stateKey(userId, fileMd5));
        List<Integer> have = (uploaded == null) ? List.of()
                : uploaded.stream().map(Integer::parseInt).sorted().collect(Collectors.toList());

        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            if (!have.contains(i)) missing.add(i);
        }

        result.put("status", "RESUME");
        result.put("chunkSize", CHUNK_SIZE);
        result.put("totalChunks", totalChunks);
        result.put("uploadedChunks", have);
        result.put("missingChunks", missing);
        return result;
    }

    /**
     * Accept one chunk. Safe to call repeatedly for the same index — the Set
     * absorbs the duplicate, which is what makes blind client retries harmless.
     */
    @PostMapping("/chunk")
    public Map<String, Object> chunk(@RequestParam("file") MultipartFile file,
                                      @RequestParam("fileMd5") String fileMd5,
                                      @RequestParam("chunkIndex") int chunkIndex,
                                      HttpServletRequest request) {
        Long userId = AuthInterceptor.currentUserId(request);
        Map<String, Object> result = new HashMap<>();
        try {
            minioUtils.uploadFile(file, chunkObject(fileMd5, chunkIndex));

            String key = stateKey(userId, fileMd5);
            redisTemplate.opsForSet().add(key, String.valueOf(chunkIndex));
            redisTemplate.expire(key, STATE_TTL_HOURS, TimeUnit.HOURS);

            result.put("status", "OK");
            result.put("chunkIndex", chunkIndex);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            // Report rather than throw: the client's retry logic keys on status,
            // and a failed chunk is expected on a flaky link, not exceptional.
            result.put("status", "ERROR");
            result.put("chunkIndex", chunkIndex);
            result.put("msg", e.getMessage());
            return result;
        }
    }

    /**
     * Merge the staged chunks into the final object and record the media row.
     *
     * Refuses to merge a partial upload: composing a gap-ridden object would
     * produce a silently corrupt video rather than a visible failure.
     */
    @PostMapping("/merge")
    public Map<String, Object> merge(@RequestParam("fileMd5") String fileMd5,
                                      @RequestParam("fileName") String fileName,
                                      @RequestParam("totalChunks") int totalChunks,
                                      HttpServletRequest request) {
        Long userId = AuthInterceptor.currentUserId(request);
        Map<String, Object> result = new HashMap<>();
        String key = stateKey(userId, fileMd5);

        try {
            Set<String> uploaded = redisTemplate.opsForSet().members(key);
            List<Integer> have = (uploaded == null) ? List.of()
                    : uploaded.stream().map(Integer::parseInt).sorted().collect(Collectors.toList());

            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < totalChunks; i++) {
                if (!have.contains(i)) missing.add(i);
            }
            if (!missing.isEmpty()) {
                result.put("status", "INCOMPLETE");
                result.put("missingChunks", missing);
                return result;
            }

            // Another request may have merged this content already
            MediaFile existing = findByUserAndMd5(userId, fileMd5);
            if (existing != null) {
                metrics.recordDedupHit(AppMetrics.SOURCE_FILE);
                result.put("status", "INSTANT");
                result.put("mediaId", existing.getId());
                return result;
            }

            List<String> parts = new ArrayList<>();
            for (int i = 0; i < totalChunks; i++) {
                parts.add(chunkObject(fileMd5, i));
            }
            String objectName = fileMd5 + suffixOf(fileName);
            // Server-side compose: bytes are never pulled back through the app
            String fileUrl = minioUtils.composeObject(parts, objectName);

            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename(fileName);
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setUserId(userId);
            mediaFile.setVideoMd5(fileMd5);
            mediaFileMapper.insert(mediaFile);

            // Only now is the scratch state safe to drop
            minioUtils.removeObjects(parts);
            redisTemplate.delete(key);
            clearListCache(userId);

            result.put("status", "COMPLETE");
            result.put("mediaId", mediaFile.getId());
            result.put("filePath", fileUrl);
            return result;

        } catch (Exception e) {
            e.printStackTrace();
            // Leave the chunk state intact so the client can retry the merge
            // instead of re-uploading everything.
            result.put("status", "ERROR");
            result.put("msg", e.getMessage());
            return result;
        }
    }

    private String stateKey(Long userId, String fileMd5) {
        return "upload:chunks:" + (userId == null ? "anon" : userId) + ":" + fileMd5;
    }

    private String chunkObject(String fileMd5, int index) {
        return "tmp/" + fileMd5 + "/" + index;
    }

    private MediaFile findByUserAndMd5(Long userId, String md5) {
        if (userId == null || md5 == null) return null;
        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        query.eq("user_id", userId).eq("video_md5", md5).orderByDesc("id").last("LIMIT 1");
        return mediaFileMapper.selectOne(query);
    }

    private String suffixOf(String filename) {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf("."));
        }
        return "";
    }

    private void clearListCache(Long userId) {
        if (userId == null) return;
        redisTemplate.delete("media:list:user:" + userId);
    }
}
