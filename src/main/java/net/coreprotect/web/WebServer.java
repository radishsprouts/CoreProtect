package net.coreprotect.web;

import com.sun.net.httpserver.HttpServer;
import net.coreprotect.CoreProtect;
import net.coreprotect.web.core.AdminRegistry;
import net.coreprotect.web.core.ContainerNetService;
import net.coreprotect.web.core.JobManager;
import net.coreprotect.web.core.LookupService;
import net.coreprotect.web.core.OperationService;
import net.coreprotect.web.core.ServerStatusService;
import net.coreprotect.web.core.TeleportService;
import net.coreprotect.web.mcp.McpHandler;
import net.coreprotect.web.mcp.McpToolRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 埋め込みHTTPサーバーの起動・停止。同一ポートで静的Web UIとAPIの両方を配信する。
 */
public class WebServer {
    private final CoreProtect plugin;
    private final WebConfig config;
    private final LookupService lookupService;
    private final OperationService operationService;
    private final JobManager jobManager;
    private final AdminRegistry adminRegistry;
    private final TeleportService teleportService;
    private final ContainerNetService containerNetService;
    private final ServerStatusService serverStatusService;
    private final McpToolRegistry mcpToolRegistry;

    private HttpServer server;
    private ExecutorService executor;

    public WebServer(CoreProtect plugin, WebConfig config,
                     LookupService lookupService, OperationService operationService, JobManager jobManager,
                     AdminRegistry adminRegistry, TeleportService teleportService, ContainerNetService containerNetService,
                     ServerStatusService serverStatusService, McpToolRegistry mcpToolRegistry) {
        this.plugin = plugin;
        this.config = config;
        this.lookupService = lookupService;
        this.operationService = operationService;
        this.jobManager = jobManager;
        this.adminRegistry = adminRegistry;
        this.teleportService = teleportService;
        this.containerNetService = containerNetService;
        this.serverStatusService = serverStatusService;
        this.mcpToolRegistry = mcpToolRegistry;
    }

    public boolean isRunning() {
        return server != null;
    }

    public synchronized void start() {
        if (server != null) {
            stop();
        }

        try {
            server = HttpServer.create(new InetSocketAddress(config.getBindAddress(), config.getWebPort()), 0);
            server.createContext("/api", new ApiHandler(plugin, config, lookupService, operationService,
                    jobManager, adminRegistry, teleportService, containerNetService, serverStatusService));
            server.createContext("/", new StaticFileHandler(plugin));

            if (config.isMcpEnabled()) {
                server.createContext("/mcp", new McpHandler(plugin, config, mcpToolRegistry));
            }

            executor = Executors.newFixedThreadPool(8, runnable -> {
                Thread thread = new Thread(runnable, "coreprotect-web-http");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.start();

            plugin.getLogger().info("[WebUI] Web UI を起動しました: http://" + config.getBindAddress() + ":" + config.getWebPort() + "/");
            if (config.isMcpEnabled()) {
                plugin.getLogger().info("[WebUI] MCPサーバーを起動しました: http://" + config.getBindAddress() + ":" + config.getWebPort() + "/mcp" +
                        (config.isMcpAllowPrivateOnly() ? "（プライベートIPアドレス帯からの接続のみ許可）" : "（接続元制限なし）"));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[WebUI] Web UI の起動に失敗しました: " + e.getMessage());
            server = null;
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            plugin.getLogger().info("[WebUI] Web UI を停止しました。");
        }

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
