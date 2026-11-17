package net.coreprotect.web.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 検索1行分のデータモデル。
 * 看板テキスト、コンテナ個数、エンティティ詳細、ブロックデータ等を含む。
 */
public final class LookupRow {
    public final long timeSeconds;
    public final String player;
    public final int actionId;
    public final String action;
    public final String world;
    public final int x;
    public final int y;
    public final int z;
    public final String type;
    public final boolean rolledBack;
    public final Integer containerAmount;
    public final String containerDirection;
    public final String message;
    public final List<String> signLines;
    public final String blockData;
    public final String entityDetail;
    public final String parseError;
    public final List<String> raw;

    public LookupRow(long timeSeconds, String player, int actionId, String action,
                     String world, int x, int y, int z, String type, boolean rolledBack,
                     Integer containerAmount, String containerDirection, String message,
                     List<String> signLines, String blockData, String entityDetail,
                     String parseError, List<String> raw) {
        this.timeSeconds = timeSeconds;
        this.player = player;
        this.actionId = actionId;
        this.action = action;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = type;
        this.rolledBack = rolledBack;
        this.containerAmount = containerAmount;
        this.containerDirection = containerDirection;
        this.message = message;
        this.signLines = signLines != null ? signLines : Collections.emptyList();
        this.blockData = blockData;
        this.entityDetail = entityDetail;
        this.parseError = parseError;
        this.raw = raw != null ? raw : Collections.emptyList();
    }

    public JsonObject toJson(boolean includeIso, boolean includeRaw) {
        JsonObject obj = new JsonObject();
        if (parseError != null) {
            obj.addProperty("parseError", parseError);
        } else {
            obj.addProperty("timestamp", timeSeconds);
            if (includeIso) {
                obj.addProperty("timestampIso", Instant.ofEpochSecond(timeSeconds).toString());
            }
            obj.addProperty("player", player);
            obj.addProperty("action", action);
            obj.addProperty("actionId", actionId);
            obj.addProperty("world", world);
            obj.addProperty("x", x);
            obj.addProperty("y", y);
            obj.addProperty("z", z);
            obj.addProperty("type", type);
            obj.addProperty("rolledBack", rolledBack);
            if (containerAmount != null) {
                obj.addProperty("containerAmount", containerAmount);
            }
            if (containerDirection != null) {
                obj.addProperty("containerDirection", containerDirection);
            }
            if (message != null) {
                obj.addProperty("message", message);
            }
            if (!signLines.isEmpty()) {
                JsonArray signs = new JsonArray();
                for (String line : signLines) {
                    signs.add(line);
                }
                obj.add("signLines", signs);
            }
            if (blockData != null && !blockData.isEmpty()) {
                obj.addProperty("blockData", blockData);
            }
            if (entityDetail != null && !entityDetail.isEmpty()) {
                obj.addProperty("entityDetail", entityDetail);
            }
        }

        if (includeRaw) {
            JsonArray rawArray = new JsonArray();
            for (String cell : raw) {
                rawArray.add(cell);
            }
            obj.add("raw", rawArray);
        }

        return obj;
    }
}
