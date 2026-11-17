package net.coreprotect.web.core;

import net.coreprotect.CoreProtect;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * ロールバック/リストア/パージのジョブを管理する。
 * 単一デーモンスレッドに直列化し、メイン/リージョンスレッドを塞がず、
 * DBの同時複数操作によるロック競合を防止する。
 */
public class JobManager {
    private final CoreProtect plugin;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private int retentionMinutes = 10;

    public JobManager(CoreProtect plugin) {
        this.plugin = plugin;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "coreprotect-web-job");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void setRetentionMinutes(int minutes) {
        this.retentionMinutes = minutes;
    }

    public Job submit(String type, Supplier<Integer> task) {
        Job job = new Job(type);
        jobs.put(job.id, job);

        executor.submit(() -> {
            job.status = Job.Status.RUNNING;
            try {
                job.affectedRows = task.get();
                job.status = Job.Status.DONE;
            } catch (Exception e) {
                job.errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                job.status = Job.Status.ERROR;
                plugin.getLogger().warning("ジョブ " + job.id + " (" + type + ") が失敗しました: " + job.errorMessage);
            } finally {
                job.finishedAt = Instant.now();
            }
        });

        return job;
    }

    public Job get(String id) {
        return jobs.get(id);
    }

    public Map<String, Job> all() {
        return jobs;
    }

    public void cleanup() {
        Instant threshold = Instant.now().minus(retentionMinutes, ChronoUnit.MINUTES);
        jobs.values().removeIf(job -> job.finishedAt != null && job.finishedAt.isBefore(threshold));
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
