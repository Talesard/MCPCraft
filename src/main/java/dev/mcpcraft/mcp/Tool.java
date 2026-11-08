package dev.mcpcraft.mcp;

import com.google.gson.JsonObject;

import java.util.Map;

/**
 * A single MCP tool.
 *
 * @param name        tool name, e.g. {@code mc_set_block}
 * @param description model-facing description: what it does and when to use it
 * @param inputSchema JSON schema of the arguments (an {@code object})
 * @param handler     execution; receives the arguments and returns a {@link ToolResult}
 */
public record Tool(String name, String description, JsonObject inputSchema, Handler handler) {

	@FunctionalInterface
	public interface Handler {
		ToolResult run(JsonObject args) throws Exception;
	}

	/** Declares a tool without arguments. */
	public static Tool of(String name, String description, Handler handler) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", new JsonObject());
		schema.add("additionalProperties", com.google.gson.JsonNull.INSTANCE);
		return new Tool(name, description, schema, handler);
	}

	/** Small helper for building property maps; kept for callers that need it. */
	public static JsonObject objectSchema(Map<String, Object> ignored) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		return schema;
	}
}
