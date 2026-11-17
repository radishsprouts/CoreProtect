package net.coreprotect.web.core;

import java.time.Instant;
import java.util.UUID;

/**
 * ロールバック/リストア/パージなど、時間のかかる非同期操作の実行状態モデル。
 */
public class Job {
    public enum Status {
        QUEUED,
        RUNNING,
        DONE,
        ERROR
    }

    public final String id = UUID.randomUUID().toString();
    public final String type;
    public final Instant createdAt = Instant.now();

    public volatile Status status = Status.QUEUED;
    public volatile Instant finishedAt;
    public volatile int affectedRows = -1;
    public volatile String errorMessage;

    public Job(String type) {
        this.type = type;
    }
}
