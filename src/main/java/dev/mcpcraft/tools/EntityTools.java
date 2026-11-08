package dev.mcpcraft.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpcraft.config.McpConfig;
import dev.mcpcraft.mcp.Tool;
import dev.mcpcraft.mcp.ToolResult;
import dev.mcpcraft.util.Args;
import dev.mcpcraft.util.MainThread;
import dev.mcpcraft.util.Worlds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Spawning, finding and controlling entities. */
public final class EntityTools {
	private static final Random RANDOM = new Random();

	private EntityTools() {
	}

	public static List<Tool> create() {
		List<Tool> tools = new ArrayList<>();
		tools.add(spawnEntity());
		tools.add(listEntities());
		tools.add(killEntities());
		tools.add(entityAction());
		return tools;
	}

	private static Tool spawnEntity() {
		JsonObject schema = Schemas.object();
		Schemas.addProperty(schema, "type", "string",
				"Entity type: minecraft:zombie, cow, creeper (the prefix is added automatically).");
		Schemas.require(schema, "type");
		Schemas.addPos(schema, "Spawn point.");
		Schemas.addProperty(schema, "count", "integer",
				"How many to spawn (1.." + McpConfig.get().maxEntitiesPerCall + ").");
		Schemas.addProperty(schema, "spread", "number", "Random spread radius around the point. Default 0.");
		Schemas.addProperty(schema, "custom_name", "string", "Visible name tag above the entity.");
		Schemas.addProperty(schema, "name_visible", "boolean",
				"Show the name. Defaults to true when custom_name is set.");
		Schemas.addProperty(schema, "glowing", "boolean", "Outline the entity with a glow.");
		Schemas.addProperty(schema, "no_ai", "boolean", "Disable AI (statue).");
		Schemas.addProperty(schema, "invulnerable", "boolean", "Make the entity invulnerable.");
		Schemas.addProperty(schema, "health", "number", "Health to set on living entities.");
		Schemas.addProperty(schema, "persistent", "boolean", "Never despawn when players move away.");
		Schemas.addDimension(schema);

		return new Tool("mc_spawn_entity",
				"Spawns mobs and other entities. Name, glow, disabled AI, health and count can be set at once. "
						+ "Returns UUIDs and positions of the created entities.",
				schema,
				args -> {
					String typeRaw = Args.requireString(args, "type");
					BlockPos pos = Args.posXYZ(args);
					int count = Args.optInt(args, "count", 1);
					double spread = Args.optDouble(args, "spread", 0);
					String customName = Args.optString(args, "custom_name", null);
					boolean nameVisible = Args.optBool(args, "name_visible", customName != null);
					boolean glowing = Args.optBool(args, "glowing", false);
					boolean noAi = Args.optBool(args, "no_ai", false);
					boolean invulnerable = Args.optBool(args, "invulnerable", false);
					double health = Args.optDouble(args, "health", -1);
					boolean persistent = Args.optBool(args, "persistent", false);

					int limit = McpConfig.get().maxEntitiesPerCall;
					if (count < 1) {
						throw new IllegalArgumentException("count must be >= 1");
					}
					if (count > limit) {
						throw new dev.mcpcraft.mcp.McpProtocol.LimitException(
								"count=" + count + " exceeds the limit of " + limit);
					}
					EntityType<?> type = resolveType(typeRaw);

					return MainThread.call(() -> {
						ServerLevel level = Worlds.levelFor(args);

						JsonArray created = new JsonArray();
						int failed = 0;
						for (int i = 0; i < count; i++) {
							double px = pos.getX() + 0.5;
							double py = pos.getY();
							double pz = pos.getZ() + 0.5;
							if (spread > 0) {
								px += (RANDOM.nextDouble() * 2 - 1) * spread;
								pz += (RANDOM.nextDouble() * 2 - 1) * spread;
								py += (RANDOM.nextDouble() * 2 - 1) * Math.min(spread, 4);
							}

							Entity entity = type.spawn(level, BlockPos.containing(px, py, pz),
									EntitySpawnReason.COMMAND);
							if (entity == null) {
								failed++;
								continue;
							}
							entity.setPos(px, py, pz);
							if (customName != null) {
								entity.setCustomName(Component.literal(customName));
								entity.setCustomNameVisible(nameVisible);
							}
							if (glowing) {
								entity.setGlowingTag(true);
							}
							if (invulnerable) {
								entity.setPermanentlyInvulnerable(true);
							}
							if (persistent && entity instanceof Mob mobForPersistence) {
								mobForPersistence.setPersistenceRequired();
							}
							if (entity instanceof Mob mob && noAi) {
								mob.setNoAi(true);
							}
							if (entity instanceof LivingEntity living && health > 0) {
								living.setHealth((float) Math.min(health, living.getMaxHealth()));
							}
							created.add(Args.json(
									"uuid", entity.getUUID().toString(),
									"type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
									"x", entity.getX(), "y", entity.getY(), "z", entity.getZ()));
						}

						JsonObject res = Args.json(
								"dimension", level.dimension().identifier().toString(),
								"requested", count,
								"spawned", created.size(),
								"failed", failed);
						res.add("entities", created);
						return ToolResult.ok(res);
					});
				});
	}

	private static Tool listEntities() {
		JsonObject schema = Schemas.object();
		Schemas.addProperty(schema, "radius", "integer",
				"Search radius around the player/point. Default " + McpConfig.get().defaultEntityRadius + ".");
		Schemas.addProperty(schema, "type", "string", "Filter by type, e.g. minecraft:zombie.");
		Schemas.addProperty(schema, "limit", "integer", "Maximum entries in the response. Default 100.");
		Schemas.addProperty(schema, "around_player", "boolean",
				"Search around the player. Default true, but explicit x/y/z take priority.");
		Schemas.addPos(schema, "Search point. When given, the search runs around it.");
		Schemas.addDimension(schema);

		return new Tool("mc_list_entities",
				"Lists nearby entities: type, position, health, UUID, name. "
						+ "When x/y/z are given the search runs around that point, otherwise around the player. "
						+ "Use it to find a target before acting on it.",
				schema,
				args -> {
					int radius = Args.optInt(args, "radius", McpConfig.get().defaultEntityRadius);
					String typeFilter = Args.optString(args, "type", null);
					int limit = Args.optInt(args, "limit", 100);
					boolean aroundPlayer = Args.optBool(args, "around_player", true);
					boolean hasPos = args.has("x") && args.has("y") && args.has("z");

					if (radius < 0) {
						throw new IllegalArgumentException("radius cannot be negative");
					}
					if (limit < 1) {
						throw new IllegalArgumentException("limit must be >= 1");
					}

					return MainThread.call(() -> {
						ServerLevel level = Worlds.levelFor(args);
						double cx;
						double cy;
						double cz;
						String centerSource;
						// Explicit coordinates win over the around_player flag.
						if (hasPos) {
							cx = Args.requireInt(args, "x");
							cy = Args.requireInt(args, "y");
							cz = Args.requireInt(args, "z");
							centerSource = "position";
						} else if (aroundPlayer) {
							var p = Worlds.player(Args.optString(args, "player", null));
							cx = p.getX();
							cy = p.getY();
							cz = p.getZ();
							centerSource = "player";
						} else {
							throw new IllegalArgumentException(
									"Provide x/y/z coordinates or around_player=true");
						}

						AABB box = new AABB(cx - radius, cy - radius, cz - radius,
								cx + radius, cy + radius, cz + radius);
						String normalizedFilter = typeFilter == null ? null : Args.normalizeId(typeFilter);

						// Collect every match, sort deterministically (by UUID) and only then
						// apply the limit, so `truncated` stays honest.
						List<Entity> matched = new ArrayList<>();
						for (Entity e : level.getEntities((Entity) null, box, e -> true)) {
							String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
							if (normalizedFilter != null && !id.equals(normalizedFilter)) {
								continue;
							}
							matched.add(e);
						}
						matched.sort(Comparator.comparing(e -> e.getUUID().toString()));

						int totalFound = matched.size();
						JsonArray arr = new JsonArray();
						for (int i = 0; i < Math.min(limit, totalFound); i++) {
							Entity e = matched.get(i);
							JsonObject o = Args.json(
									"uuid", e.getUUID().toString(),
									"type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
									"x", e.getX(), "y", e.getY(), "z", e.getZ(),
									"alive", e.isAlive());
							if (e instanceof LivingEntity living) {
								o.addProperty("health", living.getHealth());
								o.addProperty("max_health", living.getMaxHealth());
							}
							if (e.hasCustomName()) {
								o.addProperty("name", e.getCustomName().getString());
							}
							arr.add(o);
						}

						JsonObject res = Args.json(
								"dimension", level.dimension().identifier().toString(),
								"radius", radius,
								"center", Args.json("x", cx, "y", cy, "z", cz, "source", centerSource),
								"count", arr.size(),
								"found", totalFound,
								"total_found", totalFound,
								"truncated", totalFound > limit);
						res.add("entities", arr);
						return ToolResult.ok(res);
					});
				});
	}

	private static Tool killEntities() {
		JsonObject schema = Schemas.object();
		Schemas.addProperty(schema, "uuid", "string", "Remove one specific entity by UUID.");
		Schemas.addProperty(schema, "type", "string", "Filter by type for bulk removal.");
		Schemas.addProperty(schema, "radius", "integer", "Bulk removal radius around the player.");
		Schemas.addProperty(schema, "include_players", "boolean", "Whether to touch players. Default false.");
		Schemas.addDimension(schema);

		return new Tool("mc_kill_entities",
				"Removes entities: by UUID, by type within a radius, or everything within a radius. "
						+ "Players are left alone unless include_players is true.",
				schema,
				args -> {
					String uuid = Args.optString(args, "uuid", null);
					String typeFilter = Args.optString(args, "type", null);
					int radius = Args.optInt(args, "radius", -1);
					boolean includePlayers = Args.optBool(args, "include_players", false);

					if (uuid == null && typeFilter == null && radius < 0) {
						throw new IllegalArgumentException("Provide uuid, type or radius");
					}

					return MainThread.call(() -> {
						ServerLevel level = Worlds.levelFor(args);
						int removed = 0;

						if (uuid != null) {
							var id = java.util.UUID.fromString(uuid);
							for (ServerLevel l : Worlds.server().getAllLevels()) {
								Entity e = null;
								for (Entity candidate : l.getAllEntities()) {
									if (candidate.getUUID().equals(id)) {
										e = candidate;
										break;
									}
								}
								if (e != null) {
									if (!includePlayers && e instanceof net.minecraft.world.entity.player.Player) {
										throw new IllegalArgumentException(
												"The entity is a player; pass include_players=true");
									}
									e.discard();
									removed++;
								}
							}
						} else {
							var p = Worlds.player(Args.optString(args, "player", null));
							double r = radius < 0 ? McpConfig.get().defaultEntityRadius : radius;
							AABB box = new AABB(p.getX() - r, p.getY() - r, p.getZ() - r,
									p.getX() + r, p.getY() + r, p.getZ() + r);
							String normalizedFilter = typeFilter == null ? null : Args.normalizeId(typeFilter);
							List<Entity> targets = new ArrayList<>();
							for (Entity e : level.getEntities((Entity) null, box, e -> true)) {
								if (!includePlayers && e instanceof net.minecraft.world.entity.player.Player) {
									continue;
								}
								if (normalizedFilter != null
										&& !BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString()
										.equals(normalizedFilter)) {
									continue;
								}
								targets.add(e);
							}
							for (Entity e : targets) {
								e.discard();
								removed++;
							}
						}

						final int r2 = removed;
						return ToolResult.ok(Args.json("removed", r2));
					});
				});
	}

	private static Tool entityAction() {
		JsonObject schema = Schemas.object();
		Schemas.addProperty(schema, "uuid", "string", "Entity UUID.");
		Schemas.require(schema, "uuid");
		Schemas.addEnum(schema, "action", "Action to perform on the entity.",
				"glow", "unglow", "set_health", "set_scale", "no_ai", "ai", "invulnerable", "vulnerable",
				"rename", "remove", "freeze", "ignite", "extinguish");
		Schemas.require(schema, "action");
		Schemas.addProperty(schema, "value", "string", "Value for the action (health, scale, name).");

		return new Tool("mc_entity_action",
				"Controls an existing entity: glowing, health, scale, AI toggle, rename, ignite, remove. "
						+ "Returns the values that were actually applied, including engine clamping.",
				schema,
				args -> {
					String uuid = Args.requireString(args, "uuid");
					String action = Args.requireString(args, "action").toLowerCase();
					String value = Args.optString(args, "value", null);

					return MainThread.call(() -> {
						Entity entity = findAnywhere(java.util.UUID.fromString(uuid));
						if (entity == null) {
							throw new IllegalArgumentException("Entity not found: " + uuid);
						}

						JsonObject applied = new JsonObject();

						switch (action) {
							case "glow" -> {
								entity.setGlowingTag(true);
								applied.addProperty("glowing", true);
							}
							case "unglow" -> {
								entity.setGlowingTag(false);
								applied.addProperty("glowing", false);
							}
							case "invulnerable" -> {
								entity.setPermanentlyInvulnerable(true);
								applied.addProperty("invulnerable", entity.isInvulnerable());
							}
							case "vulnerable" -> {
								entity.setPermanentlyInvulnerable(false);
								applied.addProperty("invulnerable", entity.isInvulnerable());
							}
							case "remove" -> {
								entity.discard();
								applied.addProperty("removed", true);
							}
							case "set_health" -> {
								LivingEntity living = requireLiving(entity);
								float requested = Float.parseFloat(requireValue(value));
								living.setHealth(requested);
								// The engine clamps health, so report the actual value.
								applied.addProperty("requested", requested);
								applied.addProperty("applied", living.getHealth());
								applied.addProperty("health", living.getHealth());
								applied.addProperty("max_health", living.getMaxHealth());
								if (requested != living.getHealth()) {
									applied.addProperty("clamped", true);
								}
							}
							case "set_scale" -> {
								double requested = Double.parseDouble(requireValue(value));
								LivingEntity living = requireLiving(entity);
								var instance = living.getAttribute(
										net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
								if (instance == null) {
									throw new IllegalArgumentException(
											"This entity does not support scaling");
								}
								instance.setBaseValue(Math.max(0.1, requested));
								entity.refreshDimensions();
								double actual = instance.getValue();
								applied.addProperty("requested", requested);
								applied.addProperty("applied", actual);
								applied.addProperty("scale", actual);
								var dims = entity.getDimensions(entity.getPose());
								applied.addProperty("width", dims.width());
								applied.addProperty("height", dims.height());
								if (Math.abs(requested - actual) > 1e-9) {
									applied.addProperty("clamped", true);
								}
							}
							case "no_ai", "ai" -> {
								boolean enable = action.equals("ai");
								if (!(entity instanceof Mob mob)) {
									throw new IllegalArgumentException(
											"This entity is not a mob: AI applies to Mob only");
								}
								mob.setNoAi(!enable);
								applied.addProperty("no_ai", mob.isNoAi());
							}
							case "rename" -> {
								String name = requireValue(value);
								entity.setCustomName(Component.literal(name));
								entity.setCustomNameVisible(true);
								applied.addProperty("name", entity.getCustomName().getString());
								applied.addProperty("name_visible", entity.isCustomNameVisible());
							}
							case "ignite" -> {
								entity.igniteForSeconds(10);
								applied.addProperty("on_fire", entity.isOnFire());
								applied.addProperty("remaining_fire_ticks", entity.getRemainingFireTicks());
							}
							case "extinguish" -> {
								entity.clearFire();
								applied.addProperty("on_fire", entity.isOnFire());
								applied.addProperty("remaining_fire_ticks", entity.getRemainingFireTicks());
							}
							case "freeze" -> {
								entity.setTicksFrozen(200);
								applied.addProperty("ticks_frozen", entity.getTicksFrozen());
								applied.addProperty("is_frozen", entity.isFullyFrozen());
							}
							default -> throw new IllegalArgumentException("Unknown action: " + action);
						}

						JsonObject res = Args.json(
								"uuid", entity.getUUID().toString(),
								"action", action,
								"alive", entity.isAlive());
						res.add("applied", applied);
						return ToolResult.ok(res);
					});
				});
	}

	static Entity findAnywhere(java.util.UUID id) {
		for (ServerLevel l : Worlds.server().getAllLevels()) {
			for (Entity e : l.getAllEntities()) {
				if (e.getUUID().equals(id)) {
					return e;
				}
			}
		}
		return null;
	}

	private static LivingEntity requireLiving(Entity e) {
		if (e instanceof LivingEntity living) {
			return living;
		}
		throw new IllegalArgumentException("This entity is not alive (not a LivingEntity)");
	}

	private static String requireValue(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("This action requires the 'value' parameter");
		}
		return value;
	}

	static EntityType<?> resolveType(String raw) {
		String id = Args.normalizeId(raw);
		var loc = net.minecraft.resources.Identifier.tryParse(id);
		if (loc == null) {
			throw new IllegalArgumentException("Invalid type id: " + raw);
		}
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(loc);
		if (type == null) {
			throw new IllegalArgumentException("Unknown entity type: " + raw);
		}
		return type;
	}
}
