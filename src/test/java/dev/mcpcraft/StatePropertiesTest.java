package dev.mcpcraft;

import dev.mcpcraft.util.StateProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Message text and behavior of block state property validation.
 *
 * <p>Exercises the pure message builders ({@code unknownProperty},
 * {@code describeNames}), which need no Minecraft registries. {@code apply}
 * uses the same code, so the text an agent sees is guaranteed to match.</p>
 */
class StatePropertiesTest {

	@Test
	void unknownPropertyMessageListsAvailableNames() {
		IllegalArgumentException ex = StateProperties.unknownProperty(
				"nosuchprop", "minecraft:oak_log", List.of("axis"));

		String msg = ex.getMessage();
		assertTrue(msg.contains("nosuchprop"), "should name the unknown property: " + msg);
		assertTrue(msg.contains("minecraft:oak_log"), "should name the block: " + msg);
		assertTrue(msg.contains("axis"), "should list the available properties: " + msg);
	}

	@Test
	void unknownPropertyMessageForBlockWithoutProperties() {
		IllegalArgumentException ex = StateProperties.unknownProperty(
				"axis", "minecraft:stone", List.of());

		String msg = ex.getMessage();
		assertTrue(msg.contains("no configurable properties"),
				"stone should explain it has no properties: " + msg);
	}

	@Test
	void describeNamesSortsAndJoins() {
		assertEquals("axis, facing, half",
				StateProperties.describeNames(List.of("half", "axis", "facing")));
	}

	@Test
	void describeNamesHandlesEmpty() {
		assertEquals("(this block has no configurable properties)",
				StateProperties.describeNames(List.of()));
		assertEquals("(this block has no configurable properties)",
				StateProperties.describeNames(null));
	}

	@Test
	void unknownPropertyReturnsIllegalArgument() {
		// The method returns the exception so the caller can throw it in context
		// (e.g. with a "blocks[2]: " prefix in batch mode).
		RuntimeException ex = StateProperties.unknownProperty("x", "minecraft:stone", List.of());
		assertTrue(ex instanceof IllegalArgumentException);
	}
}
