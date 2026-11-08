package dev.mcpcraft.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpcraft.mcp.Tool;
import dev.mcpcraft.mcp.ToolResult;
import dev.mcpcraft.util.Args;
import dev.mcpcraft.util.MainThread;
import dev.mcpcraft.util.Worlds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Read-only world state tools; agents should start here. */
public final class QueryTools {
	private QueryTools() {
	}

	public static List<Tool> create() {
		List<Tool> tools = new ArrayList<>();
		tools.add(worldInfo());
		tools.add(playerState());
		tools.add(getBlock());
		return tools;
	}

	private static Tool worldInfo() {
		JsonObject schema = Schemas.object();

		return new Tool("mc_get_world_info",
				"Returns the situation: dimensions, time, weather, difficulty and player positions. "
						+ "Call it first to learn the context before making changes.",
				schema,
				args -> MainThread.call(() -> {
					var server = Worlds.server();
					var overworld = server.overworld();

					JsonArray dims = new JsonArray();
					for (ServerLevel l : server.getAllLevels()) {
						dims.add(l.dimension().identifier().toString());
					}

					JsonArray players = new JsonArray();
					for (ServerPlayer p : server.getPlayerList().getPlayers()) {
						players.add(Args.json(
								"name", p.getName().getString(),
								"dimension", p.level().dimension().identifier().toString(),
								"x", p.getX(), "y", p.getY(), "z", p.getZ(),
								"health", p.getHealth(),
								"gameMode", p.gameMode().getName()));
					}

					var clock = server.registryAccess()
							.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK)
							.getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
					long totalTicks = overworld.clockManager().getInstance(clock).totalTicks();
					long dayTime = Math.floorMod(totalTicks, 24000L);

					JsonObject o = Args.json(
							"dimension", overworld.dimension().identifier().toString(),
							"dayTime", dayTime,
							"totalTicks", totalTicks,
							"day", totalTicks / 24000L,
							"isDay", dayTime < 12000 || dayTime >= 22200,
							"raining", overworld.isRaining(),
							"thundering", overworld.isThundering(),
							"difficulty", overworld.getLevelData().getDifficulty().getSerializedName(),
							"playerCount", server.getPlayerList().getPlayerCount());
					o.add("dimensions", dims);
					o.add("players", players);
					return ToolResult.ok(o);
				}));
	}

	private static Tool playerState() {
		JsonObject schema = Schemas.object();
		Schemas.addProperty(schema, "player", "string", "Player name. Defaults to the first connected player.");

		return new Tool("mc_get_player_state",
				"Detailed player state: position, health, hunger, experience, game mode and inventory.",
				schema,
				args -> MainThread.call(() -> {
					String name = Args.optString(args, "player", null);
					ServerPlayer p = Worlds.player(name);

					JsonArray inv = new JsonArray();
					var inventory = p.getInventory();
					for (int i = 0; i < inventory.getContainerSize(); i++) {
						var stack = inventory.getItem(i);
						if (stack.isEmpty()) {
							continue;
						}
						inv.add(Args.json(
								"slot", i,
								"item", net.minecraft.core.registries.BuiltInRegistries.ITEM
										.getKey(stack.getItem()).toString(),
								"count", stack.getCount()));
					}

					JsonObject o = Args.json(
							"player", p.getName().getString(),
							"dimension", p.level().dimension().identifier().toString(),
							"x", p.getX(), "y", p.getY(), "z", p.getZ(),
							"health", p.getHealth(),
							"maxHealth", p.getMaxHealth(),
							"food", p.getFoodData().getFoodLevel(),
							"saturation", p.getFoodData().getSaturationLevel(),
							"xpLevel", p.experienceLevel,
							"xpProgress", p.experienceProgress,
							"gameMode", p.gameMode().getName(),
							"onGround", p.onGround(),
							"effects", EffectsTool.effectsJson(p));
					o.add("inventory", inv);
					return ToolResult.ok(o);
				}));
	}

	private static Tool getBlock() {
		JsonObject schema = Schemas.object();
		Schemas.addPos(schema, "Block coordinates.");
		Schemas.addDimension(schema);

		return new Tool("mc_get_block",
				"Reads the block at a position: id, block state properties, biome and light level. "
						+ "Helps to see what is already built.",
				schema,
				args -> {
					BlockPos pos = Args.posXYZ(args);
					return MainThread.call(() -> {
						ServerLevel level = Worlds.levelFor(args);
						BlockState state = level.getBlockState(pos);

						JsonObject props = new JsonObject();
						for (var prop : state.getProperties()) {
							props.addProperty(prop.getName(), prop.getName(valueOf(state, prop)));
						}

						JsonObject o = Args.json(
								"dimension", level.dimension().identifier().toString(),
								"pos", BlockTools.posJson(pos),
								"block", BlockTools.blockIdOf(state),
								"air", state.isAir(),
								"solid", state.isSolid(),
								"light", level.getMaxLocalRawBrightness(pos));
						o.add("properties", props);
						return ToolResult.ok(o);
					});
				});
	}

	/** Reads a property value without raw casts at the call site. */
	@SuppressWarnings("unchecked")
	private static <T extends Comparable<T>> T valueOf(BlockState state,
													   net.minecraft.world.level.block.state.properties.Property<?> prop) {
		return (T) state.getValue((net.minecraft.world.level.block.state.properties.Property<T>) prop);
	}
}
