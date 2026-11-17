package net.coreprotect.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.coreprotect.CoreProtect;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * jar 内 src/main/resources/web 配下の静的ファイルを配信する。
 */
public class StaticFileHandler implements HttpHandler {
    private final CoreProtect plugin;

    public StaticFileHandler(CoreProtect plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }

        // パストラバーサル対策
        if (path.contains("..")) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        String resourcePath = "web" + path;
        try (InputStream is = plugin.getResource(resourcePath)) {
            if (is == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            byte[] bytes = is.readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", contentTypeOf(path));
            exchange.getResponseHeaders().add("Cache-Control", "no-store, no-cache, must-revalidate");
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private String contentTypeOf(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }

        return "application/octet-stream";
    }
}
