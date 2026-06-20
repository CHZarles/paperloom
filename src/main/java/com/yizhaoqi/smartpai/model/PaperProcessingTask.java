package com.yizhaoqi.smartpai.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 论文处理任务，用于 Kafka 异步解析和索引。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PaperProcessingTask {
    public static final String TASK_TYPE_UPLOAD_PROCESS = "UPLOAD_PROCESS";
    public static final String TASK_TYPE_REINDEX = "REINDEX";

    private String paperId;
    private String paperObjectUrl;
    private String paperTitle;
    private String userId;
    private String orgTag;
    private boolean isPublic;
    private String taskType; // 任务类型
    private String requesterId; // 发起重试的用户
}
