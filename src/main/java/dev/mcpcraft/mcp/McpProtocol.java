package dev.mcpcraft.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.mcpcraft.McpCraftMod;
import dev.mcpcraft.config.McpConfig;

import java.util.Map;

/**
 * MCP protocol implementation (JSON-RPC 2.0) over Streamable HTTP.
 *
 * <p>Covers the subset agents and SDKs rely on: {@code initialize},
 * {@code notifications/initialized}, {@code ping}, {@code tools/list} and
 * {@code tools/call}. Resource and prompt methods return {@code -32601};
 * clients simply do not use them.</p>
 */
public final class McpProtocol {
	public static final String PROTOCOL_VERSION = "2025-06-18";
	public static final String SERVER_NAME = "minecraft-mcp";
	public static final String SERVER_VERSION = "1.0.0";

	public static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

	// JSON-RPC codes
	public static final int PARSE_ERROR = -32700;
	public static final int INVALID_REQUEST = -32600;
	public static final int METHOD_NOT_FOUND = -32601;
	public static final int INVALID_PARAMS = -32602;
	public static final int INTERNAL_ERROR = -32603;
	// Application codes
	public static final int NO_WORLD = -32001;
	public static final int LIMIT_EXCEEDED = -32002;
	public static final int TOOL_FAILED = -32003;

	private McpProtocol() {
	}

	/** Handles one JSON-RPC message. Returns null for notifications (no reply expected). */
	public static JsonObject handle(JsonElement request) {
		if (request == null || !request.isJsonObject()) {
			return error(JsonNull.INSTANCE, INVALID_REQUEST, "Expected a JSON object");
		}
		JsonObject req = request.getAsJsonObject();
		JsonElement id = req.has("id") ? req.get("id") : null;
		boolean isNotification = id == null || id.isJsonNull();

		String method;
		try {
			method = req.get("method").getAsString();
		} catch (RuntimeException e) {
			return error(id, INVALID_REQUEST, "Missing 'method' field");
		}

		JsonObject params = req.has("params") && req.get("params").isJsonObject()
				? req.getAsJsonObject("params")
				: new JsonObject();

		if (McpConfig.get().logRequests) {
			McpCraftMod.LOGGER.debug("MCP <- {}{}", method, isNotification ? " (notify)" : "");
		}

		try {
			return switch (method) {
				case "initialize" -> result(id, initializeResult());
				case "notifications/initialized", "notifications/cancelled" -> null;
				case "ping" -> result(id, new JsonObject());
				case "tools/list" -> result(id, toolsList());
				case "tools/call" -> result(id, toolsCall(params));
				case "resources/list" -> result(id, emptyList("resources"));
				case "resources/templates/list" -> result(id, emptyList("resourceTemplates"));
				case "prompts/list" -> result(id, emptyList("prompts"));
				default -> error(id, METHOD_NOT_FOUND, "Unsupported method: " + method);
			};
		} catch (NoWorldException e) {
			return error(id, NO_WORLD, e.getMessage());
		} catch (LimitException e) {
			return error(id, LIMIT_EXCEEDED, e.getMessage());
		} catch (ToolCallException e) {
			return isNotification ? null : error(id, TOOL_FAILED, e.getMessage());
		} catch (Exception e) {
			McpCraftMod.LOGGER.warn("MCP: failed to handle {}", method, e);
			return error(id, INTERNAL_ERROR, String.valueOf(e.getMessage()));
		}
	}

	private static JsonObject initializeResult() {
		JsonObject caps = new JsonObject();
		JsonObject tools = new JsonObject();
		tools.addProperty("listChanged", false);
		caps.add("tools", tools);

		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", SERVER_NAME);
		serverInfo.addProperty("version", SERVER_VERSION);

		JsonObject res = new JsonObject();
		res.addProperty("protocolVersion", PROTOCOL_VERSION);
		res.add("capabilities", caps);
		res.add("serverInfo", serverInfo);
		res.addProperty("instructions",
				"Minecraft world control server. Coordinates are absolute; blocks use ids like minecraft:stone. "
						+ "Start with mc_get_world_info and mc_get_player_state to learn the current state.");
		return res;
	}

	private static JsonObject toolsList() {
		JsonObject res = new JsonObject();
		JsonArray arr = new JsonArray();
		for (Tool t : ToolRegistry.all()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", t.name());
			o.addProperty("description", t.description());
			o.add("inputSchema", t.inputSchema());
			arr.add(o);
		}
		res.add("tools", arr);
		return res;
	}

	private static JsonObject toolsCall(JsonObject params) {
		String name;
		try {
			name = params.get("name").getAsString();
		} catch (RuntimeException e) {
			throw new ToolCallException("Missing tool name");
		}
		Tool tool = ToolRegistry.get(name);
		if (tool == null) {
			throw new ToolCallException("Unknown tool: " + name);
		}
		JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments")
				: new JsonObject();

		ToolLog.logCall(name, args);

		ToolResult r;
		try {
			r = tool.handler().run(args);
		} catch (NoWorldException | LimitException e) {
			ToolLog.logError(name, e.getMessage());
			throw e;
		} catch (Exception e) {
			McpCraftMod.LOGGER.warn("Tool {} failed", name, e);
			ToolLog.logError(name, String.valueOf(e.getMessage()));
			return toolResponse(ToolResult.error(String.valueOf(e.getMessage())));
		}
		return toolResponse(r);
	}

	private static JsonObject toolResponse(ToolResult r) {
		JsonObject res = new JsonObject();
		JsonArray content = new JsonArray();

		String text = r.payload() == null ? "" : (r.payload().isJsonPrimitive()
				? r.payload().getAsString()
				: GSON.toJson(r.payload()));
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);
		content.add(block);

		res.add("content", content);
		if (r.payload() != null && r.payload().isJsonObject()) {
			res.add("structuredContent", r.payload());
		}
		if (r.isError()) {
			res.addProperty("isError", true);
		}
		return res;
	}

	private static JsonObject emptyList(String key) {
		JsonObject o = new JsonObject();
		o.add(key, new JsonArray());
		return o;
	}

	public static JsonObject result(JsonElement id, JsonObject result) {
		JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", "2.0");
		o.add("id", id == null ? JsonNull.INSTANCE : id);
		o.add("result", result);
		return o;
	}

	public static JsonObject error(JsonElement id, int code, String message) {
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);

		JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", "2.0");
		o.add("id", id == null ? JsonNull.INSTANCE : id);
		o.add("error", err);
		return o;
	}

	/** Parses a request body: a single object or a batch. */
	public static JsonElement parse(String body) throws JsonSyntaxException {
		return JsonParser.parseString(body);
	}

	public static final class NoWorldException extends RuntimeException {
		public NoWorldException(String message) {
			super(message);
		}
	}

	public static final class LimitException extends RuntimeException {
		public LimitException(String message) {
			super(message);
		}
	}

	public static final class ToolCallException extends RuntimeException {
		public ToolCallException(String message) {
			super(message);
		}
	}

	/** Convenience for tools that need to convert a Map into a JsonObject. */
	public static JsonObject toJson(Map<String, ?> map) {
		return GSON.toJsonTree(map).getAsJsonObject();
	}
}
