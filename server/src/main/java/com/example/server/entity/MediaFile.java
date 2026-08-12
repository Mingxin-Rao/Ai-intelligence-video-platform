package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("media_files")
public class MediaFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;          // Core: records who uploaded it

    private String filename;
    private String status;        //UPLOADED, COMPLETED
    private String filePath;

    // The following few are newly added
    private String aiSummary;
    private String transcriptText;
    private String coverUrl;

    // Dedup keys — exactly one is set depending on how the video arrived:
    //   videoMd5      : direct file uploads, where the bytes are the identity.
    //   sourceVideoId : link imports, e.g. "youtube:dQw4w9WgXcQ". Stable across
    //                   URL forms and re-encodes, unlike a hash of the download.
    private String videoMd5;
    private String sourceVideoId;

    // [Modification] Removed the @TableField(fill = ...) annotation
    // Upload time is recorded automatically by the database; Java does not intervene, preventing errors
    private LocalDateTime uploadTime;
}