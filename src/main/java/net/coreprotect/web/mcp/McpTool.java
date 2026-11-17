package net.coreprotect.web.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * MCPの1ツール定義。
 */
public final class McpTool {
    @FunctionalInterface
    public interface Executor {
        JsonElement execute(JsonObject arguments) throws Exception;
    }

    private final String name;
    private final String description;
    private final JsonObject inputSchema;
    private final Executor executor;

    public McpTool(String name, String description, JsonObject inputSchema, Executor executor) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.executor = executor;
    }

    public String name() { return name; }
    public String description() { return description; }
    public JsonObject inputSchema() { return inputSchema; }
    public Executor executor() { return executor; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        McpTool mcpTool = (McpTool) o;
        return Objects.equals(name, mcpTool.name) &&
                Objects.equals(description, mcpTool.description) &&
                Objects.equals(inputSchema, mcpTool.inputSchema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, inputSchema);
    }
}
