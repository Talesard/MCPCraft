package dev.mcpcraft.tools;

import com.google.gson.JsonObject;
import dev.mcpcraft.config.McpConfig;
import dev.mcpcraft.mcp.Tool;
import dev.mcpcraft.mcp.ToolResult;
import dev.mcpcraft.util.Args;
import dev.mcpcraft.util.MainThread;
import dev.mcpcraft.util.Worlds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Procedural builds: spheres, domes, cylinders, pyramids, walls, platforms, block art. */
public final class BuildTools {
	private BuildTools() {
	}

	public static List<Tool> create() {
		List<Tool> tools = new ArrayList<>();
		tools.add(buildShape());
		tools.add(drawBlockArt());
		return tools;
	}

	private static Tool buildShape() {
		JsonObject schema = Schemas.object();
		Schemas.addEnum(schema, "shape", "Shape to build.",
				"sphere", "hemisphere", "dome", "cylinder", "pyramid", "torus", "wall", "platform", "box_outline");
		Schemas.require(schema, "shape");
		Schemas.addPos(schema, "Center (or corner for wall/platform).");
		Schemas.addBlock(schema);
		Schemas.addProperty(schema, "radius", "integer", "Radius or half-size. Default 5.");
		Schemas.addProperty(schema, "height", "integer", "Height for cylinder/pyramid/wall. Defaults to radius.");
		Schemas.addProperty(schema, "thickness", "integer", "Shell thickness for sphere/dome/cylinder. Default 1.");
		Schemas.addProperty(schema, "hollow", "boolean", "Shell only (for sphere/cylinder). Default true.");
		Schemas.addProperty(schema, "properties", "object", "Block state properties.");
		Schemas.addDimension(schema);

		return new Tool("mc_build_shape",
				"Builds a regular shape out of blocks: sphere, dome, cylinder, pyramid, torus, wall, platform "
						+ "or hollow box. Good for arenas, art pieces and scenery.",
				schema,
				args -> {
					String shape = Args.requireString(args, "shape").toLowerCase();
					BlockPos center = Args.posXYZ(args);
					String blockId = Args.requireString(args, "block");
					int radius = Args.optInt(args, "radius", 5);
					int height = Args.optInt(args, "height", radius);
					int thickness = Math.max(1, Args.optInt(args, "thickness", 1));
					boolean hollow = Args.optBool(args, "hollow", true);
					JsonObject props = Args.optObject(args, "properties");

					if (radius < 1 || radius > 128) {
						throw new IllegalArgumentException("radius must be between 1 and 128");
					}
					if (height < 1 || height > 256) {
						throw new IllegalArgumentException("height must be between 1 and 256");
					}

					Set<BlockPos> positions = computeShape(shape, center, radius, height, thickness, hollow);

					int limit = McpConfig.get().maxBlocksPerCall;
					if (positions.size() > limit) {
						throw new dev.mcpcraft.mcp.McpProtocol.LimitException(
								"The shape holds " + positions.size() + " blocks, limit is " + limit
										+ ". Reduce the radius or raise maxBlocksPerCall.");
					}
					BlockState state = Args.blockState(blockId, props);

					return MainThread.call(() -> {
						ServerLevel level = Worlds.levelFor(args);
						int placed = 0;
						for (BlockPos pos : positions) {
							if (level.setBlock(pos, state, 3)) {
								placed++;
							}
						}
						return ToolResult.ok(Args.json(
								"shape", shape,
								"dimension", level.dimension().identifier().toString(),
								"blocks", positions.size(),
								"placed", placed));
					});
				});
	}

	/** Computes the positions of a shape. Kept separate so it is unit-testable. */
	static Set<BlockPos> computeShape(String shape, BlockPos c, int radius, int height,
									  int thickness, boolean hollow) {
		Set<BlockPos> out = new LinkedHashSet<>();
		int r = radius;

		switch (shape) {
			case "sphere" -> {
				int inner = hollow ? Math.max(0, r - thickness) : 0;
				for (int x = -r; x <= r; x++) {
					for (int y = -r; y <= r; y++) {
						for (int z = -r; z <= r; z++) {
							double d2 = x * x + y * y + z * z;
							if (d2 <= r * r && d2 >= inner * inner) {
								out.add(c.offset(x, y, z));
							}
						}
					}
				}
			}
			case "hemisphere", "dome" -> {
				int inner = hollow ? Math.max(0, r - thickness) : 0;
				for (int x = -r; x <= r; x++) {
					for (int y = 0; y <= r; y++) {
						for (int z = -r; z <= r; z++) {
							double d2 = x * x + y * y + z * z;
							if (d2 <= r * r && d2 >= inner * inner) {
								out.add(c.offset(x, y, z));
							}
						}
					}
				}
			}
			case "cylinder" -> {
				int inner = hollow ? Math.max(0, r - thickness) : 0;
				for (int y = 0; y < height; y++) {
					for (int x = -r; x <= r; x++) {
						for (int z = -r; z <= r; z++) {
							double d2 = x * x + z * z;
							if (d2 <= r * r && d2 >= inner * inner) {
								out.add(c.offset(x, y, z));
							}
						}
					}
				}
			}
			case "pyramid" -> {
				for (int level = 0; level <= height; level++) {
					int half = Math.max(0, r - level);
					for (int x = -half; x <= half; x++) {
						for (int z = -half; z <= half; z++) {
							if (!hollow || isEdge(x, z, half)) {
								out.add(c.offset(x, level, z));
							}
						}
					}
				}
			}
			case "torus" -> {
				int bigR = r;
				int smallR = Math.max(1, thickness);
				for (int x = -bigR - smallR; x <= bigR + smallR; x++) {
					for (int z = -bigR - smallR; z <= bigR + smallR; z++) {
						double dist = Math.sqrt((double) x * x + (double) z * z);
						double d = dist - bigR;
						if (d * d <= (double) smallR * smallR) {
							out.add(c.offset(x, 0, z));
						}
					}
				}
			}
			case "wall" -> {
				// Wall from the center along X, `thickness` deep.
				int len = r;
				for (int x = 0; x <= len; x++) {
					for (int t = 0; t < thickness; t++) {
						for (int y = 0; y < height; y++) {
							out.add(c.offset(x, y, t));
						}
					}
				}
			}
			case "platform" -> {
				for (int x = -r; x <= r; x++) {
					for (int z = -r; z <= r; z++) {
						for (int t = 0; t < thickness; t++) {
							out.add(c.offset(x, t, z));
						}
					}
				}
			}
			case "box_outline" -> {
				int h = height;
				for (int x = -r; x <= r; x++) {
					for (int y = 0; y <= h; y++) {
						for (int z = -r; z <= r; z++) {
							if (isEdge(x, z, r) || y == 0 || y == h) {
								out.add(c.offset(x, y, z));
							}
						}
					}
				}
			}
			default -> throw new IllegalArgumentException("Unknown shape: " + shape);
		}
		return out;
	}

	private static boolean isEdge(int x, int z, int half) {
		return Math.abs(x) == half || Math.abs(z) == half;
	}

	private static Tool drawBlockArt() {
		JsonObject schema = Schemas.object();
		Schemas.addPos(schema, "Lower-left corner of the picture.");
		Schemas.addProperty(schema, "rows", "array",
				"Array of strings, top to bottom. Each character is a column, e.g. [\" ## \",\"####\"].");
		Schemas.require(schema, "rows");
		Schemas.addProperty(schema, "palette", "object",
				"Character to block mapping: {\"#\":\"minecraft:red_wool\",\" \":\"minecraft:air\"}. "
						+ "By default a space means \"leave untouched\".");
		Schemas.addProperty(schema, "axis", "string",
				"Plane: xy (wall), xz (floor), yz (side wall). Default xy.");
		Schemas.addDimension(schema);

		return new Tool("mc_draw_block_art",
				"Draws block pixel art from an ASCII layout. A character from palette picks the block; "
						+ "a space is skipped. A quick way to make a sign, logo or picture.",
				schema,
				args -> {
					BlockPos origin = Args.posXYZ(args);
					var rowsJson = Args.optArray(args, "rows");
					if (rowsJson.isEmpty()) {
						throw new IllegalArgumentException("Parameter 'rows' is empty");
					}
					JsonObject palette = Args.optObject(args, "palette");
					String axis = Args.optString(args, "axis", "xy").toLowerCase();

					List<String> rows = new ArrayList<>();
					int width = 0;
					for (var r : rowsJson) {
						String s = r.getAsString();
						rows.add(s);
						width = Math.max(width, s.length());
					}

					long total = (long) rows.size() * width;
					if (total > McpConfig.get().maxBlocksPerCall) {
						throw new dev.mcpcraft.mcp.McpProtocol.LimitException(
								"Picture is too large: " + total + " positions");
					}
					final int artWidth = width;
					final int artHeight = rows.size();

					return MainThread.call(() -> {
						ServerLevel level = Worlds.levelFor(args);
						int placed = 0;
						int skipped = 0;

						for (int row = 0; row < rows.size(); row++) {
							String line = rows.get(row);
							int y = rows.size() - 1 - row; // first row is the top one
							for (int col = 0; col < line.length(); col++) {
								char ch = line.charAt(col);
								if (ch == ' ') {
									skipped++;
									continue;
								}
								String blockId = palette.has(String.valueOf(ch))
										? palette.get(String.valueOf(ch)).getAsString()
										: null;
								if (blockId == null) {
									skipped++;
									continue;
								}
								BlockPos pos = switch (axis) {
									case "xz" -> origin.offset(col, 0, y);
									case "yz" -> origin.offset(0, y, col);
									default -> origin.offset(col, y, 0);
								};
								if (level.setBlock(pos, Args.blockState(blockId, new JsonObject()), 3)) {
									placed++;
								}
							}
						}

						return ToolResult.ok(Args.json(
								"width", artWidth,
								"height", artHeight,
								"axis", axis,
								"placed", placed,
								"skipped", skipped));
					});
				});
	}
}
