package net.coreprotect.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.coreprotect.CoreProtect;
import net.coreprotect.web.core.ActionType;
import net.coreprotect.web.core.AdminRegistry;
import net.coreprotect.web.core.ContainerNetService;
import net.coreprotect.web.core.Job;
import net.coreprotect.web.core.JobManager;
import net.coreprotect.web.core.LookupQuery;
import net.coreprotect.web.core.LookupRow;
import net.coreprotect.web.core.LookupService;
import net.coreprotect.web.core.OperationService;
import net.coreprotect.web.core.ServerStatusService;
import net.coreprotect.web.core.TeleportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * /api/* 配下のリクエストを処理する。
 */
public class ApiHandler implements HttpHandler {
    private final CoreProtect plugin;
    private final WebConfig config;
    private final LookupService lookupService;
    private final OperationService operationService;
    private final JobManager jobManager;
    private final AdminRegistry adminRegistry;
    private final TeleportService teleportService;
    private final ContainerNetService containerNetService;
    private final ServerStatusService serverStatusService;

    public ApiHandler(CoreProtect plugin, WebConfig config,
                      LookupService lookupService, OperationService operationService, JobManager jobManager,
                      AdminRegistry adminRegistry, TeleportService teleportService, ContainerNetService containerNetService,
                      ServerStatusService serverStatusService) {
        this.plugin = plugin;
        this.config = config;
        this.lookupService = lookupService;
        this.operationService = operationService;
        this.jobManager = jobManager;
        this.adminRegistry = adminRegistry;
        this.teleportService = teleportService;
        this.containerNetService = containerNetService;
        this.serverStatusService = serverStatusService;
    }

    @Override
    public void handle(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            try {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.sendResponseHeaders(204, -1);
            } catch (IOException ignored) {
            }
            return;
        }

        try {
            if (path.equals("/api/status")) {
                handleStatus(exchange);
            } else if (path.equals("/api/lookup")) {
                handleLookup(exchange);
            } else if (path.equals("/api/session")) {
                handleSession(exchange);
            } else if (path.equals("/api/container-net")) {
                handleContainerNet(exchange);
            } else if (path.equals("/api/rollback")) {
                handleOperation(exchange, "rollback");
            } else if (path.equals("/api/restore")) {
                handleOperation(exchange, "restore");
            } else if (path.equals("/api/purge")) {
                handlePurge(exchange);
            } else if (path.equals("/api/jobs")) {
                handleJobs(exchange);
            } else if (path.startsWith("/api/jobs/")) {
                handleJob(exchange, path.substring("/api/jobs/".length()));
            } else if (path.equals("/api/admins")) {
                handleAdmins(exchange);
            } else if (path.equals("/api/teleport")) {
                handleTeleport(exchange);
            } else {
                Http.sendError(exchange, 404, "不明なAPIです");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[WebUI] APIリクエスト処理中にエラーが発生しました (" + path + "): " + e.getMessage());
            try {
                Http.sendError(exchange, 500, "サーバー内部エラー: " + e.getMessage());
            } catch (IOException ignored) {
            }
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;

        try {
            ServerStatusService.Snapshot snapshot = serverStatusService.snapshot();

            JsonObject root = new JsonObject();
            root.addProperty("coreProtectAvailable", snapshot.coreProtectAvailable());
            if (snapshot.coreProtectApiVersion() != null) {
                root.addProperty("coreProtectApiVersion", snapshot.coreProtectApiVersion());
            }
            root.addProperty("folia", snapshot.folia());
            root.addProperty("databaseEngine", snapshot.databaseEngine());
            root.addProperty("pendingQueueSize", snapshot.pendingQueueSize());

            JsonArray worlds = new JsonArray();
            for (String w : snapshot.worlds()) worlds.add(w);
            root.add("worlds", worlds);

            JsonArray players = new JsonArray();
            for (String p : snapshot.onlinePlayers()) players.add(p);
            root.add("onlinePlayers", players);

            JsonObject operations = new JsonObject();
            operations.addProperty("rollbackEnabled", config.isRollbackEnabled());
            operations.addProperty("restoreEnabled", config.isRestoreEnabled());
            operations.addProperty("purgeEnabled", config.isPurgeEnabled());
            operations.addProperty("teleportEnabled", config.isTeleportEnabled());
            root.add("operations", operations);

            JsonArray jobs = new JsonArray();
            for (Job job : jobManager.all().values()) {
                jobs.add(jobToJson(job));
            }
            root.add("jobs", jobs);

            Http.sendJson(exchange, 200, root);
        } catch (Exception e) {
            Http.sendError(exchange, 500, "ステータス取得に失敗しました: " + rootMessage(e));
        }
    }

    private void handleLookup(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;

        Map<String, String> q = Http.parseQuery(exchange.getRequestURI().getQuery());
        LookupQuery query = buildQuery(q);

        try {
            LookupService.Outcome outcome = lookupService.lookup(query).get(30, TimeUnit.SECONDS);
            Http.sendJson(exchange, 200, buildLookupResponse(outcome));
        } catch (TimeoutException e) {
            Http.sendError(exchange, 504, "検索がタイムアウトしました");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Http.sendError(exchange, 500, "検索が中断されました");
        } catch (ExecutionException e) {
            Http.sendError(exchange, 500, "検索に失敗しました: " + rootMessage(e));
        }
    }

    private void handleSession(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;

        Map<String, String> q = Http.parseQuery(exchange.getRequestURI().getQuery());
        String user = q.get("user");
        if (user == null || user.trim().isEmpty()) {
            Http.sendError(exchange, 400, "user パラメータが必要です");
            return;
        }

        int timeSeconds = LookupQuery.parseTimeToSeconds(q.get("time"), 604800);

        try {
            LookupService.Outcome outcome = lookupService.session(user, timeSeconds).get(30, TimeUnit.SECONDS);
            Http.sendJson(exchange, 200, buildLookupResponse(outcome));
        } catch (TimeoutException e) {
            Http.sendError(exchange, 504, "検索がタイムアウトしました");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Http.sendError(exchange, 500, "検索が中断されました");
        } catch (ExecutionException e) {
            Http.sendError(exchange, 500, "検索に失敗しました: " + rootMessage(e));
        }
    }

    private void handleContainerNet(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;

        Map<String, String> q = Http.parseQuery(exchange.getRequestURI().getQuery());
        LookupQuery query = buildQuery(q);

        try {
            ContainerNetService.Result result = containerNetService
                    .aggregate(query, config.getContainerNetMaxRows())
                    .get(config.getContainerNetTimeoutSeconds(), TimeUnit.SECONDS);
            Http.sendJson(exchange, 200, buildContainerNetResponse(result));
        } catch (TimeoutException e) {
            Http.sendError(exchange, 504, "集計がタイムアウトしました");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Http.sendError(exchange, 500, "集計が中断されました");
        } catch (ExecutionException e) {
            Http.sendError(exchange, 500, "集計に失敗しました: " + rootMessage(e));
        }
    }

    private JsonObject buildContainerNetResponse(ContainerNetService.Result result) {
        JsonObject root = new JsonObject();
        JsonArray rows = new JsonArray();

        for (ContainerNetService.Entry entry : result.entries()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("player", entry.player());
            obj.addProperty("item", entry.item());
            obj.addProperty("added", entry.added());
            obj.addProperty("removed", entry.removed());
            obj.addProperty("net", entry.net());
            obj.addProperty("txCount", entry.txCount());
            obj.addProperty("rolledBackTx", entry.rolledBackTx());
            obj.addProperty("firstSeen", entry.firstSeen());
            obj.addProperty("lastSeen", entry.lastSeen());
            rows.add(obj);
        }

        root.add("rows", rows);
        root.addProperty("scannedRows", result.scannedRows());
        root.addProperty("truncated", result.truncated());

        JsonArray warnings = new JsonArray();
        for (String w : result.warnings()) warnings.add(w);
        root.add("warnings", warnings);

        return root;
    }

    private JsonObject buildLookupResponse(LookupService.Outcome outcome) {
        JsonObject root = new JsonObject();
        JsonArray rows = new JsonArray();

        for (LookupRow row : outcome.rows()) {
            rows.add(row.toJson(false, true));
        }

        root.add("rows", rows);
        root.addProperty("count", outcome.rows().size());

        JsonArray warnings = new JsonArray();
        for (String w : outcome.warnings()) warnings.add(w);
        root.add("warnings", warnings);

        return root;
    }

    private void handleOperation(HttpExchange exchange, String type) throws IOException {
        if (!requireMethod(exchange, "POST")) return;

        boolean enabled = type.equals("rollback") ? config.isRollbackEnabled() : config.isRestoreEnabled();
        if (!enabled) {
            Http.sendError(exchange, 403, (type.equals("rollback") ? "ロールバック" : "リストア") + "は設定で無効化されています");
            return;
        }

        JsonObject body = parseJsonBody(exchange);
        if (body == null) {
            Http.sendError(exchange, 400, "JSONの解析に失敗しました");
            return;
        }

        if (!body.has("confirm") || !body.get("confirm").getAsBoolean()) {
            Http.sendError(exchange, 400, "confirm: true が必要です");
            return;
        }

        LookupQuery query = buildQueryFromJson(body);
        String remoteAddress = remoteAddressOf(exchange);

        Job job = jobManager.submit(type, () -> {
            List<String[]> result = type.equals("rollback") ? operationService.rollback(query) : operationService.restore(query);
            int affected = result != null ? result.size() : 0;
            plugin.getLogger().info("[" + remoteAddress + "] " + (type.equals("rollback") ? "ロールバック" : "リストア") +
                    "を実行しました。対象件数=" + affected);
            return affected;
        });

        JsonObject root = new JsonObject();
        root.addProperty("jobId", job.id);
        root.addProperty("status", job.status.name());
        Http.sendJson(exchange, 202, root);
    }

    private void handlePurge(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;

        if (!config.isPurgeEnabled()) {
            Http.sendError(exchange, 403, "パージは設定で無効化されています");
            return;
        }

        JsonObject body = parseJsonBody(exchange);
        if (body == null) {
            Http.sendError(exchange, 400, "JSONの解析に失敗しました");
            return;
        }

        if (!body.has("confirm") || !body.get("confirm").getAsBoolean()) {
            Http.sendError(exchange, 400, "confirm: true が必要です");
            return;
        }

        if (!body.has("time")) {
            Http.sendError(exchange, 400, "time パラメータが必要です");
            return;
        }

        int timeSeconds = LookupQuery.parseTimeToSeconds(body.get("time").getAsString(), -1);
        if (timeSeconds <= 0) {
            Http.sendError(exchange, 400, "不正な時間指定です: " + body.get("time").getAsString());
            return;
        }

        String remoteAddress = remoteAddressOf(exchange);

        Job job = jobManager.submit("purge", () -> {
            plugin.getLogger().info("[" + remoteAddress + "] パージを実行します（time=" + timeSeconds + "秒前）");
            operationService.purge(timeSeconds);
            return 0;
        });

        JsonObject root = new JsonObject();
        root.addProperty("jobId", job.id);
        root.addProperty("status", job.status.name());
        Http.sendJson(exchange, 202, root);
    }

    private void handleJobs(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;

        JsonArray array = new JsonArray();
        for (Job job : jobManager.all().values()) {
            array.add(jobToJson(job));
        }

        JsonObject root = new JsonObject();
        root.add("jobs", array);
        Http.sendJson(exchange, 200, root);
    }

    private void handleJob(HttpExchange exchange, String jobId) throws IOException {
        if (!requireMethod(exchange, "GET")) return;

        Job job = jobManager.get(jobId);
        if (job == null) {
            Http.sendError(exchange, 404, "ジョブが見つかりません: " + jobId);
            return;
        }

        Http.sendJson(exchange, 200, jobToJson(job));
    }

    private void handleAdmins(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;

        try {
            List<AdminRegistry.AdminSnapshot> admins = adminRegistry.snapshotAsync().get(3, TimeUnit.SECONDS);
            JsonArray array = new JsonArray();
            for (AdminRegistry.AdminSnapshot a : admins) {
                JsonObject obj = new JsonObject();
                obj.addProperty("uuid", a.uuid().toString());
                obj.addProperty("name", a.name());
                obj.addProperty("online", a.online());
                if (a.world() != null) {
                    obj.addProperty("world", a.world());
                    obj.addProperty("x", a.x());
                    obj.addProperty("y", a.y());
                    obj.addProperty("z", a.z());
                }
                array.add(obj);
            }

            JsonObject root = new JsonObject();
            root.add("admins", array);
            Http.sendJson(exchange, 200, root);
        } catch (Exception e) {
            Http.sendError(exchange, 500, "管理者一覧の取得に失敗しました: " + rootMessage(e));
        }
    }

    private void handleTeleport(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;

        JsonObject body = parseJsonBody(exchange);
        if (body == null) {
            Http.sendError(exchange, 400, "JSONの解析に失敗しました");
            return;
        }

        if (!body.has("adminId") || !body.has("world") || !body.has("x") || !body.has("y") || !body.has("z")) {
            Http.sendError(exchange, 400, "adminId, world, x, y, z が必要です");
            return;
        }

        UUID adminId;
        try {
            adminId = UUID.fromString(body.get("adminId").getAsString());
        } catch (IllegalArgumentException e) {
            Http.sendError(exchange, 400, "不正なUUIDです");
            return;
        }

        String world = body.get("world").getAsString();
        double x = body.get("x").getAsDouble();
        double y = body.get("y").getAsDouble();
        double z = body.get("z").getAsDouble();

        try {
            TeleportService.Result result = teleportService.teleport(adminId, world, x, y, z).get(5, TimeUnit.SECONDS);

            JsonObject root = new JsonObject();
            root.addProperty("result", result.name());

            int status = switchStatus(result);
            Http.sendJson(exchange, status, root);
        } catch (Exception e) {
            Http.sendError(exchange, 500, "テレポート処理でエラーが発生しました: " + rootMessage(e));
        }
    }

    private int switchStatus(TeleportService.Result result) {
        switch (result) {
            case OK:
                return 200;
            case DISABLED:
                return 403;
            case NOT_REGISTERED:
            case WORLD_NOT_FOUND:
                return 404;
            case OFFLINE:
                return 409;
            case FAILED:
            default:
                return 500;
        }
    }

    private LookupQuery buildQuery(Map<String, String> q) {
        LookupQuery query = new LookupQuery();
        query.timeSeconds = LookupQuery.parseTimeToSeconds(q.get("time"), 86400);

        query.users = parseCsv(q.get("users"));
        query.excludeUsers = parseCsv(q.get("excludeUsers"));
        query.blockNames = parseCsv(q.get("blocks"));
        query.excludeBlockNames = parseCsv(q.get("excludeBlocks"));

        String actionsStr = q.get("actions");
        if (actionsStr != null && !actionsStr.trim().isEmpty()) {
            for (String part : actionsStr.split(",")) {
                try {
                    query.actionIds.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (query.actionIds.isEmpty()) {
            for (ActionType type : ActionType.values()) {
                query.actionIds.add(type.id());
            }
        }

        query.world = q.get("world");
        query.x = parseDouble(q.get("x"));
        query.y = parseDouble(q.get("y"));
        query.z = parseDouble(q.get("z"));

        try {
            query.radius = Integer.parseInt(q.getOrDefault("radius", String.valueOf(config.getLookupDefaultRadius())));
        } catch (NumberFormatException e) {
            query.radius = config.getLookupDefaultRadius();
        }

        try {
            query.offset = Math.max(0, Integer.parseInt(q.getOrDefault("offset", "0")));
        } catch (NumberFormatException e) {
            query.offset = 0;
        }

        try {
            int rowCount = Integer.parseInt(q.getOrDefault("rowCount", String.valueOf(config.getLookupPageSize())));
            query.rowCount = Math.max(1, Math.min(rowCount, config.getLookupMaxRows()));
        } catch (NumberFormatException e) {
            query.rowCount = config.getLookupPageSize();
        }

        return query;
    }

    private LookupQuery buildQueryFromJson(JsonObject obj) {
        LookupQuery query = new LookupQuery();

        if (obj.has("time")) {
            query.timeSeconds = LookupQuery.parseTimeToSeconds(obj.get("time").getAsString(), 86400);
        }

        query.users = parseJsonStringList(obj.get("users"));
        query.excludeUsers = parseJsonStringList(obj.get("excludeUsers"));
        query.blockNames = parseJsonStringList(obj.get("blocks"));
        query.excludeBlockNames = parseJsonStringList(obj.get("excludeBlocks"));

        if (obj.has("actions") && obj.get("actions").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("actions")) {
                try {
                    query.actionIds.add(el.getAsInt());
                } catch (Exception ignored) {
                }
            }
        }

        if (query.actionIds.isEmpty()) {
            for (ActionType type : ActionType.values()) {
                query.actionIds.add(type.id());
            }
        }

        if (obj.has("world") && !obj.get("world").isJsonNull()) {
            query.world = obj.get("world").getAsString();
        }
        if (obj.has("x") && !obj.get("x").isJsonNull()) {
            query.x = obj.get("x").getAsDouble();
        }
        if (obj.has("y") && !obj.get("y").isJsonNull()) {
            query.y = obj.get("y").getAsDouble();
        }
        if (obj.has("z") && !obj.get("z").isJsonNull()) {
            query.z = obj.get("z").getAsDouble();
        }

        if (obj.has("radius")) {
            query.radius = obj.get("radius").getAsInt();
        } else {
            query.radius = config.getLookupDefaultRadius();
        }

        return query;
    }

    private static List<String> parseCsv(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return list;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    private static List<String> parseJsonStringList(JsonElement element) {
        List<String> list = new ArrayList<>();
        if (element == null || element.isJsonNull()) {
            return list;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                String s = item.getAsString().trim();
                if (!s.isEmpty()) {
                    list.add(s);
                }
            }
        } else if (element.isJsonPrimitive()) {
            list.addAll(parseCsv(element.getAsString()));
        }
        return list;
    }

    private static Double parseDouble(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.sendError(exchange, 405, method + " メソッドのみ受け付けます");
            return false;
        }
        return true;
    }

    private static JsonObject parseJsonBody(HttpExchange exchange) {
        try {
            String body = Http.readBody(exchange);
            JsonElement element = JsonParser.parseString(body);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject jobToJson(Job job) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", job.id);
        obj.addProperty("type", job.type);
        obj.addProperty("status", job.status.name());
        obj.addProperty("createdAt", job.createdAt.toString());
        if (job.finishedAt != null) {
            obj.addProperty("finishedAt", job.finishedAt.toString());
        }
        obj.addProperty("affectedRows", job.affectedRows);
        if (job.errorMessage != null) {
            obj.addProperty("errorMessage", job.errorMessage);
        }
        return obj;
    }

    private static String remoteAddressOf(HttpExchange exchange) {
        if (exchange.getRemoteAddress() == null) {
            return "unknown";
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
    }
}
