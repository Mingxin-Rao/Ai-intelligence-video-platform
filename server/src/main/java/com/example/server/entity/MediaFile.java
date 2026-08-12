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

    // Content fingerprint (MD5 of the file bytes). Used to dedup identical uploads
    // so the same video is not stored / analyzed twice. Maps to column video_md5.
    private String videoMd5;

    // [Modification] Removed the @TableField(fill = ...) annotation
    // Upload time is recorded automatically by the database; Java does not intervene, preventing errors
    private LocalDateTime uploadTime;
}