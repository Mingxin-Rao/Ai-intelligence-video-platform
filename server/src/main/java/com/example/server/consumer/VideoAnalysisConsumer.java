package com.example.server.consumer;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Component
// Listens on the "video-analysis-topic" topic; the consumer group name is arbitrary.
@RocketMQMessageListener(topic = "video-analysis-topic", consumerGroup = "video-group")
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    @Autowired
    private AiService aiService;

    @Autowired
    private MediaFileMapper mediaFileMapper;

    // Inject the previously configured IO-intensive thread pool.
    @Autowired
    private Executor aiTaskExecutor;

    @Override
    public void onMessage(AnalysisTaskMsg msg) {
        Long mediaId = msg.getMediaId();
        System.out.println("⚡ [MQ Consumer] Received task ID: " + mediaId + ", dispatching to the thread pool...");

        // Asynchronous orchestration with CompletableFuture.
        // Even though the MQ consumer thread is fast, we don't block it — heavy work goes to the business thread pool.
        CompletableFuture.runAsync(() -> {
            System.out.println("🧵 [ThreadPool] Starting the AI analysis logic...");
            try {

                aiService.asyncAnalyze(mediaId);
            } catch (Exception e) {
                System.err.println("❌ Task execution failed: " + e.getMessage());
                // Extension point: persist the failure state to the database here.
                markAsFailed(mediaId, e.getMessage());
            }
        }, aiTaskExecutor);
    }

    private void markAsFailed(Long id, String error) {
        MediaFile file = mediaFileMapper.selectById(id);
        if (file != null) {
            file.setAiSummary("❌ Analysis failed: " + error);
            mediaFileMapper.updateById(file);
        }
    }
}
