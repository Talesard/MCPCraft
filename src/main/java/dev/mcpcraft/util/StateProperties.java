package dev.mcpcraft.util;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Applies string-valued block state properties.
 *
 * <p>Minecraft keeps a {@link Property} object for every property of a block
 * state in {@link BlockState#getProperties()}. A JSON value is coerced to the
 * required type (boolean / int / enum) and applied via
 * {@link BlockState#trySetValue}.</p>
 *
 * <p>Validation is strict: an unknown property name is an error listing the
 * available names instead of a silent no-op. Otherwise a typo such as
 * {@code axes} instead of {@code axis} would leave the block in its default
 * state while the agent believes the call succeeded.</p>
 */
public final class StateProperties {
	private StateProperties() {
	}

	/**
	 * Applies a "property to value" pair to a block state.
	 *
	 * @throws IllegalArgumentException when the block has no such property
	 *                                  or the value is not acceptable
	 */
	public static BlockState apply(BlockState state, Block block, String rawName, String rawValue) {
		String name = rawName == null ? "" : rawName.trim();
		List<String> available = new ArrayList<>();

		for (Property<?> prop : state.getProperties()) {
			available.add(prop.getName());
			if (prop.getName().equalsIgnoreCase(name)) {
				return applyTyped(state, prop, rawValue);
			}
		}

		throw unknownProperty(rawName, blockId(block), available);
	}

	/**
	 * Builds the unknown-property error. Extracted so the message can be
	 * unit tested without booting Minecraft.
	 */
	public static IllegalArgumentException unknownProperty(String rawName, String blockId,
														  Collection<String> available) {
		return new IllegalArgumentException(
				"Unknown property '" + rawName + "' for " + blockId
						+ "; available: " + describeNames(available));
	}

	/** Sorted, comma-separated list of names for error messages. */
	public static String describeNames(Collection<String> available) {
		if (available == null || available.isEmpty()) {
			return "(this block has no configurable properties)";
		}
		List<String> names = new ArrayList<>(available);
		names.sort(String::compareTo);
		return String.join(", ", names);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static <T extends Comparable<T>> BlockState applyTyped(BlockState state, Property<T> prop, String rawValue) {
		var parsed = prop.getValue(rawValue.trim());
		if (parsed.isEmpty()) {
			// Retry with boolean/numeric normalization (True/1 -> true).
			String v = rawValue.trim().toLowerCase();
			parsed = prop.getValue(v);
		}
		if (parsed.isEmpty()) {
			throw new IllegalArgumentException(
					"Invalid value '" + rawValue + "' for property " + prop.getName()
							+ "; available: " + possibleValues(prop));
		}
		return state.trySetValue((Property) prop, (T) parsed.get());
	}

	private static String blockId(Block block) {
		try {
			return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
		} catch (RuntimeException e) {
			return "this block";
		}
	}

	private static <T extends Comparable<T>> String possibleValues(Property<T> prop) {
		List<String> out = new ArrayList<>();
		for (T v : prop.getPossibleValues()) {
			out.add(prop.getName(v));
		}
		return String.join(", ", out);
	}
}
