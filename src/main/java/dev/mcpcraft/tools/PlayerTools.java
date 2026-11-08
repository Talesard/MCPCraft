package dev.mcpcraft.tools;

import com.google.gson.JsonObject;
import dev.mcpcraft.mcp.Tool;
import dev.mcpcraft.mcp.ToolResult;
import dev.mcpcraft.util.Args;
import dev.mcpcraft.util.MainThread;
import dev.mcpcraft.util.Worlds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;

/** Player actions: teleport, game mode, health, hunger, flight. */
public final class PlayerTools {
	private PlayerTools() {
	}

	public static List<Tool> create() {
		List<Tool> tools = new ArrayList<>();
		tools.add(teleport());
		tools.add(setAttributes());
		return tools;
	}

	private static Tool teleport() {
		JsonObject schema = Schemas.object();
		Schemas.addPos(schema, "Destination coordinates.");
		Schemas.addProperty(schema, "player", "string", "Player name. Defaults to the first connected player.");
		Schemas.addDimension(schema);
		Schemas.addProperty(schema, "yaw", "number", "Horizontal rotation (degrees).");
		Schemas.addProperty(schema, "pitch", "number", "Vertical look angle (degrees).");

		return new Tool("mc_teleport_player",
				"Teleports a player to a position, including a different dimension.",
				schema,
				args -> {
					int x = Args.requireInt(args, "x");
					int y = Args.requireInt(args, "y");
					int z = Args.requireInt(args, "z");
					String name = Args.optString(args, "player", null);
					float yaw = (float) Args.optDouble(args, "yaw", 0);
					float pitch = (float) Args.optDouble(args, "pitch", 0);
					boolean hasYaw = args.has("yaw");

					return MainThread.call(() -> {
						ServerPlayer p = Worlds.player(name);
						ServerLevel level = Worlds.levelFor(args);
						p.teleportTo(level, x + 0.5, y, z + 0.5,
								java.util.Set.of(), hasYaw ? yaw : p.getYRot(), pitch, true);
						return ToolResult.ok(Args.json(
								"player", p.getName().getString(),
								"dimension", level.dimension().identifier().toString(),
								"x", p.getX(), "y", p.getY(), "z", p.getZ()));
					});
				});
	}

	private static Tool setAttributes() {
		JsonObject schema = Schemas.object();
		Schemas.addProperty(schema, "player", "string", "Player name. Defaults to the first connected player.");
		Schemas.addEnum(schema, "gamemode", "Game mode.", "survival", "creative", "adventure", "spectator");
		Schemas.addProperty(schema, "health", "number", "Health to set (0..20 by default).");
		Schemas.addProperty(schema, "food", "integer", "Hunger level 0..20.");
		Schemas.addProperty(schema, "xp_levels", "integer", "Experience level to set.");
		Schemas.addProperty(schema, "heal", "boolean", "Fully heal.");
		Schemas.addProperty(schema, "feed", "boolean", "Fully feed.");
		Schemas.addProperty(schema, "allow_flight", "boolean", "Allow flight (creative).");
		Schemas.addProperty(schema, "invulnerable", "boolean", "Make the player invulnerable.");

		return new Tool("mc_set_player_attributes",
				"Changes player state: game mode, health, hunger, experience, flight, invulnerability. "
						+ "Only the parameters that are passed are applied.",
				schema,
				args -> {
					String name = Args.optString(args, "player", null);
					return MainThread.call(() -> {
						ServerPlayer p = Worlds.player(name);
						JsonObject applied = new JsonObject();

						if (args.has("gamemode")) {
							String gm = Args.requireString(args, "gamemode").toLowerCase();
							GameType type = switch (gm) {
								case "survival", "0" -> GameType.SURVIVAL;
								case "creative", "1" -> GameType.CREATIVE;
								case "adventure", "2" -> GameType.ADVENTURE;
								case "spectator", "3" -> GameType.SPECTATOR;
								default -> throw new IllegalArgumentException("Unknown game mode: " + gm);
							};
							p.setGameMode(type);
							applied.addProperty("gamemode", gm);
						}

						if (args.has("health")) {
							float hp = (float) Args.optDouble(args, "health", p.getHealth());
							p.setHealth(Math.max(1, Math.min(hp, p.getMaxHealth())));
							applied.addProperty("health", p.getHealth());
						}

						if (args.has("food")) {
							int food = Args.requireInt(args, "food");
							p.getFoodData().setFoodLevel(Math.max(0, Math.min(20, food)));
							applied.addProperty("food", food);
						}

						if (args.has("xp_levels")) {
							int lvl = Args.requireInt(args, "xp_levels");
							p.setExperienceLevels(lvl);
							applied.addProperty("xp_levels", lvl);
						}

						if (Args.optBool(args, "heal", false)) {
							p.setHealth(p.getMaxHealth());
							p.clearFire();
							applied.addProperty("healed", true);
						}

						if (Args.optBool(args, "feed", false)) {
							p.getFoodData().setFoodLevel(20);
							p.getFoodData().setSaturation(20f);
							applied.addProperty("fed", true);
						}

						if (args.has("allow_flight")) {
							boolean allow = Args.optBool(args, "allow_flight", false);
							p.getAbilities().mayfly = allow;
							if (!allow) {
								p.getAbilities().flying = false;
							}
							p.onUpdateAbilities();
							applied.addProperty("allow_flight", allow);
						}

						if (args.has("invulnerable")) {
							boolean inv = Args.optBool(args, "invulnerable", false);
							p.setPermanentlyInvulnerable(inv);
							applied.addProperty("invulnerable", inv);
						}

						JsonObject res = Args.json("player", p.getName().getString());
						res.add("applied", applied);
						return ToolResult.ok(res);
					});
				});
	}
}
