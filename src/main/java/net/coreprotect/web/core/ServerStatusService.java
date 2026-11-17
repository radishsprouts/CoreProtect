package net.coreprotect.web.core;

import net.coreprotect.CoreProtect;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.thread.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * サーバー状態、ワールド一覧、オンラインプレイヤー、DBエンジン、キュー滞留数を取得する。
 */
public class ServerStatusService {
    private final CoreProtect plugin;

    public ServerStatusService(CoreProtect plugin) {
        this.plugin = plugin;
    }

    public static final class Snapshot {
        private final boolean coreProtectAvailable;
        private final Integer coreProtectApiVersion;
        private final boolean folia;
        private final String databaseEngine;
        private final int pendingQueueSize;
        private final List<String> worlds;
        private final List<String> onlinePlayers;

        public Snapshot(boolean coreProtectAvailable, Integer coreProtectApiVersion, boolean folia,
                        String databaseEngine, int pendingQueueSize, List<String> worlds, List<String> onlinePlayers) {
            this.coreProtectAvailable = coreProtectAvailable;
            this.coreProtectApiVersion = coreProtectApiVersion;
            this.folia = folia;
            this.databaseEngine = databaseEngine != null ? databaseEngine : "Unknown";
            this.pendingQueueSize = pendingQueueSize;
            this.worlds = worlds != null ? worlds : Collections.emptyList();
            this.onlinePlayers = onlinePlayers != null ? onlinePlayers : Collections.emptyList();
        }

        public boolean coreProtectAvailable() { return coreProtectAvailable; }
        public Integer coreProtectApiVersion() { return coreProtectApiVersion; }
        public boolean folia() { return folia; }
        public String databaseEngine() { return databaseEngine; }
        public int pendingQueueSize() { return pendingQueueSize; }
        public List<String> worlds() { return worlds; }
        public List<String> onlinePlayers() { return onlinePlayers; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Snapshot snapshot = (Snapshot) o;
            return coreProtectAvailable == snapshot.coreProtectAvailable &&
                    folia == snapshot.folia &&
                    pendingQueueSize == snapshot.pendingQueueSize &&
                    Objects.equals(coreProtectApiVersion, snapshot.coreProtectApiVersion) &&
                    Objects.equals(databaseEngine, snapshot.databaseEngine) &&
                    Objects.equals(worlds, snapshot.worlds) &&
                    Objects.equals(onlinePlayers, snapshot.onlinePlayers);
        }

        @Override
        public int hashCode() {
            return Objects.hash(coreProtectAvailable, coreProtectApiVersion, folia, databaseEngine, pendingQueueSize, worlds, onlinePlayers);
        }
    }

    public Snapshot snapshot() throws InterruptedException, ExecutionException, TimeoutException {
        List<String> worldNames = callOnGlobalRegion(() -> {
            List<String> names = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                names.add(world.getName());
            }
            return names;
        });

        List<String> playerNames = callOnGlobalRegion(() -> {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return names;
        });

        boolean available = plugin.getAPI() != null && plugin.getAPI().isEnabled();
        Integer apiVer = plugin.getAPI() != null ? plugin.getAPI().APIVersion() : null;
        String dbType = ConfigHandler.databaseType != null ? ConfigHandler.databaseType.getDisplayName() : "Unknown";
        int queueSize = Consumer.getConsumerSize(0) + Consumer.getConsumerSize(1);

        return new Snapshot(available, apiVer, ConfigHandler.isFolia, dbType, queueSize, worldNames, playerNames);
    }

    private <T> T callOnGlobalRegion(Supplier<T> supplier) throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<T> future = new CompletableFuture<>();
        Scheduler.scheduleSyncDelayedTask(plugin, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, null, 0);
        return future.get(5, TimeUnit.SECONDS);
    }
}
