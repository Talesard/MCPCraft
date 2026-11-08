package dev.mcpcraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.mcpcraft.McpCraftMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MCP server configuration, read from {@code config/minecraft-mcp.json}.
 * The file is created on first launch with defaults.
 */
public final class McpConfig {
	public static final int DEFAULT_PORT = 8765;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static volatile McpConfig instance = new McpConfig();

	public boolean enabled = true;
	public String bindAddress = "127.0.0.1";
	public int port = DEFAULT_PORT;
	/** When set, requests must carry {@code Authorization: Bearer <token>}. */
	public String token = null;
	public int maxBlocksPerCall = 65536;
	public int maxEntitiesPerCall = 200;
	public boolean allowRunCommand = true;
	public boolean logRequests = false;
	/** Print a short one-line summary of every tool call into the in-game chat. */
	public boolean logToolCallsToChat = true;
	/** Default search radius for {@code mc_list_entities}. */
	public int defaultEntityRadius = 64;

	public static McpConfig get() {
		return instance;
	}

	public static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("minecraft-mcp.json");
	}

	public static synchronized void load() {
		Path path = configPath();
		McpConfig loaded = new McpConfig();

		if (Files.isRegularFile(path)) {
			try {
				String json = Files.readString(path, StandardCharsets.UTF_8);
				McpConfig fromFile = GSON.fromJson(json, McpConfig.class);
				if (fromFile != null) {
					loaded = fromFile;
				}
			} catch (Exception e) {
				McpCraftMod.LOGGER.warn("Minecraft MCP: failed to read {}, using defaults", path, e);
				loaded = new McpConfig();
			}
		}

		loaded.sanitize();
		instance = loaded;
		save();
		McpCraftMod.LOGGER.info("Minecraft MCP: config {} (port {}, bind {})",
				path, loaded.port, loaded.bindAddress);
	}

	private void sanitize() {
		if (bindAddress == null || bindAddress.isBlank()) {
			bindAddress = "127.0.0.1";
		}
		if (port < 1 || port > 65535) {
			port = DEFAULT_PORT;
		}
		if (maxBlocksPerCall < 1) {
			maxBlocksPerCall = 65536;
		}
		if (maxEntitiesPerCall < 1) {
			maxEntitiesPerCall = 200;
		}
		if (defaultEntityRadius < 1) {
			defaultEntityRadius = 64;
		}
		if (token != null && token.isBlank()) {
			token = null;
		}
	}

	public static synchronized void save() {
		Path path = configPath();
		try {
			Files.createDirectories(path.getParent());
			JsonObject o = new JsonObject();
			o.addProperty("enabled", instance.enabled);
			o.addProperty("bindAddress", instance.bindAddress);
			o.addProperty("port", instance.port);
			o.addProperty("token", instance.token);
			o.addProperty("maxBlocksPerCall", instance.maxBlocksPerCall);
			o.addProperty("maxEntitiesPerCall", instance.maxEntitiesPerCall);
			o.addProperty("allowRunCommand", instance.allowRunCommand);
			o.addProperty("logRequests", instance.logRequests);
			o.addProperty("logToolCallsToChat", instance.logToolCallsToChat);
			o.addProperty("defaultEntityRadius", instance.defaultEntityRadius);
			Files.writeString(path, GSON.toJson(o), StandardCharsets.UTF_8);
		} catch (IOException e) {
			McpCraftMod.LOGGER.warn("Minecraft MCP: failed to save config", e);
		}
	}
}
