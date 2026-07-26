package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.strategy.AiAnalysisStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AiService {

    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    @Qualifier("defaultAiStrategy")
    private AiAnalysisStrategy aiAnalysisStrategy;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * Asynchronously analyze a video (AI summary report).
     */
    @Async("aiTaskExecutor")
    public void asyncAnalyze(Long mediaId) {
        System.out.println("🚀 [ThreadPool] Starting AI analysis task, ID: " + mediaId);

        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) return;

        try {
            //    Exponential-backoff retry: up to 3 attempts on AI/network hiccups (1s, 2s gaps).
            final int maxAttempts = 3;
            String summary = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                summary = aiAnalysisStrategy.generateSummary(mediaFile.getFilePath());
                // The strategy returns a ❌-prefixed string on failure
                if (summary != null && !summary.startsWith("❌")) break;
                System.err.println("⚠️ [ThreadPool] AI attempt " + attempt + "/" + maxAttempts
                        + " failed for ID " + mediaId + ": " + summary);
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep((long) Math.pow(2, attempt - 1) * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            boolean failed = (summary == null || summary.startsWith("❌"));

            //    Persist the result (do NOT touch transcriptText — owned by "Extract Text").
            mediaFile.setAiSummary(summary);
            // The media file itself is still usable; keep COMPLETED so the user can click to retry.
            mediaFile.setStatus("COMPLETED");
            mediaFileMapper.updateById(mediaFile);

            //    Force-clear the Redis cache so the frontend can load the fresh data.
            cleanRedisCache(mediaFile.getUserId());

            if (failed) {
                // Backend records a clear failure (frontend shows ❌ and can retry)
                System.err.println("❌ [ThreadPool] AI analysis FAILED after " + maxAttempts + " attempts, ID: " + mediaId);
            } else {
                System.out.println("✅ [ThreadPool] AI analysis task fully completed!");
            }

        } catch (Exception e) {
            System.err.println("❌ [ThreadPool] AI analysis crashed: " + e.getMessage());
            e.printStackTrace();
            // Surface the crash so the user sees it failed and can retry
            try {
                mediaFile.setAiSummary("❌ Analysis error: " + e.getMessage());
                mediaFile.setStatus("COMPLETED");
                mediaFileMapper.updateById(mediaFile);
            } catch (Exception ignore) {}
            // Clear the cache on failure too, so the frontend stops spinning.
            cleanRedisCache(mediaFile.getUserId());
        }
    }

    /**
     * Asynchronously extract the full transcript (used when the frontend extracts text on its own).
     */
    @Async("aiTaskExecutor")
    public void asyncTranscribe(Long mediaId) {
        System.out.println("🚀 [ThreadPool] Starting transcript extraction task, ID: " + mediaId);

        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) return;

        try {
            String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
            mediaFile.setTranscriptText(text);
            mediaFileMapper.updateById(mediaFile);

            cleanRedisCache(mediaFile.getUserId());
            System.out.println("✅ [ThreadPool] Transcript extraction completed!");

        } catch (Exception e) {
            System.err.println("❌ [ThreadPool] Extraction failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Shared helper that clears the Redis cache for a user's media list.
     */
    private void cleanRedisCache(Long userId) {
        String userIdStr = (userId == null) ? "anon" : String.valueOf(userId);
        String cacheKey = "media:list:user:" + userIdStr;
        Boolean deleted = redisTemplate.delete(cacheKey);
        System.out.println("🧹 [CacheCleanup] Key: " + cacheKey + " | Result: " + deleted);
    }
}
