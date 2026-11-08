package dev.mcpcraft.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcpcraft.mcp.McpProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Helpers for parsing tool arguments. */
public final class Args {
	private Args() {
	}

	public static JsonObject requireObject(JsonObject args) {
		return args == null ? new JsonObject() : args;
	}

	public static String requireString(JsonObject args, String key) {
		JsonElement el = args.get(key);
		if (el == null || el.isJsonNull()) {
			throw new IllegalArgumentException("Missing required parameter: " + key);
		}
		return el.getAsString();
	}

	public static String optString(JsonObject args, String key, String def) {
		JsonElement el = args.get(key);
		if (el == null || el.isJsonNull()) {
			return def;
		}
		return el.getAsString();
	}

	public static int requireInt(JsonObject args, String key) {
		JsonElement el = args.get(key);
		if (el == null || el.isJsonNull()) {
			throw new IllegalArgumentException("Missing required parameter: " + key);
		}
		return el.getAsInt();
	}

	public static int optInt(JsonObject args, String key, int def) {
		JsonElement el = args.get(key);
		if (el == null || el.isJsonNull()) {
			return def;
		}
		return el.getAsInt();
	}

	public static double optDouble(JsonObject args, String key, double def) {
		JsonElement el = args.get(key);
		if (el == null || el.isJsonNull()) {
			return def;
		}
		return el.getAsDouble();
	}

	public static boolean optBool(JsonObject args, String key, boolean def) {
		JsonElement el = args.get(key);
		if (el == null || el.isJsonNull()) {
			return def;
		}
		return el.getAsBoolean();
	}

	public static JsonArray optArray(JsonObject args, String key) {
		JsonElement el = args.get(key);
		if (el == null || el.isJsonNull() || !el.isJsonArray()) {
			return new JsonArray();
		}
		return el.getAsJsonArray();
	}

	public static JsonObject optObject(JsonObject args, String key) {
		JsonElement el = args.get(key);
		if (el == null || el.isJsonNull() || !el.isJsonObject()) {
			return new JsonObject();
		}
		return el.getAsJsonObject();
	}

	/** Position from either a nested {@code {x,y,z}} value or a {@code [x,y,z]} array. */
	public static BlockPos pos(JsonObject args, String key) {
		JsonElement el = args.get(key);
		if (el != null && el.isJsonObject()) {
			JsonObject o = el.getAsJsonObject();
			return new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
		}
		if (el != null && el.isJsonArray() && el.getAsJsonArray().size() >= 3) {
			JsonArray a = el.getAsJsonArray();
			return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
		}
		throw new IllegalArgumentException("Missing coordinates: " + key);
	}

	public static BlockPos optPos(JsonObject args, String key, BlockPos def) {
		if (!args.has(key) || args.get(key).isJsonNull()) {
			return def;
		}
		return pos(args, key);
	}

	public static BlockPos posXYZ(JsonObject args) {
		return new BlockPos(requireInt(args, "x"), requireInt(args, "y"), requireInt(args, "z"));
	}

	/** Normalizes a block id to {@code minecraft:stone}. */
	public static String normalizeId(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("Empty identifier");
		}
		String id = raw.trim().toLowerCase();
		return id.contains(":") ? id : "minecraft:" + id;
	}

	public static Identifier identifier(String raw) {
		String id = normalizeId(raw);
		Identifier loc = Identifier.tryParse(id);
		if (loc == null) {
			throw new IllegalArgumentException("Invalid identifier: " + raw);
		}
		return loc;
	}

	public static Block block(String raw) {
		Identifier id = identifier(raw);
		Block block = BuiltInRegistries.BLOCK.getValue(id);
		if (block == null) {
			throw new IllegalArgumentException("Unknown block: " + raw);
		}
		return block;
	}

	/** Block state with the given properties applied (string values). */
	public static BlockState blockState(String rawBlock, JsonObject properties) {
		Block block = block(rawBlock);
		BlockState state = block.defaultBlockState();
		if (properties != null && !properties.isEmpty()) {
			for (var entry : properties.entrySet()) {
				String value = entry.getValue().getAsString();
				state = StateProperties.apply(state, block, entry.getKey(), value);
			}
		}
		return state;
	}

	public static JsonObject json(Object... kv) {
		JsonObject o = new JsonObject();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			String key = String.valueOf(kv[i]);
			Object v = kv[i + 1];
			if (v == null) {
				o.add(key, com.google.gson.JsonNull.INSTANCE);
			} else if (v instanceof Number n) {
				o.addProperty(key, n);
			} else if (v instanceof Boolean b) {
				o.addProperty(key, b);
			} else if (v instanceof JsonElement je) {
				o.add(key, je);
			} else {
				o.addProperty(key, String.valueOf(v));
			}
		}
		return o;
	}

	public static String toJson(JsonElement el) {
		return McpProtocol.GSON.toJson(el);
	}
}
