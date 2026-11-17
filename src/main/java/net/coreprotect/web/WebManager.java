package net.coreprotect.web;

import net.coreprotect.CoreProtect;
import net.coreprotect.command.WebCommand;
import net.coreprotect.thread.Scheduler;
import net.coreprotect.web.core.ActivitySummaryService;
import net.coreprotect.web.core.AdminRegistry;
import net.coreprotect.web.core.ContainerNetService;
import net.coreprotect.web.core.JobManager;
import net.coreprotect.web.core.LookupService;
import net.coreprotect.web.core.OperationService;
import net.coreprotect.web.core.ServerStatusService;
import net.coreprotect.web.core.TeleportService;
import net.coreprotect.web.mcp.McpToolRegistry;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;

/**
 * WebUI / MCP モジュール全体のライフサイクル（初期化・起動・停止・リロード）を統括する。
 */
public class WebManager {
    private static WebManager instance;

    private final CoreProtect plugin;
    private final WebConfig config;
    private final JobManager jobManager;
    private final AdminRegistry adminRegistry;
    private final WebServer webServer;
    private final WebCommand webCommand;

    private WebManager(CoreProtect plugin) {
        this.plugin = plugin;
        this.config = new WebConfig(plugin);
        this.config.initialize();

        this.adminRegistry = new AdminRegistry(plugin);
        this.adminRegistry.load();

        LookupService lookupService = new LookupService(plugin);
        OperationService operationService = new OperationService(plugin);
        TeleportService teleportService = new TeleportService(plugin, config, adminRegistry);
        ContainerNetService containerNetService = new ContainerNetService(plugin);
        ActivitySummaryService activitySummaryService = new ActivitySummaryService(plugin);
        ServerStatusService serverStatusService = new ServerStatusService(plugin);

        this.jobManager = new JobManager(plugin);
        this.jobManager.setRetentionMinutes(config.getJobRetentionMinutes());

        McpToolRegistry mcpToolRegistry = new McpToolRegistry(plugin, config, lookupService,
                containerNetService, activitySummaryService, serverStatusService, jobManager);

        this.webServer = new WebServer(plugin, config, lookupService, operationService,
                jobManager, adminRegistry, teleportService, containerNetService, serverStatusService, mcpToolRegistry);

        this.webCommand = new WebCommand(plugin, config, webServer, adminRegistry);
    }

    public static synchronized void initialize(CoreProtect plugin) {
        if (instance != null) {
            return;
        }

        checkLegacyPlugin(plugin);

        instance = new WebManager(plugin);

        PluginCommand cpweb = plugin.getCommand("cpweb");
        if (cpweb != null) {
            cpweb.setExecutor(instance.webCommand);
            cpweb.setTabCompleter(new net.coreprotect.command.TabHandler());
        }

        if (instance.config.isWebEnabled()) {
            instance.webServer.start();
        } else {
            plugin.getLogger().info("[WebUI] web.yml で web.enabled が false のため、Web UI は起動しません。");
        }

        startJobCleanupTask(plugin, instance.jobManager);
    }

    private static void checkLegacyPlugin(CoreProtect plugin) {
        Plugin legacy = Bukkit.getPluginManager().getPlugin("CoreProtectWebUI");
        if (legacy != null && legacy.isEnabled()) {
            plugin.getLogger().warning("[WebUI] 警告: 外部プラグイン 'CoreProtectWebUI' が検出されました！");
            plugin.getLogger().warning("[WebUI] CoreProtect 本体に WebUI が統合されたため、二重起動・ポート重複を防ぐために CoreProtectWebUI.jar を plugins/ から削除してください。");
        }
    }

    private static void startJobCleanupTask(CoreProtect plugin, JobManager jobManager) {
        Scheduler.scheduleSyncDelayedTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (instance != null) {
                    jobManager.cleanup();
                    startJobCleanupTask(plugin, jobManager);
                }
            }
        }, null, 20 * 60);
    }

    public static synchronized void reload() {
        if (instance == null) {
            return;
        }

        int oldPort = instance.config.getWebPort();
        String oldBind = instance.config.getBindAddress();
        boolean oldEnabled = instance.config.isWebEnabled();

        instance.config.reload();
        instance.jobManager.setRetentionMinutes(instance.config.getJobRetentionMinutes());

        boolean needRestart = (oldPort != instance.config.getWebPort())
                || !oldBind.equals(instance.config.getBindAddress())
                || (oldEnabled != instance.config.isWebEnabled());

        if (needRestart) {
            instance.webServer.stop();
            if (instance.config.isWebEnabled()) {
                instance.webServer.start();
            }
        }
    }

    public static synchronized void shutdown() {
        if (instance == null) {
            return;
        }

        if (instance.webServer != null) {
            instance.webServer.stop();
        }
        if (instance.jobManager != null) {
            instance.jobManager.shutdown();
        }

        instance = null;
    }

    public static WebManager getInstance() {
        return instance;
    }

    public WebConfig getConfig() {
        return config;
    }

    public WebServer getWebServer() {
        return webServer;
    }

    public JobManager getJobManager() {
        return jobManager;
    }

    public AdminRegistry getAdminRegistry() {
        return adminRegistry;
    }

    public WebCommand getWebCommand() {
        return webCommand;
    }
}
