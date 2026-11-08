package dev.mcpcraft;

import com.google.gson.JsonObject;
import dev.mcpcraft.util.Args;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Argument parsing and identifier normalization. */
class ArgsTest {

	@Test
	void normalizeIdAddsNamespace() {
		assertEquals("minecraft:stone", Args.normalizeId("stone"));
		assertEquals("minecraft:oak_planks", Args.normalizeId("OAK_PLANKS"));
		assertEquals("mymod:thing", Args.normalizeId("mymod:thing"));
	}

	@Test
	void normalizeIdRejectsBlank() {
		assertThrows(IllegalArgumentException.class, () -> Args.normalizeId(""));
		assertThrows(IllegalArgumentException.class, () -> Args.normalizeId("   "));
	}

	@Test
	void requireStringThrowsWhenMissing() {
		JsonObject o = new JsonObject();
		assertThrows(IllegalArgumentException.class, () -> Args.requireString(o, "block"));
	}

	@Test
	void optionalHelpersUseDefaults() {
		JsonObject o = new JsonObject();
		assertEquals(7, Args.optInt(o, "n", 7));
		assertEquals("def", Args.optString(o, "s", "def"));
		assertTrue(Args.optBool(o, "b", true));
		assertEquals(1.5, Args.optDouble(o, "d", 1.5), 1e-9);
		assertFalse(Args.optBool(o, "b2", false));
	}

	@Test
	void posAcceptsObjectAndArray() {
		JsonObject obj = new JsonObject();
		JsonObject pos = new JsonObject();
		pos.addProperty("x", 3);
		pos.addProperty("y", 64);
		pos.addProperty("z", -5);
		obj.add("from", pos);

		var block = Args.pos(obj, "from");
		assertEquals(3, block.getX());
		assertEquals(64, block.getY());
		assertEquals(-5, block.getZ());
	}

	@Test
	void jsonHelperHandlesMixedTypes() {
		JsonObject o = Args.json("int", 1, "bool", true, "str", "x", "null", null);
		assertEquals(1, o.get("int").getAsInt());
		assertTrue(o.get("bool").getAsBoolean());
		assertEquals("x", o.get("str").getAsString());
		assertTrue(o.get("null").isJsonNull());
	}
}
