package dev.mcpcraft.tools;

import com.google.gson.JsonObject;
import dev.mcpcraft.config.McpConfig;
import dev.mcpcraft.mcp.Tool;
import dev.mcpcraft.mcp.ToolResult;
import dev.mcpcraft.util.Args;
import dev.mcpcraft.util.MainThread;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Placing blocks: single, bulk, and region cloning. */
public final class BlockTools {
	private BlockTools() {
	}

	public static List<Tool> create() {
		List<Tool> tools = new ArrayList<>();
		tools.add(setBlock());
		tools.add(fillBlocks());
		tools.add(batchSetBlocks());
		tools.add(cloneRegion());
		tools.add(replaceBlocks());
		return tools;
	}

	private static Tool setBlock() {
		JsonObject schema = Schemas.object();
		Schemas.addPos(schema, "Required.");
		Schemas.addBlock(schema);
		Schemas.addDimension(schema);

		return new Tool("mc_set_block",
				"Places a single block at the given coordinates. Block state properties can be supplied "
						+ "(e.g. {\"axis\":\"y\"} for a log or {\"facing\":\"north\"} for a chest). "
						+ "Returns the previous and the new block state.",
				schema,
				args -> {
					BlockPos pos = Args.posXYZ(args);
					String blockId = Args.requireString(args, "block");
					JsonObject props = Args.optObject(args, "properties");
					return MainThread.call(() -> {
						ServerLevel level = dev.mcpcraft.util.Worlds.levelFor(args);
						BlockState newState = Args.blockState(blockId, props);
						BlockState oldState = level.getBlockState(pos);
						boolean ok = level.setBlock(pos, newState, 3);
						return ToolResult.ok(Args.json(
								"dimension", level.dimension().identifier().toString(),
								"pos", posJson(pos),
								"block", blockIdOf(newState),
								"previous", blockIdOf(oldState),
								"success", ok));
					});
				});
	}

	private static Tool fillBlocks() {
		JsonObject schema = Schemas.object();
		Schemas.addRegion(schema);
		Schemas.addBlock(schema);
		Schemas.addDimension(schema);
		Schemas.addEnum(schema, "mode",
				"Fill mode: replace — overwrite everything; destroy — break existing blocks with drops; "
						+ "hollow — the surface (walls, floor, ceiling); outline — only the 12 edges; "
						+ "keep — leave occupied blocks untouched.", "replace");
		Schemas.addProperty(schema, "properties", "object", "Block state properties.");

		return new Tool("mc_fill_blocks",
				"Fills a rectangular volume with blocks (like the /fill command). "
						+ "The volume is capped by maxBlocksPerCall; for large builds use mc_build_shape.",
				schema,
				args -> {
					BlockPos from = Args.pos(args, "from");
					BlockPos to = Args.pos(args, "to");
					String blockId = Args.requireString(args, "block");
					String mode = Args.optString(args, "mode", "replace").toLowerCase();
					JsonObject props = Args.optObject(args, "properties");

					long volume = Schemas.volume(from, to);
					int limit = McpConfig.get().maxBlocksPerCall;
					if (volume > limit) {
						throw new dev.mcpcraft.mcp.McpProtocol.LimitException(
								"Volume of " + volume + " blocks exceeds the limit of " + limit
										+ ". Shrink the region or raise maxBlocksPerCall in the config.");
					}
					BlockState state = Args.blockState(blockId, props);

					return MainThread.call(() -> {
						ServerLevel level = dev.mcpcraft.util.Worlds.levelFor(args);
						boolean destroyMode = "destroy".equals(mode);
						long placed = 0;
						long unchanged = 0;
						long skipped = 0;
						long destroyed = 0;

						for (BlockPos pos : Schemas.iterRegion(from, to)) {
							if (!Schemas.acceptByMode(mode, from, to, pos, level, state)) {
								skipped++;
								continue;
							}
							if (destroyMode) {
								// Real destruction with drops: pass true so items fall out.
								if (level.destroyBlock(pos, true)) {
									destroyed++;
								}
								continue;
							}
							// setBlock returns false when the block already had this state.
							if (level.setBlock(pos, state, 3)) {
								placed++;
							} else {
								unchanged++;
							}
						}
						final long p = placed;
						final long u = unchanged;
						final long s = skipped;
						final long d = destroyed;
						return ToolResult.ok(Args.json(
								"dimension", level.dimension().identifier().toString(),
								"from", posJson(from),
								"to", posJson(to),
								"block", blockIdOf(state),
								"mode", mode,
								"placed", destroyMode ? 0 : p + u,
								"changed", destroyMode ? 0 : p,
								"already_same", destroyMode ? 0 : u,
								"destroyed", d,
								"skipped", s,
								"volume", volume));
					});
				});
	}

	private static Tool batchSetBlocks() {
		JsonObject schema = Schemas.object();
		Schemas.addProperty(schema, "blocks", "array",
				"Array of objects like {\"x\":0,\"y\":64,\"z\":0,\"block\":\"minecraft:stone\",\"properties\":{...}}");
		Schemas.addDimension(schema);

		return new Tool("mc_batch_set_blocks",
				"Places many blocks in one call (more efficient than repeated mc_set_block). "
						+ "Suits precise coordinate-by-coordinate placement.",
				schema,
				args -> {
					var arr = Args.optArray(args, "blocks");
					if (arr.isEmpty()) {
						throw new IllegalArgumentException("Parameter 'blocks' is empty");
					}
					if (arr.size() > McpConfig.get().maxBlocksPerCall) {
						throw new dev.mcpcraft.mcp.McpProtocol.LimitException(
								"Too many blocks in one call: " + arr.size());
					}

					// Validate the whole array first: one bad entry rejects the call so the
					// agent never gets a partially applied batch.
					List<BlockPos> positions = new ArrayList<>(arr.size());
					List<BlockState> states = new ArrayList<>(arr.size());
					for (int i = 0; i < arr.size(); i++) {
						JsonObject o = arr.get(i).getAsJsonObject();
						try {
							positions.add(new BlockPos(
									o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
							states.add(Args.blockState(o.get("block").getAsString(),
									Args.optObject(o, "properties")));
						} catch (RuntimeException e) {
							throw new IllegalArgumentException("blocks[" + i + "]: " + e.getMessage(), e);
						}
					}

					return MainThread.call(() -> {
						ServerLevel level = dev.mcpcraft.util.Worlds.levelFor(args);
						int changed = 0;
						int unchanged = 0;
						for (int i = 0; i < positions.size(); i++) {
							if (level.setBlock(positions.get(i), states.get(i), 3)) {
								changed++;
							} else {
								unchanged++;
							}
						}
						return ToolResult.ok(Args.json(
								"placed", changed + unchanged,
								"changed", changed,
								"already_same", unchanged,
								"total", arr.size()));
					});
				});
	}

	private static Tool cloneRegion() {
		JsonObject schema = Schemas.object();
		Schemas.addRegion(schema);
		Schemas.addProperty(schema, "offset", "object", "Paste offset: {\"x\":10,\"y\":0,\"z\":0}");
		Schemas.addDimension(schema);

		return new Tool("mc_clone_region",
				"Copies a rectangular region and pastes it with an offset (like /clone). "
						+ "Handy for duplicating builds.",
				schema,
				args -> {
					BlockPos from = Args.pos(args, "from");
					BlockPos to = Args.pos(args, "to");
					JsonObject off = Args.optObject(args, "offset");
					int dx = Args.optInt(off, "x", 0);
					int dy = Args.optInt(off, "y", 0);
					int dz = Args.optInt(off, "z", 0);

					long volume = Schemas.volume(from, to);
					if (volume > McpConfig.get().maxBlocksPerCall) {
						throw new dev.mcpcraft.mcp.McpProtocol.LimitException(
								"Volume of " + volume + " blocks exceeds the limit");
					}

					return MainThread.call(() -> {
						ServerLevel level = dev.mcpcraft.util.Worlds.levelFor(args);
						List<BlockPos> src = new ArrayList<>();
						List<BlockState> states = new ArrayList<>();
						for (BlockPos pos : Schemas.iterRegion(from, to)) {
							src.add(pos);
							states.add(level.getBlockState(pos));
						}
						int placed = 0;
						for (int i = 0; i < src.size(); i++) {
							BlockPos target = src.get(i).offset(dx, dy, dz);
							if (level.setBlock(target, states.get(i), 3)) {
								placed++;
							}
						}
						final int p = placed;
						return ToolResult.ok(Args.json(
								"copied", src.size(), "placed", p, "offset", Args.toJson(off)));
					});
				});
	}

	private static Tool replaceBlocks() {
		JsonObject schema = Schemas.object();
		Schemas.addRegion(schema);
		Schemas.addProperty(schema, "from_block", "string", "Which blocks to replace, e.g. minecraft:stone");
		Schemas.addProperty(schema, "to_block", "string", "What to replace them with, e.g. minecraft:diamond_block");
		Schemas.addProperty(schema, "properties", "object", "Properties of the new block state.");
		Schemas.addDimension(schema);

		return new Tool("mc_replace_blocks",
				"Replaces one block type with another inside a region (palette swap). "
						+ "Useful for restyling builds without touching anything else.",
				schema,
				args -> {
					BlockPos from = Args.pos(args, "from");
					BlockPos to = Args.pos(args, "to");
					String fromBlock = Args.requireString(args, "from_block");
					String toBlock = Args.requireString(args, "to_block");
					JsonObject props = Args.optObject(args, "properties");

					long volume = Schemas.volume(from, to);
					if (volume > McpConfig.get().maxBlocksPerCall) {
						throw new dev.mcpcraft.mcp.McpProtocol.LimitException(
								"Volume of " + volume + " blocks exceeds the limit");
					}
					var fromState = Args.blockState(fromBlock, new JsonObject());
					var toState = Args.blockState(toBlock, props);

					return MainThread.call(() -> {
						ServerLevel level = dev.mcpcraft.util.Worlds.levelFor(args);
						long changed = 0;
						for (BlockPos pos : Schemas.iterRegion(from, to)) {
							if (level.getBlockState(pos).getBlock() == fromState.getBlock()) {
								if (level.setBlock(pos, toState, 3)) {
									changed++;
								}
							}
						}
						final long c = changed;
						return ToolResult.ok(Args.json(
								"from_block", fromBlock, "to_block", toBlock, "changed", c, "volume", volume));
					});
				});
	}

	static JsonObject posJson(BlockPos pos) {
		return Args.json("x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
	}

	static String blockIdOf(BlockState state) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}
}
