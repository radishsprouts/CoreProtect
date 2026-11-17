package net.coreprotect.web.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.coreprotect.CoreProtect;
import net.coreprotect.web.Http;
import net.coreprotect.web.WebConfig;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * MCP（Model Context Protocol）の Streamable HTTP トランスポートを実装する。
 */
public class McpHandler implements HttpHandler {
    private static final Gson GSON = new Gson();
    private static final Set<String> KNOWN_PROTOCOL_VERSIONS = new HashSet<>(
            Arrays.asList("2025-06-18", "2025-03-26", "2024-11-05")
    );
    private static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";

    private final CoreProtect plugin;
    private final WebConfig config;
    private final McpToolRegistry registry;

    public McpHandler(CoreProtect plugin, WebConfig config, McpToolRegistry registry) {
        this.plugin = plugin;
        this.config = config;
        this.registry = registry;
    }

    private static final class ProtocolException extends RuntimeException {
        final int code;

        ProtocolException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    @Override
    public void handle(HttpExchange exchange) {
        try {
            if (!isAllowedRemote(exchange)) {
                Http.sendError(exchange, 403, "許可されていない接続元です");
                return;
            }

            String method = exchange.getRequestMethod();
            if ("POST".equalsIgnoreCase(method)) {
                handlePost(exchange);
            } else if ("GET".equalsIgnoreCase(method)) {
                Http.sendError(exchange, 405, "このエンドポイントはサーバー起点のストリームを提供しません");
            } else if ("DELETE".equalsIgnoreCase(method)) {
                Http.sendJson(exchange, 200, new JsonObject());
            } else {
                Http.sendError(exchange, 405, "許可されていないメソッドです");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[MCP] リクエスト処理中にエラーが発生しました: " + e.getMessage());
            try {
                Http.sendError(exchange, 500, "サーバー内部エラー: " + rootMessage(e));
            } catch (IOException ignored) {
            }
        }
    }

    private boolean isAllowedRemote(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        InetAddress address = remote != null ? remote.getAddress() : null;
        return IpAccessControl.isAllowed(address, config.isMcpAllowPrivateOnly(), config.getMcpAllowedCidrs());
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        String body = Http.readBody(exchange);
        if (config.isMcpLogRequests()) {
            plugin.getLogger().info("[MCP] recv: " + body);
        }

        JsonElement parsed;
        try {
            parsed = (body == null || body.trim().isEmpty()) ? null : JsonParser.parseString(body);
        } catch (Exception e) {
            sendSingle(exchange, errorResponse(JsonNull.INSTANCE, -32700, "JSONの解析に失敗しました"));
            return;
        }

        if (parsed == null) {
            sendSingle(exchange, errorResponse(JsonNull.INSTANCE, -32600, "リクエストボディが空です"));
            return;
        }

        if (parsed.isJsonArray()) {
            JsonArray responses = new JsonArray();
            for (JsonElement element : parsed.getAsJsonArray()) {
                JsonObject response = dispatch(element);
                if (response != null) {
                    responses.add(response);
                }
            }

            if (responses.isEmpty()) {
                sendNoContent(exchange);
            } else {
                sendJsonAndLog(exchange, responses);
            }
            return;
        }

        JsonObject response = dispatch(parsed);
        if (response == null) {
            sendNoContent(exchange);
        } else {
            sendJsonAndLog(exchange, response);
        }
    }

    private void sendSingle(HttpExchange exchange, JsonObject response) throws IOException {
        sendJsonAndLog(exchange, response);
    }

    private void sendJsonAndLog(HttpExchange exchange, JsonElement response) throws IOException {
        if (config.isMcpLogRequests()) {
            plugin.getLogger().info("[MCP] send: " + GSON.toJson(response));
        }
        Http.sendJson(exchange, 200, response);
    }

    private void sendNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(202, -1);
        exchange.close();
    }

    private JsonObject dispatch(JsonElement element) {
        if (!element.isJsonObject()) {
            return errorResponse(JsonNull.INSTANCE, -32600, "不正なリクエストです");
        }

        JsonObject request = element.getAsJsonObject();
        JsonElement id = request.has("id") ? request.get("id") : null;
        boolean isNotification = id == null;

        if (!request.has("method") || !request.get("method").isJsonPrimitive()) {
            return isNotification ? null : errorResponse(id, -32600, "method が必要です");
        }

        String method = request.get("method").getAsString();

        if (method.equals("notifications/initialized")) {
            return null;
        }

        JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.get("params").getAsJsonObject() : new JsonObject();

        try {
            JsonElement result;
            if (method.equals("initialize")) {
                result = handleInitialize(params);
            } else if (method.equals("ping")) {
                result = new JsonObject();
            } else if (method.equals("tools/list")) {
                result = handleToolsList();
            } else if (method.equals("tools/call")) {
                result = handleToolsCall(params);
            } else {
                throw new ProtocolException(-32601, "未知のメソッドです: " + method);
            }

            if (isNotification) {
                return null;
            }

            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.add("id", id);
            response.add("result", result);
            return response;
        } catch (ProtocolException e) {
            return isNotification ? null : errorResponse(id, e.code, e.getMessage());
        } catch (Exception e) {
            return isNotification ? null : errorResponse(id, -32603, "内部エラー: " + rootMessage(e));
        }
    }

    private JsonObject handleInitialize(JsonObject params) {
        String requested = params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive()
                ? params.get("protocolVersion").getAsString() : null;
        String protocolVersion = requested != null && KNOWN_PROTOCOL_VERSIONS.contains(requested)
                ? requested : DEFAULT_PROTOCOL_VERSION;

        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);

        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", tools);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "coreprotect-webui");
        serverInfo.addProperty("version", plugin.getDescription().getVersion());

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", protocolVersion);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        return result;
    }

    private JsonObject handleToolsList() {
        JsonArray tools = new JsonArray();
        for (McpTool tool : registry.tools() ) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", tool.name());
            obj.addProperty("description", tool.description());
            obj.add("inputSchema", tool.inputSchema());
            tools.add(obj);
        }

        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private JsonObject handleToolsCall(JsonObject params) {
        if (!params.has("name") || !params.get("name").isJsonPrimitive()) {
            throw new ProtocolException(-32602, "name パラメータが必要です");
        }

        String name = params.get("name").getAsString();
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.get("arguments").getAsJsonObject() : new JsonObject();

        McpTool tool = registry.get(name);
        if (tool == null) {
            throw new ProtocolException(-32602, "未知のツールです: " + name);
        }

        try {
            JsonElement payload = tool.executor().execute(arguments);
            return toolResult(payload, false);
        } catch (Exception e) {
            JsonObject errorPayload = new JsonObject();
            errorPayload.addProperty("error", rootMessage(e));
            return toolResult(errorPayload, true);
        }
    }

    private JsonObject toolResult(JsonElement payload, boolean isError) {
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", GSON.toJson(payload));

        JsonArray content = new JsonArray();
        content.add(textBlock);

        JsonObject result = new JsonObject();
        result.add("content", content);
        if (isError) {
            result.addProperty("isError", true);
        }
        return result;
    }

    private JsonObject errorResponse(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id != null ? id : JsonNull.INSTANCE);
        response.add("error", error);
        return response;
    }

    private String rootMessage(Throwable t) {
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
