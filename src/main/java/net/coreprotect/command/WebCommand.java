package net.coreprotect.command;

import net.coreprotect.CoreProtect;
import net.coreprotect.web.WebConfig;
import net.coreprotect.web.WebServer;
import net.coreprotect.web.core.AdminRegistry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

/**
 * /co web および /cpweb コマンド。
 * Webサーバーの制御とテレポート対象管理者の登録を行う。
 */
public class WebCommand implements CommandExecutor {
    private static WebCommand instance;

    private final CoreProtect plugin;
    private final WebConfig config;
    private final WebServer webServer;
    private final AdminRegistry adminRegistry;

    public WebCommand(CoreProtect plugin, WebConfig config, WebServer webServer, AdminRegistry adminRegistry) {
        this.plugin = plugin;
        this.config = config;
        this.webServer = webServer;
        this.adminRegistry = adminRegistry;
        instance = this;
    }

    public static WebCommand getInstance() {
        return instance;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        run(sender, args);
        return true;
    }

    public static void runCommand(CommandSender sender, boolean permission, String[] args) {
        if (!permission) {
            sender.sendMessage("§cこのコマンドを実行する権限がありません。");
            return;
        }

        if (instance == null) {
            sender.sendMessage("§cWeb UI モジュールが初期化されていません。");
            return;
        }

        // args[0] は "web" または "webui" なので、それ以降の引数を渡す
        String[] subArgs = new String[Math.max(0, args.length - 1)];
        if (args.length > 1) {
            System.arraycopy(args, 1, subArgs, 0, subArgs.length);
        }

        instance.run(sender, subArgs);
    }

    public void run(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            handleStatus(sender);
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "start":
                handleStart(sender);
                break;
            case "stop":
                handleStop(sender);
                break;
            case "restart":
            case "reload":
                handleReload(sender);
                break;
            case "register":
                handleRegister(sender);
                break;
            case "unregister":
                handleUnregister(sender);
                break;
            case "admins":
                handleAdmins(sender);
                break;
            default:
                sendUsage(sender);
                break;
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§b[CoreProtect]§r /co web <status|start|stop|restart|register|unregister|admins>");
    }

    private void handleStatus(CommandSender sender) {
        String state = webServer.isRunning() ? "§a稼働中" : "§c停止中";
        sender.sendMessage("§b[CoreProtect]§r Web UI 状態: " + state + "§r  URL: http://" + config.getBindAddress() + ":" + config.getWebPort() + "/");

        if (webServer.isRunning() && config.isMcpEnabled()) {
            String scope = config.isMcpAllowPrivateOnly() ? "§a有効（プライベートIP帯のみ）" : "§e有効（接続元制限なし）";
            sender.sendMessage("§b[CoreProtect]§r MCP サーバー: " + scope + "§r  URL: http://" + config.getBindAddress() + ":" + config.getWebPort() + "/mcp");
        } else {
            sender.sendMessage("§b[CoreProtect]§r MCP サーバー: §c無効");
        }
    }

    private void handleStart(CommandSender sender) {
        webServer.start();
        sender.sendMessage("§b[CoreProtect]§r Web UI を起動しました。");
    }

    private void handleStop(CommandSender sender) {
        webServer.stop();
        sender.sendMessage("§b[CoreProtect]§r Web UI を停止しました。");
    }

    private void handleReload(CommandSender sender) {
        config.reload();
        webServer.stop();
        if (config.isWebEnabled()) {
            webServer.start();
        }
        sender.sendMessage("§b[CoreProtect]§r Web UI 設定を再読み込みしました。");
    }

    private void handleRegister(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます。");
            return;
        }
        Player player = (Player) sender;
        boolean isNew = adminRegistry.register(player.getUniqueId(), player.getName());
        sender.sendMessage("§b[CoreProtect]§r " + (isNew ? "管理者として登録しました。" : "管理者情報を更新しました。") +
                " Web UI からテレポート対象として選択できます。");
    }

    private void handleUnregister(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます。");
            return;
        }
        Player player = (Player) sender;
        boolean removed = adminRegistry.unregister(player.getUniqueId());
        sender.sendMessage(removed ? "§b[CoreProtect]§r 管理者登録を解除しました。" : "§c登録されていません。");
    }

    private void handleAdmins(CommandSender sender) {
        Map<UUID, String> admins = adminRegistry.registeredAdmins();
        if (admins.isEmpty()) {
            sender.sendMessage("§b[CoreProtect]§r 登録済みの管理者はいません。");
            return;
        }
        sender.sendMessage("§b[CoreProtect]§r 登録済み管理者 (" + admins.size() + "人): " + String.join(", ", admins.values()));
    }
}
