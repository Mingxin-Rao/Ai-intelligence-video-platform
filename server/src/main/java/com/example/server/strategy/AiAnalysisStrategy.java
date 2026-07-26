package com.example.server.strategy;

/**
 * AI analysis strategy interface.
 * Note: parameters use String so both local paths (D:/...) and network URLs (http://...) work.
 */
public interface AiAnalysisStrategy {

    /**
     * Convert a video file into text.
     * @param videoPath video path or URL
     */
    String transcribe(String videoPath);

    /**
     * Generate an AI summary of the video content.
     * @param videoPath video path or URL
     */
    String generateSummary(String videoPath);
}
