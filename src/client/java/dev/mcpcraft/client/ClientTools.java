package dev.mcpcraft.client;

import com.google.gson.JsonObject;
import dev.mcpcraft.mcp.Tool;
import dev.mcpcraft.mcp.ToolRegistry;

/**
 * Client-side tools that need the renderer (screenshot).
 * Registered from the client entrypoint, so a dedicated server will not have them.
 */
public final class ClientTools {
	private ClientTools() {
	}

	public static void registerAll() {
		ToolRegistry.register(screenshot());
	}

	private static Tool screenshot() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", new JsonObject());

		return new Tool("mc_screenshot",
				"Takes a Minecraft screenshot and returns the PNG as base64. "
						+ "Lets an agent see the result of a build with its own eyes.",
				schema,
				args -> {
					byte[] png = ScreenshotBridge.requestScreenshot(8_000);
					if (png == null || png.length == 0) {
						return dev.mcpcraft.mcp.ToolResult.error(
								"Could not take a screenshot: a loaded world and an active game window are required");
					}
					String base64 = java.util.Base64.getEncoder().encodeToString(png);

					JsonObject o = new JsonObject();
					o.addProperty("format", "png");
					o.addProperty("bytes", png.length);
					o.addProperty("encoding", "base64");
					o.addProperty("image_base64", base64);
					return dev.mcpcraft.mcp.ToolResult.ok(o);
				});
	}
}
