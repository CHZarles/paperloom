package io.github.chzarles.paperloom.utils;

import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;

/**
 * 对象存储的清理动作。失败只记日志，不打断调用方。
 */
public final class MinioObjects {

    private MinioObjects() {
    }

    public static void removeQuietly(MinioClient client, String bucket, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception ignored) {
            // 清理是尽力而为，数据库元数据仍会被删掉。
        }
    }
}
