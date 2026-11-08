package dev.mcpcraft.tools;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Builds tool JSON schemas and provides region helpers. */
public final class Schemas {
	private Schemas() {
	}

	public static JsonObject object() {
		JsonObject o = new JsonObject();
		o.addProperty("type", "object");
		o.add("properties", new JsonObject());
		JsonArrayBuilder.required(o, new ArrayList<>());
		return o;
	}

	private static JsonObject properties(JsonObject schema) {
		return schema.getAsJsonObject("properties");
	}

	private static com.google.gson.JsonArray requiredArray(JsonObject schema) {
		if (!schema.has("required")) {
			schema.add("required", new com.google.gson.JsonArray());
		}
		return schema.getAsJsonArray("required");
	}

	public static void addProperty(JsonObject schema, String name, String type, String description) {
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		if (description != null) {
			p.addProperty("description", description);
		}
		properties(schema).add(name, p);
	}

	public static void addEnum(JsonObject schema, String name, String description, String... values) {
		JsonObject p = new JsonObject();
		p.addProperty("type", "string");
		if (description != null) {
			p.addProperty("description", description);
		}
		var arr = new com.google.gson.JsonArray();
		for (String v : values) {
			arr.add(v);
		}
		p.add("enum", arr);
		properties(schema).add(name, p);
	}

	public static void addEnumDefault(JsonObject schema, String name, String description,
									  String defaultDescription, String defaultValue, String... values) {
		addEnum(schema, name, description + " Default: " + defaultDescription + ".", values);
	}

	public static void require(JsonObject schema, String name) {
		var arr = requiredArray(schema);
		for (var el : arr) {
			if (el.getAsString().equals(name)) {
				return;
			}
		}
		arr.add(name);
	}

	public static void addPos(JsonObject schema, String note) {
		addProperty(schema, "x", "integer", "X coordinate. " + note);
		addProperty(schema, "y", "integer", "Y coordinate. " + note);
		addProperty(schema, "z", "integer", "Z coordinate. " + note);
		require(schema, "x");
		require(schema, "y");
		require(schema, "z");
	}

	public static void addBlock(JsonObject schema) {
		addProperty(schema, "block", "string",
				"Block id: minecraft:stone, oak_planks (the minecraft: prefix is added automatically).");
		require(schema, "block");
	}

	public static void addDimension(JsonObject schema) {
		addProperty(schema, "dimension", "string",
				"Dimension: overworld, nether, end or a full id. Defaults to the player's world.");
	}

	public static void addRegion(JsonObject schema) {
		addProperty(schema, "from", "object", "Region corner: {\"x\":0,\"y\":64,\"z\":0}");
		addProperty(schema, "to", "object", "Opposite corner: {\"x\":9,\"y\":73,\"z\":9}");
		require(schema, "from");
		require(schema, "to");
	}

	/** Region volume (inclusive). */
	public static long volume(BlockPos from, BlockPos to) {
		long dx = Math.abs((long) to.getX() - from.getX()) + 1;
		long dy = Math.abs((long) to.getY() - from.getY()) + 1;
		long dz = Math.abs((long) to.getZ() - from.getZ()) + 1;
		return dx * dy * dz;
	}

	/** Iterates every position of the region, inclusive. */
	public static Iterable<BlockPos> iterRegion(BlockPos a, BlockPos b) {
		int x1 = Math.min(a.getX(), b.getX());
		int x2 = Math.max(a.getX(), b.getX());
		int y1 = Math.min(a.getY(), b.getY());
		int y2 = Math.max(a.getY(), b.getY());
		int z1 = Math.min(a.getZ(), b.getZ());
		int z2 = Math.max(a.getZ(), b.getZ());

		return () -> new Iterator<>() {
			private int x = x1;
			private int y = y1;
			private int z = z1;
			private boolean done = false;

			@Override
			public boolean hasNext() {
				return !done && y <= y2;
			}

			@Override
			public BlockPos next() {
				BlockPos cur = new BlockPos(x, y, z);
				advance();
				return cur;
			}

			private void advance() {
				if (z < z2) {
					z++;
				} else if (x < x2) {
					z = z1;
					x++;
				} else if (y < y2) {
					z = z1;
					x = x1;
					y++;
				} else {
					done = true;
				}
			}
		};
	}

	/**
	 * Decides whether a position should be filled according to the fill mode.
	 *
	 * <p>Mode semantics:</p>
	 * <ul>
	 *   <li>{@code replace} — overwrite everything;</li>
	 *   <li>{@code keep} — only fill air;</li>
	 *   <li>{@code hollow} — the whole surface of the box (walls, floor, ceiling);</li>
	 *   <li>{@code outline} — only the 12 edges (two or more coordinates on a boundary);</li>
	 *   <li>{@code destroy} — break existing blocks with drops, place nothing.</li>
	 * </ul>
	 */
	public static boolean acceptByMode(String mode, BlockPos from, BlockPos to, BlockPos pos,
									   net.minecraft.server.level.ServerLevel level,
									   net.minecraft.world.level.block.state.BlockState state) {
		return switch (mode) {
			case "keep" -> level.getBlockState(pos).isAir();
			case "hollow" -> isOnShell(from, to, pos);
			case "outline" -> isOnEdge(from, to, pos);
			case "destroy" -> !level.getBlockState(pos).isAir();
			default -> true; // replace
		};
	}

	/** Position on the surface of the box (at least one coordinate on a boundary). */
	public static boolean isOnShell(BlockPos a, BlockPos b, BlockPos pos) {
		return boundaryCount(a, b, pos) >= 1;
	}

	/** Position on an edge of the box (at least two coordinates on a boundary). */
	public static boolean isOnEdge(BlockPos a, BlockPos b, BlockPos pos) {
		return boundaryCount(a, b, pos) >= 2;
	}

	/** How many coordinates of the position sit on a region boundary (0..3). */
	public static int boundaryCount(BlockPos a, BlockPos b, BlockPos pos) {
		int x1 = Math.min(a.getX(), b.getX());
		int x2 = Math.max(a.getX(), b.getX());
		int y1 = Math.min(a.getY(), b.getY());
		int y2 = Math.max(a.getY(), b.getY());
		int z1 = Math.min(a.getZ(), b.getZ());
		int z2 = Math.max(a.getZ(), b.getZ());

		int count = 0;
		if (pos.getX() == x1 || pos.getX() == x2) {
			count++;
		}
		if (pos.getY() == y1 || pos.getY() == y2) {
			count++;
		}
		if (pos.getZ() == z1 || pos.getZ() == z2) {
			count++;
		}
		return count;
	}

	/** Tiny internal helper so no extra class is needed. */
	static final class JsonArrayBuilder {
		private JsonArrayBuilder() {
		}

		static void required(JsonObject schema, List<String> ignored) {
			requiredArray(schema);
		}
	}
}
