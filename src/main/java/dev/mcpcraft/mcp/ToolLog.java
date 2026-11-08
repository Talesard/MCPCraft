package dev.mcpcraft.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcpcraft.config.McpConfig;
import dev.mcpcraft.util.MainThread;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Short, human-readable chat transcript of tool calls.
 *
 * <p>Every MCP call is rendered as a single compact line, e.g.
 * {@code mc_set_block 5 64 10 stone} or {@code mc_spawn_entity zombie x3}.
 * Messages are posted to the integrated server thread, so they appear in the
 * in-game chat without blocking the HTTP worker.</p>
 */
public final class ToolLog {
	/** Fields worth showing in the summary, in priority order. */
	private static final String[] POSITION_KEYS = {"x", "y", "z"};

	private ToolLog() {
	}

	/** Logs a tool call before its execution. */
	public static void logCall(String toolName, JsonObject args) {
		if (!McpConfig.get().logToolCallsToChat) {
			return;
		}
		broadcast("[MCP] " + summary(toolName, args));
	}

	/** Logs a short outcome; only errors are worth a separate line. */
	public static void logError(String toolName, String message) {
		if (!McpConfig.get().logToolCallsToChat) {
			return;
		}
		broadcast("[MCP] " + toolName + " FAILED: " + shorten(message, 80));
	}

	/**
	 * Builds the one-line summary for a call: the tool name plus the few
	 * arguments that matter (coordinates, block, entity type, item, count).
	 */
	public static String summary(String toolName, JsonObject args) {
		List<String> parts = new ArrayList<>();

		if (args != null) {
			String position = position(args);
			if (position != null) {
				parts.add(position);
			}

			for (String key : new String[]{"from", "to"}) {
				String region = point(args.get(key));
				if (region != null) {
					parts.add(region);
				}
			}

			for (String key : new String[]{"block", "type", "item", "particle", "sound", "shape",
					"weather", "difficulty", "gamemode", "action", "uuid"}) {
				String value = scalar(args.get(key));
				if (value != null) {
					parts.add(key.equals("uuid") ? shorten(value, 8) : value);
				}
			}

			String count = scalar(args.get("count"));
			if (count == null) {
				count = scalar(args.get("radius"));
			}
			if (count != null) {
				parts.add("x" + count);
			}

			String text = scalar(args.get("text"));
			if (text == null) {
				text = scalar(args.get("command"));
			}
			if (text != null) {
				parts.add("\"" + shorten(text, 40) + "\"");
			}
		}

		return parts.isEmpty() ? toolName : toolName + " " + String.join(" ", parts);
	}

	private static String position(JsonObject args) {
		if (!args.has("x")) {
			return null;
		}
		StringJoinerOut out = new StringJoinerOut();
		for (String key : POSITION_KEYS) {
			String value = scalar(args.get(key));
			if (value != null) {
				out.add(value);
			}
		}
		return out.isEmpty() ? null : out.toString();
	}

	private static String point(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return null;
		}
		return position(element.getAsJsonObject());
	}

	private static String scalar(JsonElement element) {
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return null;
		}
		var primitive = element.getAsJsonPrimitive();
		if (primitive.isBoolean()) {
			return String.valueOf(primitive.getAsBoolean());
		}
		if (primitive.isNumber()) {
			double value = primitive.getAsDouble();
			if (value == Math.rint(value) && !Double.isInfinite(value)) {
				return String.valueOf((long) value);
			}
			return String.valueOf(value);
		}
		String raw = primitive.getAsString();
		return raw.isBlank() ? null : raw.replace("minecraft:", "");
	}

	private static String shorten(String value, int max) {
		if (value == null) {
			return "";
		}
		String flat = value.replace('\n', ' ').trim();
		return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
	}

	private static void broadcast(String line) {
		if (!MainThread.hasServer()) {
			return;
		}
		try {
			var server = MainThread.server();
			server.submit(() -> {
				Component message = Component.literal(line);
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					player.sendSystemMessage(message);
				}
			});
		} catch (RuntimeException ignored) {
			// Chat logging must never break a tool call.
		}
	}

	/** Tiny helper to join coordinates without pulling in stream machinery. */
	private static final class StringJoinerOut {
		private final List<String> parts = new ArrayList<>();

		void add(String value) {
			parts.add(value);
		}

		boolean isEmpty() {
			return parts.isEmpty();
		}

		@Override
		public String toString() {
			return String.join(" ", parts);
		}
	}
}
