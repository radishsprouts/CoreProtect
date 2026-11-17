package net.coreprotect.web;

import net.coreprotect.CoreProtect;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * web.yml の設定値を管理する。
 */
public class WebConfig {
    private final CoreProtect plugin;
    private final File configFile;

    private boolean webEnabled;
    private int webPort;
    private String bindAddress;

    private int lookupDefaultRadius;
    private int lookupPageSize;
    private int lookupMaxRows;

    private int containerNetMaxRows;
    private int containerNetTimeoutSeconds;

    private boolean rollbackEnabled;
    private boolean restoreEnabled;
    private boolean purgeEnabled;

    private boolean teleportEnabled;
    private boolean teleportSafeY;
    private boolean teleportNotifyPlayer;

    private int jobRetentionMinutes;

    private boolean mcpEnabled;
    private boolean mcpAllowPrivateOnly;
    private List<String> mcpAllowedCidrs = Collections.emptyList();
    private int mcpMaxRows;
    private int mcpSummaryMaxRows;
    private int mcpTimeoutSeconds;
    private boolean mcpLogRequests;

    public WebConfig(CoreProtect plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "web.yml");
    }

    public void initialize() {
        checkLegacyMigration();
        if (!configFile.exists()) {
            saveDefaultConfig();
        }
        reload();
    }

    private void checkLegacyMigration() {
        File dataFolder = plugin.getDataFolder();
        File legacyAdmins = new File(dataFolder.getParentFile(), "CoreProtectWebUI" + File.separator + "admins.yml");
        File currentAdmins = new File(dataFolder, "admins.yml");

        if (legacyAdmins.exists() && !currentAdmins.exists()) {
            try {
                if (!dataFolder.exists()) {
                    dataFolder.mkdirs();
                }
                Files.copy(legacyAdmins.toPath(), currentAdmins.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                plugin.getLogger().info("[WebUI] 旧 CoreProtectWebUI/admins.yml から管理者を自動引き継ぎました。");
            } catch (IOException e) {
                plugin.getLogger().warning("[WebUI] admins.yml の自動引き継ぎに失敗しました: " + e.getMessage());
            }
        }
    }

    private void saveDefaultConfig() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            try (InputStream in = plugin.getResource("web.yml")) {
                if (in != null) {
                    Files.copy(in, configFile.toPath());
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[WebUI] web.yml の作成に失敗しました: " + e.getMessage());
        }
    }

    public void reload() {
        if (!configFile.exists()) {
            saveDefaultConfig();
        }

        YamlConfiguration c = YamlConfiguration.loadConfiguration(configFile);
        try (InputStream defStream = plugin.getResource("web.yml")) {
            if (defStream != null) {
                c.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defStream, StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) {
        }

        webEnabled = c.getBoolean("web.enabled", true);
        webPort = c.getInt("web.port", 8123);
        bindAddress = c.getString("web.bind-address", "0.0.0.0");

        lookupDefaultRadius = c.getInt("lookup.default-radius", 10);
        lookupPageSize = c.getInt("lookup.page-size", 50);
        lookupMaxRows = c.getInt("lookup.max-rows", 1000);

        containerNetMaxRows = c.getInt("container-net.max-rows", 20000);
        containerNetTimeoutSeconds = c.getInt("container-net.timeout-seconds", 60);

        rollbackEnabled = c.getBoolean("operations.rollback-enabled", true);
        restoreEnabled = c.getBoolean("operations.restore-enabled", true);
        purgeEnabled = c.getBoolean("operations.purge-enabled", false);

        teleportEnabled = c.getBoolean("teleport.enabled", true);
        teleportSafeY = c.getBoolean("teleport.safe-y", false);
        teleportNotifyPlayer = c.getBoolean("teleport.notify-player", true);

        jobRetentionMinutes = c.getInt("job.retention-minutes", 10);

        mcpEnabled = c.getBoolean("mcp.enabled", true);
        mcpAllowPrivateOnly = c.getBoolean("mcp.allow-private-only", true);
        mcpAllowedCidrs = new ArrayList<>(c.getStringList("mcp.allowed-cidrs"));
        mcpMaxRows = c.getInt("mcp.max-rows", 200);
        mcpSummaryMaxRows = c.getInt("mcp.summary-max-rows", 20000);
        mcpTimeoutSeconds = c.getInt("mcp.timeout-seconds", 60);
        mcpLogRequests = c.getBoolean("mcp.log-requests", false);
    }

    public boolean isWebEnabled() { return webEnabled; }
    public int getWebPort() { return webPort; }
    public String getBindAddress() { return bindAddress; }
    public int getLookupDefaultRadius() { return lookupDefaultRadius; }
    public int getLookupPageSize() { return lookupPageSize; }
    public int getLookupMaxRows() { return lookupMaxRows; }
    public int getContainerNetMaxRows() { return containerNetMaxRows; }
    public int getContainerNetTimeoutSeconds() { return containerNetTimeoutSeconds; }
    public boolean isRollbackEnabled() { return rollbackEnabled; }
    public boolean isRestoreEnabled() { return restoreEnabled; }
    public boolean isPurgeEnabled() { return purgeEnabled; }
    public boolean isTeleportEnabled() { return teleportEnabled; }
    public boolean isTeleportSafeY() { return teleportSafeY; }
    public boolean isTeleportNotifyPlayer() { return teleportNotifyPlayer; }
    public int getJobRetentionMinutes() { return jobRetentionMinutes; }
    public boolean isMcpEnabled() { return mcpEnabled; }
    public boolean isMcpAllowPrivateOnly() { return mcpAllowPrivateOnly; }
    public List<String> getMcpAllowedCidrs() { return mcpAllowedCidrs; }
    public int getMcpMaxRows() { return mcpMaxRows; }
    public int getMcpSummaryMaxRows() { return mcpSummaryMaxRows; }
    public int getMcpTimeoutSeconds() { return mcpTimeoutSeconds; }
    public boolean isMcpLogRequests() { return mcpLogRequests; }
}
