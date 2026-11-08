package dev.mcpcraft.tools;

import com.google.gson.JsonObject;
import dev.mcpcraft.mcp.Tool;
import dev.mcpcraft.mcp.ToolResult;
import dev.mcpcraft.util.Args;
import dev.mcpcraft.util.MainThread;
import dev.mcpcraft.util.Worlds;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.List;

/** Visual effects: particles, sounds, lightning, explosions, fireworks. */
public final class FunTools {
	private FunTools() {
	}

	public static List<Tool> create() {
		List<Tool> tools = new ArrayList<>();
		tools.add(spawnParticles());
		tools.add(playSound());
		tools.add(fx());
		return tools;
	}

	private static Tool spawnParticles() {
		JsonObject schema = Schemas.object();
		Schemas.addPos(schema, "Center of the particle cloud.");
		Schemas.addProperty(schema, "particle", "string",
				"Particle type: flame, smoke, heart, note, end_rod, totem_of_undying, lava "
						+ "(the minecraft: prefix is added automatically).");
		Schemas.require(schema, "particle");
		Schemas.addProperty(schema, "count", "integer", "Number of particles. Default 30.");
		Schemas.addProperty(schema, "spread", "number", "Spread. Default 0.5.");
		Schemas.addProperty(schema, "speed", "number", "Speed. Default 0.1.");
		Schemas.addDimension(schema);

		return new Tool("mc_spawn_particles",
				"Spawns a cloud of particles: fire, smoke, hearts, totems and any other. "
						+ "For cinematic moments and hints to the player.",
				schema,
				args -> {
					int x = Args.requireInt(args, "x");
					int y = Args.requireInt(args, "y");
					int z = Args.requireInt(args, "z");
					String particleRaw = Args.requireString(args, "particle");
					int count = Args.optInt(args, "count", 30);
					double spread = Args.optDouble(args, "spread", 0.5);
					double speed = Args.optDouble(args, "speed", 0.1);
					ParticleOptions particle = resolveParticle(particleRaw);

					return MainThread.call(() -> {
						ServerLevel level = Worlds.levelFor(args);
						level.sendParticles(particle, x + 0.5, y + 0.5, z + 0.5,
								count, spread, spread, spread, speed);
						return ToolResult.ok(Args.json(
								"particle", particleRaw,
								"count", count,
								"pos", Args.json("x", x, "y", y, "z", z)));
					});
				});
	}

	private static Tool playSound() {
		JsonObject schema = Schemas.object();
		Schemas.addProperty(schema, "sound", "string",
				"Sound: entity.ender_dragon.growl, block.note_block.bell, ui.toast.challenge_complete "
						+ "(the minecraft: prefix is added automatically).");
		Schemas.require(schema, "sound");
		Schemas.addPos(schema, "Where to play it.");
		Schemas.addProperty(schema, "volume", "number", "Volume. Default 1.0.");
		Schemas.addProperty(schema, "pitch", "number", "Pitch. Default 1.0.");
		Schemas.addDimension(schema);

		return new Tool("mc_play_sound",
				"Plays a sound at a world position.",
				schema,
				args -> {
					String soundRaw = Args.requireString(args, "sound");
					int x = Args.requireInt(args, "x");
					int y = Args.requireInt(args, "y");
					int z = Args.requireInt(args, "z");
					float volume = (float) Args.optDouble(args, "volume", 1.0);
					float pitch = (float) Args.optDouble(args, "pitch", 1.0);
					SoundEvent sound = resolveSound(soundRaw);

					return MainThread.call(() -> {
						ServerLevel level = Worlds.levelFor(args);
						level.playSound(null, x, y, z, sound, SoundSource.MASTER, volume, pitch);
						return ToolResult.ok(Args.json("sound", soundRaw, "played", true));
					});
				});
	}

	private static Tool fx() {
		JsonObject schema = Schemas.object();
		Schemas.addEnum(schema, "kind", "Effect type.", "lightning", "explosion", "firework");
		Schemas.require(schema, "kind");
		Schemas.addPos(schema, "Effect position.");
		Schemas.addProperty(schema, "power", "number", "Explosion power (for explosion). Default 4.");
		Schemas.addProperty(schema, "damage", "boolean", "Whether to deal damage. Default false.");
		Schemas.addProperty(schema, "colors", "array", "Firework colors as R,G,B.");
		Schemas.addDimension(schema);

		return new Tool("mc_fx",
				"Plays an effect: lightning strike, explosion or firework. Harmless by default — a safe spectacle.",
				schema,
				args -> {
					String kind = Args.requireString(args, "kind").toLowerCase();
					int x = Args.requireInt(args, "x");
					int y = Args.requireInt(args, "y");
					int z = Args.requireInt(args, "z");
					boolean damage = Args.optBool(args, "damage", false);
					double power = Args.optDouble(args, "power", 4.0);

					return MainThread.call(() -> {
						ServerLevel level = Worlds.levelFor(args);
						switch (kind) {
							case "lightning" -> {
								var bolt = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT
										.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
								if (bolt != null) {
									bolt.setPos(x + 0.5, y, z + 0.5);
									level.addFreshEntity(bolt);
								}
							}
							case "explosion" -> level.explode(null, x + 0.5, y + 0.5, z + 0.5,
									(float) power, damage,
									net.minecraft.world.level.Level.ExplosionInteraction.TNT);
							case "firework" -> {
								var fw = new net.minecraft.world.entity.projectile.FireworkRocketEntity(
										level, x + 0.5, y + 0.5, z + 0.5,
										new net.minecraft.world.item.ItemStack(
												net.minecraft.world.item.Items.FIREWORK_ROCKET));
								level.addFreshEntity(fw);
							}
							default -> throw new IllegalArgumentException("Unknown effect: " + kind);
						}
						return ToolResult.ok(Args.json("kind", kind, "pos", Args.json("x", x, "y", y, "z", z)));
					});
				});
	}

	static ParticleOptions resolveParticle(String raw) {
		String id = Args.normalizeId(raw);
		Identifier loc = Identifier.tryParse(id);
		if (loc == null) {
			throw new IllegalArgumentException("Invalid particle id: " + raw);
		}
		var type = BuiltInRegistries.PARTICLE_TYPE.getValue(loc);
		if (type instanceof ParticleOptions options) {
			return options;
		}
		throw new IllegalArgumentException("Particle does not support simple spawning: " + raw
				+ " (it needs parameters, e.g. block or dust)");
	}

	static SoundEvent resolveSound(String raw) {
		String id = Args.normalizeId(raw);
		Identifier loc = Identifier.tryParse(id);
		if (loc == null) {
			throw new IllegalArgumentException("Invalid sound id: " + raw);
		}
		SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getValue(loc);
		if (sound == null) {
			throw new IllegalArgumentException("Unknown sound: " + raw);
		}
		return sound;
	}
}
