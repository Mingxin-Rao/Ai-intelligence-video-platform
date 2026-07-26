package com.example.server.dto;

import java.io.Serializable;

// Must implement the Serializable interface, otherwise it cannot be transmitted over the network
public class AnalysisTaskMsg implements Serializable {
    private Long mediaId;
    private String action; // e.g. "START_ANALYSIS"

    public AnalysisTaskMsg() {}

    public AnalysisTaskMsg(Long mediaId, String action) {
        this.mediaId = mediaId;
        this.action = action;
    }

    public Long getMediaId() { return mediaId; }
    public void setMediaId(Long mediaId) { this.mediaId = mediaId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
}