package dev.mcpcraft.util;

import dev.mcpcraft.mcp.McpProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/** Access helpers for the server, worlds and players. Call from the main thread only. */
public final class Worlds {
	private Worlds() {
	}

	public static MinecraftServer server() {
		MinecraftServer s = MainThread.server();
		if (s == null) {
			throw new McpProtocol.NoWorldException(
					"No world loaded: open a singleplayer world or join a server");
		}
		return s;
	}

	/** World by dimension name: {@code overworld}, {@code nether}, {@code end} or a full id. */
	public static ServerLevel level(String dimension) {
		MinecraftServer s = server();
		if (dimension == null || dimension.isBlank()) {
			return s.overworld();
		}
		String dim = dimension.trim().toLowerCase();
		ServerLevel byName = switch (dim) {
			case "overworld", "world", "minecraft:overworld", "minecraft:world" -> s.overworld();
			case "nether", "the_nether", "minecraft:the_nether" -> s.getLevel(Level.NETHER);
			case "end", "the_end", "minecraft:the_end" -> s.getLevel(Level.END);
			default -> null;
		};
		if (byName != null) {
			return byName;
		}
		for (ServerLevel l : s.getAllLevels()) {
			Identifier id = l.dimension().identifier();
			if (id.toString().equals(dim) || id.getPath().equals(dim)) {
				return l;
			}
		}
		throw new IllegalArgumentException("Unknown dimension: " + dimension
				+ ". Available: " + String.join(", ", dimensionNames()));
	}

	public static List<String> dimensionNames() {
		List<String> out = new ArrayList<>();
		for (ServerLevel l : server().getAllLevels()) {
			out.add(l.dimension().identifier().toString());
		}
		return out;
	}

	/** Player by name, otherwise the first connected player (the local one in singleplayer). */
	public static ServerPlayer player(String name) {
		MinecraftServer s = server();
		var list = s.getPlayerList();
		if (name != null && !name.isBlank()) {
			ServerPlayer p = list.getPlayerByName(name);
			if (p == null) {
				throw new IllegalArgumentException("Player not found: " + name);
			}
			return p;
		}
		List<ServerPlayer> players = list.getPlayers();
		if (players.isEmpty()) {
			throw new McpProtocol.NoWorldException("No players connected");
		}
		return players.get(0);
	}

	public static boolean hasPlayers() {
		MinecraftServer s = MainThread.server();
		return s != null && !s.getPlayerList().getPlayers().isEmpty();
	}

	/** World a tool operates on: the one given explicitly, else the player's world. */
	public static ServerLevel levelFor(com.google.gson.JsonObject args) {
		String dim = Args.optString(args, "dimension", null);
		if (dim != null) {
			return level(dim);
		}
		return player(null).level();
	}

	public static String describe(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}
}
