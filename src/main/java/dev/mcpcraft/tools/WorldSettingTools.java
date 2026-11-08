package dev.mcpcraft.tools;

import com.google.gson.JsonObject;
import dev.mcpcraft.config.McpConfig;
import dev.mcpcraft.mcp.Tool;
import dev.mcpcraft.mcp.ToolResult;
import dev.mcpcraft.util.Args;
import dev.mcpcraft.util.MainThread;
import dev.mcpcraft.util.Worlds;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.clock.ClockTimeMarker;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;

import java.util.ArrayList;
import java.util.List;

/** World settings: time, weather, difficulty, commands and messages. */
public final class WorldSettingTools {
	private WorldSettingTools() {
	}

	public static List<Tool> create() {
		List<Tool> tools = new ArrayList<>();
		tools.add(setTime());
		tools.add(getTime());
		tools.add(setWeather());
		tools.add(setDifficulty());
		tools.add(runCommand());
		tools.add(sendMessage());
		return tools;
	}

	/** The clock that drives the time of day in this dimension. */
	private static Holder<WorldClock> mainClock() {
		var registry = Worlds.server().registryAccess().lookupOrThrow(
				net.minecraft.core.registries.Registries.WORLD_CLOCK);
		return registry.getOrThrow(WorldClocks.OVERWORLD);
	}

	private static ServerClockManager clockManager() {
		return Worlds.server().overworld().clockManager();
	}

	private static Tool setTime() {
		JsonObject schema = Schemas.object();
		Schemas.addEnum(schema, "value", "Named time of day.", "day", "noon", "night", "midnight");
		Schemas.addProperty(schema, "ticks", "integer",
				"Exact time in ticks (0..24000). Takes priority over value.");
		Schemas.addDimension(schema);

		return new Tool("mc_set_time",
				"Sets the time of day: day, noon, night, midnight or an exact tick count.",
				schema,
				args -> MainThread.call(() -> {
					Holder<WorldClock> clock = mainClock();
					ServerClockManager mgr = clockManager();
					JsonObject out;

					if (args.has("ticks")) {
						long ticks = Math.floorMod(Args.requireInt(args, "ticks"), 24000L);
						long current = mgr.getInstance(clock).totalTicks();
						// Keep the day number: full days are counted from the current day start.
						long dayStart = current - Math.floorMod(current, 24000L);
						mgr.setTotalTicks(clock, dayStart + ticks);
						out = Args.json("time", ticks, "totalTicks", mgr.getInstance(clock).totalTicks());
					} else {
						String value = Args.optString(args, "value", "day").toLowerCase();
						ResourceKey<ClockTimeMarker> marker = switch (value) {
							case "day" -> ClockTimeMarkers.DAY;
							case "noon" -> ClockTimeMarkers.NOON;
							case "night" -> ClockTimeMarkers.NIGHT;
							case "midnight" -> ClockTimeMarkers.MIDNIGHT;
							default -> throw new IllegalArgumentException("Unknown time: " + value);
						};
						mgr.moveToTimeMarker(clock, marker);
						out = Args.json("time", value, "totalTicks", mgr.getInstance(clock).totalTicks());
					}

					ServerLevel level = Worlds.levelFor(args);
					out.addProperty("dimension", level.dimension().identifier().toString());
					return ToolResult.ok(out);
				}));
	}

	private static Tool getTime() {
		JsonObject schema = Schemas.object();
		Schemas.addDimension(schema);

		return new Tool("mc_get_time",
				"Returns the current time of day in ticks, the day number and the phase.",
				schema,
				args -> MainThread.call(() -> {
					Holder<WorldClock> clock = mainClock();
					long total = clockManager().getInstance(clock).totalTicks();
					long dayTime = Math.floorMod(total, 24000L);

					JsonObject o = Args.json(
							"totalTicks", total,
							"dayTime", dayTime,
							"day", total / 24000L,
							"phase", dayTime < 12000 ? "day" : (dayTime < 13800 ? "sunset"
									: (dayTime < 22200 ? "night" : "sunrise")));
					o.addProperty("dimension", Worlds.levelFor(args).dimension().identifier().toString());
					return ToolResult.ok(o);
				}));
	}

	private static Tool setWeather() {
		JsonObject schema = Schemas.object();
		Schemas.addEnum(schema, "weather", "Weather.", "clear", "rain", "thunder");
		Schemas.addProperty(schema, "duration_seconds", "integer",
				"How many seconds the weather lasts. 0 means until the natural change.");
		Schemas.addDimension(schema);

		return new Tool("mc_set_weather",
				"Controls the weather: clear, rain or thunder. Duration is optional.",
				schema,
				args -> {
					String weather = Args.requireString(args, "weather").toLowerCase();
					int ticks = Args.optInt(args, "duration_seconds", 0) * 20;

					return MainThread.call(() -> {
						ServerLevel level = Worlds.levelFor(args);
						var data = level.getWeatherData();
						switch (weather) {
							case "clear" -> {
								data.setRaining(false);
								data.setThundering(false);
								data.setRainTime(ticks);
								data.setThunderTime(ticks);
								data.setClearWeatherTime(ticks);
							}
							case "rain" -> {
								data.setRaining(true);
								data.setThundering(false);
								data.setRainTime(ticks);
								data.setThunderTime(0);
								data.setClearWeatherTime(0);
							}
							case "thunder" -> {
								data.setRaining(true);
								data.setThundering(true);
								data.setRainTime(ticks);
								data.setThunderTime(ticks);
								data.setClearWeatherTime(0);
							}
							default -> throw new IllegalArgumentException("Unknown weather: " + weather);
						}
						return ToolResult.ok(Args.json(
								"weather", weather,
								"raining", level.isRaining(),
								"thundering", level.isThundering()));
					});
				});
	}

	private static Tool setDifficulty() {
		JsonObject schema = Schemas.object();
		Schemas.addEnum(schema, "difficulty", "Difficulty.", "peaceful", "easy", "normal", "hard");

		return new Tool("mc_set_difficulty",
				"Changes the world difficulty.",
				schema,
				args -> {
					String d = Args.requireString(args, "difficulty").toLowerCase();
					return MainThread.call(() -> {
						Difficulty diff = switch (d) {
							case "peaceful" -> Difficulty.PEACEFUL;
							case "easy" -> Difficulty.EASY;
							case "normal" -> Difficulty.NORMAL;
							case "hard" -> Difficulty.HARD;
							default -> throw new IllegalArgumentException("Unknown difficulty: " + d);
						};
						Worlds.server().setDifficulty(diff, true);
						return ToolResult.ok(Args.json("difficulty", diff.getSerializedName()));
					});
				});
	}

	private static Tool runCommand() {
		JsonObject schema = Schemas.object();
		Schemas.addProperty(schema, "command", "string",
				"Command without a leading slash, e.g. give @s diamond 64");
		Schemas.require(schema, "command");
		Schemas.addProperty(schema, "player", "string",
				"Whose identity to run it as (@s). Defaults to the first connected player.");

		return new Tool("mc_run_command",
				"Runs any server command as the player. The escape hatch for anything "
						+ "that has no dedicated tool (summon, effect, setblock, gamerule, etc.).",
				schema,
				args -> {
					String raw = Args.requireString(args, "command").trim();
					String command = raw.startsWith("/") ? raw.substring(1) : raw;
					String playerName = Args.optString(args, "player", null);

					if (!McpConfig.get().allowRunCommand) {
						throw new dev.mcpcraft.mcp.McpProtocol.ToolCallException(
								"Command execution is disabled in the config (allowRunCommand=false)");
					}

					return MainThread.call(() -> {
						ServerPlayer p = Worlds.player(playerName);
						var server = p.level().getServer();
						int result = 0;
						try {
							result = server.getCommands().getDispatcher()
									.execute(command, server.createCommandSourceStack()
											.withEntity(p)
											.withPosition(p.position())
											.withLevel(p.level())
											.withPermission(
													net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS));
						} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
							// Fallback: some commands need the full pipeline.
							server.getCommands().performPrefixedCommand(
									server.createCommandSourceStack()
											.withEntity(p)
											.withPosition(p.position())
											.withLevel(p.level())
											.withPermission(
													net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS),
									command);
						}
						return ToolResult.ok(Args.json("command", command, "result", result, "executed", true));
					});
				});
	}

	private static Tool sendMessage() {
		JsonObject schema = Schemas.object();
		Schemas.addProperty(schema, "text", "string", "Message text.");
		Schemas.require(schema, "text");
		Schemas.addEnum(schema, "mode", "Where to show the message.",
				"chat", "actionbar", "title", "subtitle");
		Schemas.addProperty(schema, "player", "string", "Player name. Defaults to the first connected player.");

		return new Tool("mc_send_message",
				"Shows a message to the player: in chat, above the hotbar, as a title or subtitle.",
				schema,
				args -> {
					String text = Args.requireString(args, "text");
					String mode = Args.optString(args, "mode", "chat").toLowerCase();
					String playerName = Args.optString(args, "player", null);

					return MainThread.call(() -> {
						ServerPlayer p = Worlds.player(playerName);
						var component = net.minecraft.network.chat.Component.literal(text);
						switch (mode) {
							case "chat" -> p.sendSystemMessage(component);
							case "actionbar" -> p.sendOverlayMessage(component);
							case "title" -> p.connection.send(new net.minecraft.network.protocol.game
									.ClientboundSetTitleTextPacket(component));
							case "subtitle" -> p.connection.send(new net.minecraft.network.protocol.game
									.ClientboundSetSubtitleTextPacket(component));
							default -> throw new IllegalArgumentException("Unknown mode: " + mode);
						}
						return ToolResult.ok(Args.json("sent", true, "mode", mode));
					});
				});
	}
}
