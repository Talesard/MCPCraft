package dev.mcpcraft.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpcraft.mcp.Tool;
import dev.mcpcraft.mcp.ToolResult;
import dev.mcpcraft.util.Args;
import dev.mcpcraft.util.MainThread;
import dev.mcpcraft.util.Worlds;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/** Potion effects: applying, clearing and reading. */
public final class EffectsTool {
	private EffectsTool() {
	}

	public static List<Tool> create() {
		List<Tool> tools = new ArrayList<>();
		tools.add(setEffects());
		tools.add(clearEffects());
		return tools;
	}

	private static Tool setEffects() {
		JsonObject schema = Schemas.object();
		Schemas.addProperty(schema, "effects", "array",
				"List of effects: [{\"id\":\"minecraft:speed\",\"duration_seconds\":60,\"amplifier\":1,"
						+ "\"particles\":true,\"ambient\":false}]. amplifier is the level above base (0 = I).");
		Schemas.require(schema, "effects");
		Schemas.addProperty(schema, "target", "string",
				"Player name. Defaults to the first connected player.");
		Schemas.addProperty(schema, "entity_uuid", "string",
				"Entity UUID instead of a player; the effect is applied to that mob.");

		return new Tool("mc_set_effects",
				"Applies effects to a player or mob: speed, strength, regeneration, invisibility and any other. "
						+ "Good for scenes and challenges.",
				schema,
				args -> {
					var arr = Args.optArray(args, "effects");
					if (arr.isEmpty()) {
						throw new IllegalArgumentException("The 'effects' list is empty");
					}
					String targetName = Args.optString(args, "target", null);
					String uuid = Args.optString(args, "entity_uuid", null);

					return MainThread.call(() -> {
						LivingEntity target = resolveTarget(targetName, uuid);
						JsonArray applied = new JsonArray();
						for (var el : arr) {
							JsonObject o = el.getAsJsonObject();
							String id = Args.requireString(o, "id");
							int durationSec = Args.optInt(o, "duration_seconds", 30);
							int amplifier = Args.optInt(o, "amplifier", 0);
							boolean particles = Args.optBool(o, "particles", true);
							boolean ambient = Args.optBool(o, "ambient", false);

							Holder<MobEffect> effect = resolveEffect(id);
							int ticks = Math.max(1, durationSec * 20);
							target.addEffect(new MobEffectInstance(effect, ticks, amplifier, ambient, particles));
							applied.add(Args.json(
									"id", BuiltInRegistries.MOB_EFFECT.getKey(effect.value()).toString(),
									"duration_seconds", durationSec,
									"amplifier", amplifier));
						}
						JsonObject res = Args.json("applied", applied.size());
						res.add("effects", applied);
						return ToolResult.ok(res);
					});
				});
	}

	private static Tool clearEffects() {
		JsonObject schema = Schemas.object();
		Schemas.addProperty(schema, "target", "string", "Player name. Defaults to the first connected player.");
		Schemas.addProperty(schema, "entity_uuid", "string", "Entity UUID instead of a player.");

		return new Tool("mc_clear_effects",
				"Removes all effects from a player or mob.",
				schema,
				args -> {
					String targetName = Args.optString(args, "target", null);
					String uuid = Args.optString(args, "entity_uuid", null);
					return MainThread.call(() -> {
						LivingEntity target = resolveTarget(targetName, uuid);
						boolean ok = target.removeAllEffects();
						return ToolResult.ok(Args.json("cleared", ok));
					});
				});
	}

	/** Resolves the target: a mob by UUID, otherwise a player by name/default. */
	static LivingEntity resolveTarget(String playerName, String uuid) {
		if (uuid != null && !uuid.isBlank()) {
			var server = Worlds.server();
			var id = java.util.UUID.fromString(uuid);
			for (var level : server.getAllLevels()) {
				var entity = level.getEntityInAnyDimension(id);
				if (entity instanceof LivingEntity living) {
					return living;
				}
			}
			throw new IllegalArgumentException("Entity not found: " + uuid);
		}
		return Worlds.player(playerName);
	}

	static Holder<MobEffect> resolveEffect(String raw) {
		String id = Args.normalizeId(raw);
		var loc = net.minecraft.resources.Identifier.tryParse(id);
		if (loc == null) {
			throw new IllegalArgumentException("Invalid effect id: " + raw);
		}
		var ref = BuiltInRegistries.MOB_EFFECT.get(loc).orElse(null);
		if (ref == null) {
			throw new IllegalArgumentException("Unknown effect: " + raw);
		}
		return ref;
	}

	static JsonArray effectsJson(LivingEntity entity) {
		JsonArray arr = new JsonArray();
		for (MobEffectInstance inst : entity.getActiveEffects()) {
			arr.add(Args.json(
					"id", BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect().value()).toString(),
					"duration_seconds", inst.getDuration() / 20,
					"amplifier", inst.getAmplifier(),
					"ambient", inst.isAmbient(),
					"visible", inst.isVisible()));
		}
		return arr;
	}
}
