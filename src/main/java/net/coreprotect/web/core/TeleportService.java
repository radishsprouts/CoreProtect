package net.coreprotect.web.core;

import net.coreprotect.CoreProtect;
import net.coreprotect.thread.Scheduler;
import net.coreprotect.web.WebConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Web UI の座標クリックから、登録済み管理者を対象座標へテレポートさせる。
 */
public class TeleportService {
    public enum Result {
        OK,
        DISABLED,
        NOT_REGISTERED,
        OFFLINE,
        WORLD_NOT_FOUND,
        FAILED
    }

    private final CoreProtect plugin;
    private final WebConfig config;
    private final AdminRegistry adminRegistry;

    public TeleportService(CoreProtect plugin, WebConfig config, AdminRegistry adminRegistry) {
        this.plugin = plugin;
        this.config = config;
        this.adminRegistry = adminRegistry;
    }

    public CompletableFuture<Result> teleport(UUID adminId, String worldName, double x, double y, double z) {
        CompletableFuture<Result> future = new CompletableFuture<>();

        if (!config.isTeleportEnabled()) {
            future.complete(Result.DISABLED);
            return future;
        }

        if (!adminRegistry.isRegistered(adminId)) {
            future.complete(Result.NOT_REGISTERED);
            return future;
        }

        Scheduler.scheduleSyncDelayedTask(plugin, () -> {
            Player player = Bukkit.getPlayer(adminId);
            if (player == null || !player.isOnline()) {
                future.complete(Result.OFFLINE);
                return;
            }

            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                future.complete(Result.WORLD_NOT_FOUND);
                return;
            }

            Scheduler.scheduleSyncDelayedTask(plugin, () -> {
                float yaw = player.getLocation().getYaw();
                float pitch = player.getLocation().getPitch();

                Runnable doTeleport = () -> executeTeleport(player, world, x, y, z, yaw, pitch, future);

                if (config.isTeleportSafeY()) {
                    Scheduler.scheduleSyncDelayedTask(plugin, doTeleport, new Location(world, x, 0, z), 0);
                } else {
                    doTeleport.run();
                }
            }, player, 0);
        }, null, 0);

        return future;
    }

    private void executeTeleport(Player player, World world, double x, double y, double z,
                                float yaw, float pitch, CompletableFuture<Result> future) {
        double targetX = x + 0.5;
        double targetZ = z + 0.5;
        double targetY = config.isTeleportSafeY()
                ? world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1
                : y;

        Location destination = new Location(world, targetX, targetY, targetZ, yaw, pitch);

        player.teleportAsync(destination).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                if (config.isTeleportNotifyPlayer()) {
                    player.sendMessage("§b[CoreProtect]§r Web UI から §e" + world.getName() + " (" +
                            (int) targetX + ", " + (int) targetY + ", " + (int) targetZ + ")§r へ移動しました。");
                }
                plugin.getLogger().info("[WebUI] 管理者 " + player.getName() + " を " + world.getName() +
                        " (" + (int) targetX + ", " + (int) targetY + ", " + (int) targetZ + ") へテレポートしました。");
                future.complete(Result.OK);
            } else {
                future.complete(Result.FAILED);
            }
        });
    }
}
