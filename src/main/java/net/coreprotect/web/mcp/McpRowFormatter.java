package net.coreprotect.web.mcp;

import com.google.gson.JsonObject;
import net.coreprotect.web.core.LookupRow;

/**
 * 検索行を MCP 向け JSON に整形する。
 */
public final class McpRowFormatter {
    private McpRowFormatter() {
    }

    public static JsonObject rowToJson(LookupRow row, boolean includeRaw) {
        return row.toJson(true, includeRaw);
    }
}
