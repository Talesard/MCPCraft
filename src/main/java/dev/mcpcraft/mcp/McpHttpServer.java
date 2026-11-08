package dev.mcpcraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mcpcraft.McpCraftMod;
import dev.mcpcraft.config.McpConfig;
import dev.mcpcraft.util.MainThread;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP HTTP transport (Streamable HTTP, JSON-RPC 2.0).
 *
 * <p>Uses the JDK's {@code com.sun.net.httpserver}: no external dependencies and
 * no conflicts with the Netty instance Minecraft runs. Handlers run on a small
 * worker pool and hand game work to the main thread through {@link MainThread}.</p>
 */
public final class McpHttpServer {
	private static final AtomicReference<McpHttpServer> CURRENT = new AtomicReference<>();

	private final HttpServer server;
	private final ExecutorService executor;
	private final McpConfig config;

	private McpHttpServer(HttpServer server, ExecutorService executor, McpConfig config) {
		this.server = server;
		this.executor = executor;
		this.config = config;
	}

	public static synchronized McpHttpServer start(McpConfig config) throws IOException {
		stop();

		HttpServer http = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 0);
		ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
			Thread t = new Thread(r, "minecraft-mcp-http");
			t.setDaemon(true);
			return t;
		});

		McpHttpServer instance = new McpHttpServer(http, pool, config);
		http.setExecutor(pool);
		http.createContext("/mcp", instance::handleMcp);
		http.createContext("/health", instance::handleHealth);
		http.start();

		CURRENT.set(instance);
		McpCraftMod.LOGGER.info("Minecraft MCP: listening on http://{}:{}/mcp",
				config.bindAddress, config.port);
		return instance;
	}

	public static synchronized void stop() {
		McpHttpServer s = CURRENT.getAndSet(null);
		if (s == null) {
			return;
		}
		try {
			s.server.stop(0);
		} finally {
			s.executor.shutdownNow();
		}
		McpCraftMod.LOGGER.info("Minecraft MCP: server stopped");
	}

	public static boolean isRunning() {
		return CURRENT.get() != null;
	}

	private void handleHealth(HttpExchange ex) throws IOException {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("status", "ok");
			o.addProperty("name", McpProtocol.SERVER_NAME);
			o.addProperty("version", McpProtocol.SERVER_VERSION);
			o.addProperty("protocolVersion", McpProtocol.PROTOCOL_VERSION);
			o.addProperty("worldLoaded", MainThread.hasServer());
			o.addProperty("tools", ToolRegistry.size());
			sendJson(ex, 200, McpProtocol.GSON.toJson(o));
		} finally {
			ex.close();
		}
	}

	private void handleMcp(HttpExchange ex) throws IOException {
		try {
			switch (ex.getRequestMethod()) {
				case "OPTIONS" -> {
					sendEmpty(ex, 204);
					return;
				}
				case "GET" -> {
					// No SSE stream: tell the client it is not offered.
					sendEmpty(ex, 405);
					return;
				}
				case "DELETE" -> {
					// Session teardown per spec; we keep no state.
					sendEmpty(ex, 204);
					return;
				}
				case "POST" -> {
					// handled below
				}
				default -> {
					sendEmpty(ex, 405);
					return;
				}
			}

			if (!authorized(ex)) {
				sendJson(ex, 401, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"Unauthorized\"},\"id\":null}");
				return;
			}

			String body = readBody(ex);
			if (body.isBlank()) {
				sendJson(ex, 400, McpProtocol.GSON.toJson(
						McpProtocol.error(null, McpProtocol.PARSE_ERROR, "Empty request body")));
				return;
			}

			JsonElement parsed;
			try {
				parsed = McpProtocol.parse(body);
			} catch (Exception e) {
				sendJson(ex, 200, McpProtocol.GSON.toJson(
						McpProtocol.error(null, McpProtocol.PARSE_ERROR, "Invalid JSON: " + e.getMessage())));
				return;
			}

			if (parsed.isJsonArray()) {
				handleBatch(ex, parsed.getAsJsonArray());
				return;
			}

			JsonObject response = McpProtocol.handle(parsed);
			if (response == null) {
				sendEmpty(ex, 202);
			} else {
				sendJson(ex, 200, McpProtocol.GSON.toJson(response));
			}
		} catch (Exception e) {
			McpCraftMod.LOGGER.warn("MCP: HTTP handler failed", e);
			try {
				sendJson(ex, 200, McpProtocol.GSON.toJson(
						McpProtocol.error(null, McpProtocol.INTERNAL_ERROR, String.valueOf(e.getMessage()))));
			} catch (IOException ignored) {
				// connection already closed
			}
		} finally {
			ex.close();
		}
	}

	private void handleBatch(HttpExchange ex, JsonArray batch) throws IOException {
		JsonArray out = new JsonArray();
		for (JsonElement el : batch) {
			JsonObject r = McpProtocol.handle(el);
			if (r != null) {
				out.add(r);
			}
		}
		if (out.isEmpty()) {
			sendEmpty(ex, 202);
		} else {
			sendJson(ex, 200, McpProtocol.GSON.toJson(out));
		}
	}

	private boolean authorized(HttpExchange ex) {
		String expected = config.token;
		if (expected == null || expected.isBlank()) {
			return true;
		}
		Headers h = ex.getRequestHeaders();
		String auth = h.getFirst("Authorization");
		if (auth == null || !auth.startsWith("Bearer ")) {
			return false;
		}
		String actual = auth.substring("Bearer ".length()).trim();
		return MessageDigest.isEqual(
				expected.getBytes(StandardCharsets.UTF_8),
				actual.getBytes(StandardCharsets.UTF_8));
	}

	private static String readBody(HttpExchange ex) throws IOException {
		try (InputStream in = ex.getRequestBody()) {
			byte[] data = in.readAllBytes();
			return new String(data, StandardCharsets.UTF_8);
		}
	}

	private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		Headers h = ex.getResponseHeaders();
		h.set("Content-Type", "application/json; charset=utf-8");
		h.set("Cache-Control", "no-store");
		h.set("Access-Control-Allow-Origin", "*");
		h.set("Access-Control-Allow-Headers", "Content-Type, Authorization, Mcp-Session-Id, Mcp-Protocol-Version");
		h.set("Access-Control-Allow-Methods", "POST, GET, DELETE, OPTIONS");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void sendEmpty(HttpExchange ex, int status) throws IOException {
		Headers h = ex.getResponseHeaders();
		h.set("Access-Control-Allow-Origin", "*");
		h.set("Access-Control-Allow-Headers", "Content-Type, Authorization, Mcp-Session-Id, Mcp-Protocol-Version");
		h.set("Access-Control-Allow-Methods", "POST, GET, DELETE, OPTIONS");
		ex.sendResponseHeaders(status, -1);
	}
}
