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

    // [Modification] Removed the @TableField(fill = ...) annotation
    // Upload time is recorded automatically by the database; Java does not intervene, preventing errors
    private LocalDateTime uploadTime;
}