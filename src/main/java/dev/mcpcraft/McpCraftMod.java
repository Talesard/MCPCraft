package dev.mcpcraft;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main (logic side) entry point.
 *
 * <p>Only client-agnostic setup lives here: the tool registry and config.
 * The HTTP server starts from the client entrypoint
 * ({@code dev.mcpcraft.client.McpCraftClient}) so it works in the integrated
 * world and in the menu, and always has access to the client main thread.</p>
 */
public final class McpCraftMod implements ModInitializer {
	public static final String MOD_ID = "minecraft-mcp";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Minecraft MCP: initializing (main), build {}", dev.mcpcraft.util.BuildInfo.describe());
		dev.mcpcraft.config.McpConfig.load();
		dev.mcpcraft.util.MainThread.init();
		dev.mcpcraft.mcp.ToolRegistry.bootstrap();
		LOGGER.info("Minecraft MCP: registered {} tools", dev.mcpcraft.mcp.ToolRegistry.size());
	}
}
