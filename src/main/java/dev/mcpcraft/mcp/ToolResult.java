package dev.mcpcraft.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Result of a tool invocation.
 *
 * @param payload arbitrary JSON handed back to the agent (text or structure)
 * @param isError when true the MCP response is flagged with {@code isError: true}
 */
public record ToolResult(JsonElement payload, boolean isError) {

	public static ToolResult ok(JsonElement payload) {
		return new ToolResult(payload, false);
	}

	public static ToolResult ok(JsonObject payload) {
		return new ToolResult(payload, false);
	}

	public static ToolResult error(String message) {
		JsonObject o = new JsonObject();
		o.addProperty("error", message);
		return new ToolResult(o, true);
	}
}
