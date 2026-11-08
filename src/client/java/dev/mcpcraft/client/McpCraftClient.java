package dev.mcpcraft.client;

import dev.mcpcraft.McpCraftMod;
import dev.mcpcraft.config.McpConfig;
import dev.mcpcraft.mcp.McpHttpServer;
import dev.mcpcraft.util.MainThread;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import java.io.IOException;

/**
 * Client entry point.
 *
 * <p>The HTTP server starts immediately so an agent can inspect the tool list
 * already in the menu; the server reference is updated on world join and cleared
 * on disconnect.</p>
 */
public final class McpCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		McpCraftMod.LOGGER.info("Minecraft MCP: initializing (client)");

		// The server lives inside the game JVM: world access appears when one is opened.
		ServerLifecycleEvents.SERVER_STARTED.register(MainThread::setServer);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> MainThread.clearServer());

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			MainThread.setServer(client.getSingleplayerServer());
			McpCraftMod.LOGGER.info("Minecraft MCP: player joined a world, world {}",
					MainThread.hasServer() ? "available" : "unavailable (external server)");
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			MainThread.clearServer();
			McpCraftMod.LOGGER.info("Minecraft MCP: left the world");
		});

		// Screenshots are requested from HTTP workers and captured in a client tick.
		ClientTickEvents.END_CLIENT_TICK.register(client -> ScreenshotBridge.tick(client));

		McpConfig config = McpConfig.get();
		if (!config.enabled) {
			McpCraftMod.LOGGER.info("Minecraft MCP: server disabled in the config");
			return;
		}

		ClientTools.registerAll();

		try {
			McpHttpServer.start(config);
		} catch (IOException e) {
			McpCraftMod.LOGGER.error("Minecraft MCP: failed to start the HTTP server on port {}",
					config.port, e);
		}
	}
}
