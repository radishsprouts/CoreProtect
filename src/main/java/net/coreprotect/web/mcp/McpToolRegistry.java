package net.coreprotect.web.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.web.WebConfig;
import net.coreprotect.web.core.ActionType;
import net.coreprotect.web.core.ActivitySummaryService;
import net.coreprotect.web.core.ContainerNetService;
import net.coreprotect.web.core.Job;
import net.coreprotect.web.core.JobManager;
import net.coreprotect.web.core.LookupQuery;
import net.coreprotect.web.core.LookupRow;
import net.coreprotect.web.core.LookupService;
import net.coreprotect.web.core.ServerStatusService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MCPで公開するツール群の定義と既存サービスへの結線。
 */
public class McpToolRegistry {
    private static final String[] ACTION_NAMES = buildActionNames();

    private final CoreProtect plugin;
    private final WebConfig config;
    private final LookupService lookupService;
    private final ContainerNetService containerNetService;
    private final ActivitySummaryService activitySummaryService;
    private final ServerStatusService serverStatusService;
    private final JobManager jobManager;

    private final Map<String, McpTool> tools;

    public McpToolRegistry(CoreProtect plugin, WebConfig config, LookupService lookupService,
                           ContainerNetService containerNetService, ActivitySummaryService activitySummaryService,
                           ServerStatusService serverStatusService, JobManager jobManager) {
        this.plugin = plugin;
        this.config = config;
        this.lookupService = lookupService;
        this.containerNetService = containerNetService;
        this.activitySummaryService = activitySummaryService;
        this.serverStatusService = serverStatusService;
        this.jobManager = jobManager;
        this.tools = buildTools();
    }

    public List<McpTool> tools() {
        return Collections.unmodifiableList(new ArrayList<>(tools.values()));
    }

    public McpTool get(String name) {
        return tools.get(name);
    }

    private Map<String, McpTool> buildTools() {
        List<McpTool> list = Arrays.asList(
                toolGetServerStatus(),
                toolActivitySummary(),
                toolLookupActions(),
                toolBlockHistoryAt(),
                toolPlayerSessionHistory(),
                toolContainerNetSummary(),
                toolListJobs());

        Map<String, McpTool> map = new LinkedHashMap<>();
        for (McpTool tool : list) {
            map.put(tool.name(), tool);
        }
        return map;
    }

    private McpTool toolGetServerStatus() {
        return new McpTool(
                "get_server_status",
                "サーバーの状態（ワールド一覧・オンラインプレイヤー・CoreProtectの可用性・DBエンジン・キュー滞留数・Foliaかどうか）を取得する。",
                schema(new JsonObject(), Collections.emptyList()),
                args -> {
                    ServerStatusService.Snapshot snapshot = serverStatusService.snapshot();

                    JsonObject result = new JsonObject();
                    result.addProperty("coreProtectAvailable", snapshot.coreProtectAvailable());
                    if (snapshot.coreProtectApiVersion() != null) {
                        result.addProperty("coreProtectApiVersion", snapshot.coreProtectApiVersion());
                    }
                    result.addProperty("folia", snapshot.folia());
                    result.addProperty("databaseEngine", snapshot.databaseEngine());
                    result.addProperty("pendingQueueSize", snapshot.pendingQueueSize());

                    JsonArray worlds = new JsonArray();
                    for (String w : snapshot.worlds()) worlds.add(w);
                    result.add("worlds", worlds);

                    JsonArray players = new JsonArray();
                    for (String p : snapshot.onlinePlayers()) players.add(p);
                    result.add("onlinePlayers", players);

                    return result;
                });
    }

    private McpTool toolActivitySummary() {
        return new McpTool(
                "activity_summary",
                "指定した期間・範囲のCoreProtectログを走査し、プレイヤー別/アクション別/ブロック別/時間帯別の件数を集計する。" +
                        "個々の行データは返さないため、まず全体像を掴んで怪しいプレイヤーや時間帯に当たりを付けるのに使う。",
                schema(properties(
                        "time", prop("string", "検索期間。例: \"3d\", \"1d12h\", \"30m\"。省略時は86400秒（1日）"),
                        "world", prop("string", "ワールド名。x/y/zと合わせて指定した場合のみ範囲検索になる。省略時は全ワールドが対象"),
                        "x", prop("number", "中心X座標（world/y/zと併せて指定）"),
                        "y", prop("number", "中心Y座標（world/x/zと併せて指定）"),
                        "z", prop("number", "中心Z座標（world/x/yと併せて指定）"),
                        "radius", prop("integer", "中心座標からの検索半径（ブロック単位）"),
                        "users", stringArrayProp("対象を絞るプレイヤー名のリスト"),
                        "actions", enumArrayProp("対象を絞るアクション種別のリスト。省略時は全種別が対象", ACTION_NAMES),
                        "topN", prop("integer", "各カテゴリで返す上位件数。既定20")),
                        Collections.emptyList()),
                args -> {
                    requireCoreProtect();
                    LookupQuery query = buildQuery(args);
                    int topN = getInt(args, "topN", 20);

                    ActivitySummaryService.Summary summary = activitySummaryService
                            .summarize(query, config.getMcpSummaryMaxRows(), topN)
                            .get(config.getMcpTimeoutSeconds(), TimeUnit.SECONDS);

                    return summaryToJson(summary);
                });
    }

    private McpTool toolLookupActions() {
        return new McpTool(
                "lookup_actions",
                "CoreProtectのログを条件検索する。プレイヤー・ブロック名・アクション種別・座標範囲・期間で絞り込み可能。",
                schema(properties(
                        "time", prop("string", "検索期間。例: \"3d\", \"1d12h\"。省略時は86400秒"),
                        "users", stringArrayProp("対象を絞るプレイヤー名のリスト"),
                        "excludeUsers", stringArrayProp("除外するプレイヤー名のリスト"),
                        "blocks", stringArrayProp("対象を絞るブロック名/エンティティ名のリスト（例: \"diamond_ore\", \"chest\"）"),
                        "excludeBlocks", stringArrayProp("除外するブロック名/エンティティ名のリスト"),
                        "actions", enumArrayProp("対象を絞るアクション種別のリスト。省略時は全種別が対象", ACTION_NAMES),
                        "world", prop("string", "ワールド名"),
                        "x", prop("number", "中心X座標"),
                        "y", prop("number", "中心Y座標"),
                        "z", prop("number", "中心Z座標"),
                        "radius", prop("integer", "中心座標からの検索半径（ブロック単位）"),
                        "offset", prop("integer", "ページング用の開始位置（新しい順で0始まり）"),
                        "limit", prop("integer", "取得件数の上限。既定50、最大" + config.getMcpMaxRows())),
                        Collections.emptyList()),
                args -> {
                    requireCoreProtect();
                    LookupQuery query = buildQuery(args);

                    LookupService.Outcome outcome = lookupService.lookup(query)
                            .get(config.getMcpTimeoutSeconds(), TimeUnit.SECONDS);

                    return lookupResultToJson(outcome, query);
                });
    }

    private McpTool toolBlockHistoryAt() {
        return new McpTool(
                "block_history_at",
                "指定した座標（半径指定可）の操作履歴を取得する。「この場所で何が起きたか」を調べるのに使う。",
                schema(properties(
                        "world", prop("string", "ワールド名"),
                        "x", prop("number", "X座標"),
                        "y", prop("number", "Y座標"),
                        "z", prop("number", "Z座標"),
                        "radius", prop("integer", "検索半径（ブロック単位）。既定0（その座標のみ）"),
                        "time", prop("string", "検索期間。例: \"3d\"。省略時は86400秒"),
                        "limit", prop("integer", "取得件数の上限。既定50、最大" + config.getMcpMaxRows())),
                        Arrays.asList("world", "x", "y", "z")),
                args -> {
                    requireCoreProtect();
                    if (!args.has("world") || !args.has("x") || !args.has("y") || !args.has("z")) {
                        throw new IllegalArgumentException("world, x, y, z は必須です");
                    }

                    LookupQuery query = new LookupQuery();
                    query.world = args.get("world").getAsString();
                    query.x = args.get("x").getAsDouble();
                    query.y = args.get("y").getAsDouble();
                    query.z = args.get("z").getAsDouble();
                    query.radius = getInt(args, "radius", 0);
                    query.timeSeconds = LookupQuery.parseTimeToSeconds(getString(args, "time", null), 86400);
                    query.rowCount = Math.min(getInt(args, "limit", 50), config.getMcpMaxRows());

                    for (ActionType type : ActionType.values()) {
                        query.actionIds.add(type.id());
                    }

                    LookupService.Outcome outcome = lookupService.lookup(query)
                            .get(config.getMcpTimeoutSeconds(), TimeUnit.SECONDS);

                    return lookupResultToJson(outcome, query);
                });
    }

    private McpTool toolPlayerSessionHistory() {
        return new McpTool(
                "player_session_history",
                "指定したプレイヤーのログイン・ログアウト履歴を取得する。",
                schema(properties(
                        "player", prop("string", "プレイヤー名"),
                        "time", prop("string", "検索期間。例: \"7d\"。省略時は604800秒（7日）"),
                        "limit", prop("integer", "取得件数の上限。既定50、最大" + config.getMcpMaxRows())),
                        Collections.singletonList("player")),
                args -> {
                    requireCoreProtect();
                    if (!args.has("player")) {
                        throw new IllegalArgumentException("player は必須です");
                    }

                    String player = args.get("player").getAsString();
                    int timeSeconds = LookupQuery.parseTimeToSeconds(getString(args, "time", null), 604800);
                    int limit = Math.min(getInt(args, "limit", 50), config.getMcpMaxRows());

                    LookupService.Outcome outcome = lookupService.session(player, timeSeconds)
                            .get(config.getMcpTimeoutSeconds(), TimeUnit.SECONDS);

                    List<LookupRow> rows = outcome.rows();
                    if (rows.size() > limit) {
                        rows = rows.subList(0, limit);
                    }

                    JsonObject root = new JsonObject();
                    root.addProperty("player", player);
                    root.addProperty("returnedRows", rows.size());

                    JsonArray rowArray = new JsonArray();
                    for (LookupRow row : rows) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("timestamp", row.timeSeconds);
                        obj.addProperty("timestampIso", Instant.ofEpochSecond(row.timeSeconds).toString());
                        obj.addProperty("action", row.action);
                        obj.addProperty("world", row.world);
                        obj.addProperty("x", row.x);
                        obj.addProperty("y", row.y);
                        obj.addProperty("z", row.z);
                        rowArray.add(obj);
                    }
                    root.add("sessions", rowArray);

                    return root;
                });
    }

    private McpTool toolContainerNetSummary() {
        return new McpTool(
                "container_net_summary",
                "指定した範囲・期間のチェスト等のコンテナ操作を集計し、プレイヤー×アイテムごとの純増減（収納数 - 取出数）を算出する。" +
                        "純増減がマイナス（取り出したきり返していない）の行を先頭に並べるため、アイテム窃盗の疑いがあるプレイヤーを特定するのに使う。",
                schema(properties(
                        "world", prop("string", "ワールド名"),
                        "x", prop("number", "中心X座標"),
                        "y", prop("number", "中心Y座標"),
                        "z", prop("number", "中心Z座標"),
                        "radius", prop("integer", "検索半径（ブロック単位）"),
                        "time", prop("string", "検索期間。例: \"3d\"。省略時は86400秒"),
                        "users", stringArrayProp("対象を絞るプレイヤー名のリスト"),
                        "excludeUsers", stringArrayProp("除外するプレイヤー名のリスト"),
                        "blocks", stringArrayProp("対象を絞るアイテム名（例: \"diamond\", \"iron_ingot\"）"),
                        "onlyNegative", prop("boolean", "純増減がマイナスの行（持ち出し超過）のみ返すか。既定 true")),
                        Collections.emptyList()),
                args -> {
                    requireCoreProtect();
                    LookupQuery query = buildQuery(args);
                    boolean onlyNegative = !args.has("onlyNegative") || args.get("onlyNegative").getAsBoolean();

                    ContainerNetService.Result result = containerNetService
                            .aggregate(query, config.getMcpSummaryMaxRows())
                            .get(config.getMcpTimeoutSeconds(), TimeUnit.SECONDS);

                    JsonObject root = new JsonObject();
                    root.addProperty("scannedRows", result.scannedRows());
                    root.addProperty("truncated", result.truncated());

                    JsonArray rows = new JsonArray();
                    for (ContainerNetService.Entry entry : result.entries()) {
                        if (onlyNegative && entry.net() >= 0) {
                            continue;
                        }
                        JsonObject obj = new JsonObject();
                        obj.addProperty("player", entry.player());
                        obj.addProperty("item", entry.item());
                        obj.addProperty("net", entry.net());
                        obj.addProperty("added", entry.added());
                        obj.addProperty("removed", entry.removed());
                        obj.addProperty("txCount", entry.txCount());
                        obj.addProperty("rolledBackTx", entry.rolledBackTx());
                        obj.addProperty("firstSeenIso", Instant.ofEpochSecond(entry.firstSeen()).toString());
                        obj.addProperty("lastSeenIso", Instant.ofEpochSecond(entry.lastSeen()).toString());
                        rows.add(obj);
                    }
                    root.add("entries", rows);

                    JsonArray warnings = new JsonArray();
                    for (String w : result.warnings()) warnings.add(w);
                    root.add("warnings", warnings);

                    return root;
                });
    }

    private McpTool toolListJobs() {
        return new McpTool(
                "list_jobs",
                "Web UI から実行されたロールバック・リストア・パージ等のジョブ一覧と進行状況を取得する（読み取り専用）。",
                schema(new JsonObject(), Collections.emptyList()),
                args -> {
                    JsonArray array = new JsonArray();
                    for (Job job : jobManager.all().values()) {
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
                        array.add(obj);
                    }

                    JsonObject root = new JsonObject();
                    root.add("jobs", array);
                    return root;
                });
    }

    // ---- ヘルパー -------------------------------------------------------------

    private void requireCoreProtect() {
        CoreProtectAPI api = plugin.getAPI();
        if (api == null || !api.isEnabled()) {
            throw new IllegalStateException("CoreProtect が利用できません");
        }
    }

    private LookupQuery buildQuery(JsonObject args) {
        LookupQuery query = new LookupQuery();
        query.timeSeconds = LookupQuery.parseTimeToSeconds(getString(args, "time", null), 86400);
        query.users = getStringList(args, "users");
        query.excludeUsers = getStringList(args, "excludeUsers");
        query.blockNames = getStringList(args, "blocks");
        query.excludeBlockNames = getStringList(args, "excludeBlocks");

        List<String> actionNames = getStringList(args, "actions");
        if (!actionNames.isEmpty()) {
            for (String name : actionNames) {
                ActionType type = parseActionType(name);
                if (type != null) {
                    query.actionIds.add(type.id());
                } else {
                    query.warnings.add("未知のアクション種別です: " + name);
                }
            }
        }
        if (query.actionIds.isEmpty()) {
            for (ActionType type : ActionType.values()) {
                query.actionIds.add(type.id());
            }
        }

        query.world = getString(args, "world", null);
        query.x = getDouble(args, "x");
        query.y = getDouble(args, "y");
        query.z = getDouble(args, "z");
        query.radius = getInt(args, "radius", config.getLookupDefaultRadius());
        query.offset = Math.max(0, getInt(args, "offset", 0));
        query.rowCount = Math.min(getInt(args, "limit", 50), config.getMcpMaxRows());

        return query;
    }

    private JsonObject lookupResultToJson(LookupService.Outcome outcome, LookupQuery query) {
        JsonObject root = new JsonObject();
        root.addProperty("returnedRows", outcome.rows().size());

        JsonArray rows = new JsonArray();
        for (LookupRow row : outcome.rows()) {
            rows.add(McpRowFormatter.rowToJson(row, false));
        }
        root.add("rows", rows);

        JsonArray warnings = new JsonArray();
        for (String w : outcome.warnings()) warnings.add(w);
        root.add("warnings", warnings);

        return root;
    }

    private JsonObject summaryToJson(ActivitySummaryService.Summary summary) {
        JsonObject root = new JsonObject();
        root.addProperty("scannedRows", summary.scannedRows());
        root.addProperty("truncated", summary.truncated());

        root.add("byPlayer", countsToJson(summary.byPlayer()));
        root.add("byAction", countsToJson(summary.byAction()));
        root.add("byBlock", countsToJson(summary.byBlock()));
        root.add("byHour", countsToJson(summary.byHour()));

        JsonArray warnings = new JsonArray();
        for (String w : summary.warnings()) warnings.add(w);
        root.add("warnings", warnings);

        return root;
    }

    private JsonArray countsToJson(List<ActivitySummaryService.Count> counts) {
        JsonArray array = new JsonArray();
        for (ActivitySummaryService.Count c : counts) {
            JsonObject obj = new JsonObject();
            obj.addProperty("key", c.key());
            obj.addProperty("count", c.count());
            array.add(obj);
        }
        return array;
    }

    private static String[] buildActionNames() {
        ActionType[] values = ActionType.values();
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].displayName();
        }
        return names;
    }

    private static ActionType parseActionType(String name) {
        for (ActionType type : ActionType.values()) {
            if (type.displayName().equalsIgnoreCase(name) || type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

    private static JsonObject schema(JsonObject properties, List<String> required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        if (!required.isEmpty()) {
            JsonArray req = new JsonArray();
            for (String r : required) req.add(r);
            schema.add("required", req);
        }
        return schema;
    }

    private static JsonObject properties(Object... kvs) {
        JsonObject props = new JsonObject();
        for (int i = 0; i < kvs.length; i += 2) {
            props.add((String) kvs[i], (JsonElement) kvs[i + 1]);
        }
        return props;
    }

    private static JsonObject prop(String type, String desc) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        obj.addProperty("description", desc);
        return obj;
    }

    private static JsonObject stringArrayProp(String desc) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "array");
        obj.addProperty("description", desc);
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        obj.add("items", items);
        return obj;
    }

    private static JsonObject enumArrayProp(String desc, String[] enums) {
        JsonObject obj = stringArrayProp(desc);
        JsonArray enumVals = new JsonArray();
        for (String e : enums) enumVals.add(e);
        obj.getAsJsonObject("items").add("enum", enumVals);
        return obj;
    }

    private static String getString(JsonObject obj, String key, String def) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return def;
        return obj.get(key).getAsString();
    }

    private static int getInt(JsonObject obj, String key, int def) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return def;
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return def;
        }
    }

    private static Double getDouble(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try {
            return obj.get(key).getAsDouble();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> getStringList(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray(key)) {
            list.add(el.getAsString());
        }
        return list;
    }
}
