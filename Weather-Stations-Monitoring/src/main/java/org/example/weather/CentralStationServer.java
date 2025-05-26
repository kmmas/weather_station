package org.example.weather;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.Arrays;
public class CentralStationServer {
    private final BitcaskStore bitcaskStore;
    private final int port;
    private HttpServer server;

    public CentralStationServer(BitcaskStore bitcaskStore, int port) {
        this.bitcaskStore = bitcaskStore;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/view", new ViewHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Central Station Server started on port " + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private class ViewHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);

            try {
                if (params.containsKey("key")) {
                    handleSingleKey(exchange, params.get("key"));
                } else {
                    handleAllKeys(exchange);
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
                e.printStackTrace();
            }
        }

        private void handleSingleKey(HttpExchange exchange, String key) throws IOException {
            String value = bitcaskStore.get(key);
            if (value != null) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, value.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(value.getBytes());
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        }

        private void handleAllKeys(HttpExchange exchange) throws IOException {
            Map<String, String> allData = bitcaskStore.getAll();
            String response = allData.entrySet().stream()
                    .map(e -> e.getKey() + "," + e.getValue().replace("\n", "\\n"))
                    .collect(Collectors.joining("\n"));

            exchange.getResponseHeaders().set("Content-Type", "text/csv");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }

        private Map<String, String> parseQuery(String query) {
            if (query == null || query.isEmpty()) return Collections.emptyMap();
            return Arrays.stream(query.split("&"))
                    .map(param -> param.split("="))
                    .collect(Collectors.toMap(
                            arr -> arr[0],
                            arr -> arr.length > 1 ? arr[1] : "",
                            (v1, v2) -> v1));
        }
    }
}