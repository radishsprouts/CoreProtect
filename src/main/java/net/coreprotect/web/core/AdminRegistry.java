package net.coreprotect.web.core;

import net.coreprotect.CoreProtect;
import net.coreprotect.thread.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Web UI からのテレポート対象となる管理者の登録を管理する。
 */
public class AdminRegistry {
    private final CoreProtect plugin;
    private final File file;
    private final Map<UUID, String> admins = new ConcurrentHashMap<>();

    public AdminRegistry(CoreProtect plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "admins.yml");
    }

    public void load() {
        if (!file.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                admins.put(UUID.fromString(key), yaml.getString(key, key));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[WebUI] admins.yml の不正なUUIDを無視しました: " + key);
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, String> entry : admins.entrySet()) {
            yaml.set(entry.getKey().toString(), entry.getValue());
        }

        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("[WebUI] admins.yml の保存に失敗しました: " + e.getMessage());
        }
    }

    private void saveAsync() {
        CompletableFuture.runAsync(this::save);
    }

    public boolean register(UUID uuid, String name) {
        boolean isNew = !admins.containsKey(uuid);
        admins.put(uuid, name);
        saveAsync();
        return isNew;
    }

    public boolean unregister(UUID uuid) {
        boolean removed = admins.remove(uuid) != null;
        if (removed) {
            saveAsync();
        }
        return removed;
    }

    public boolean isRegistered(UUID uuid) {
        return admins.containsKey(uuid);
    }

    public Map<UUID, String> registeredAdmins() {
        return Collections.unmodifiableMap(admins);
    }

    public static final class AdminSnapshot {
        private final UUID uuid;
        private final String name;
        private final boolean online;
        private final String world;
        private final double x;
        private final double y;
        private final double z;

        public AdminSnapshot(UUID uuid, String name, boolean online, String world, double x, double y, double z) {
            this.uuid = uuid;
            this.name = name;
            this.online = online;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public UUID uuid() { return uuid; }
        public String name() { return name; }
        public boolean online() { return online; }
        public String world() { return world; }
        public double x() { return x; }
        public double y() { return y; }
        public double z() { return z; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AdminSnapshot that = (AdminSnapshot) o;
            return online == that.online && Double.compare(that.x, x) == 0 &&
                    Double.compare(that.y, y) == 0 && Double.compare(that.z, z) == 0 &&
                    Objects.equals(uuid, that.uuid) && Objects.equals(name, that.name) &&
                    Objects.equals(world, that.world);
        }

        @Override
        public int hashCode() {
            return Objects.hash(uuid, name, online, world, x, y, z);
        }
    }

    public CompletableFuture<List<AdminSnapshot>> snapshotAsync() {
        CompletableFuture<List<AdminSnapshot>> future = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            List<AdminSnapshot> offline = new ArrayList<>();
            List<CompletableFuture<AdminSnapshot>> onlineFutures = new ArrayList<>();

            for (Map.Entry<UUID, String> entry : admins.entrySet()) {
                UUID uuid = entry.getKey();
                Player player = Bukkit.getPlayer(uuid);

                if (player == null || !player.isOnline()) {
                    offline.add(new AdminSnapshot(uuid, entry.getValue(), false, null, 0, 0, 0));
                    continue;
                }

                CompletableFuture<AdminSnapshot> locationFuture = new CompletableFuture<>();
                Scheduler.scheduleSyncDelayedTask(plugin, () -> {
                    try {
                        locationFuture.complete(new AdminSnapshot(
                                uuid, player.getName(), true,
                                player.getWorld().getName(),
                                player.getLocation().getX(),
                                player.getLocation().getY(),
                                player.getLocation().getZ()));
                    } catch (Throwable t) {
                        locationFuture.complete(new AdminSnapshot(uuid, entry.getValue(), false, null, 0, 0, 0));
                    }
                }, player, 0);
                onlineFutures.add(locationFuture);
            }

            try {
                CompletableFuture.allOf(onlineFutures.toArray(new CompletableFuture[0])).get(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }

            List<AdminSnapshot> result = new ArrayList<>(offline);
            for (CompletableFuture<AdminSnapshot> f : onlineFutures) {
                if (f.isDone() && !f.isCompletedExceptionally()) {
                    AdminSnapshot snap = f.getNow(null);
                    if (snap != null) {
                        result.add(snap);
                    }
                }
            }

            future.complete(result);
        });

        return future;
    }
}
