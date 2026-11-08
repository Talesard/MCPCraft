package dev.mcpcraft.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpcraft.mcp.Tool;
import dev.mcpcraft.mcp.ToolResult;
import dev.mcpcraft.util.Args;
import dev.mcpcraft.util.MainThread;
import dev.mcpcraft.util.Worlds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Giving items and managing the player inventory. */
public final class ItemTools {
	private ItemTools() {
	}

	public static List<Tool> create() {
		List<Tool> tools = new ArrayList<>();
		tools.add(giveItem());
		tools.add(clearInventory());
		return tools;
	}

	private static Tool giveItem() {
		JsonObject schema = Schemas.object();
		Schemas.addProperty(schema, "item", "string",
				"Item id: diamond, minecraft:diamond_sword (the prefix is added automatically).");
		Schemas.require(schema, "item");
		Schemas.addProperty(schema, "count", "integer", "Amount. Default 1 (max 6400).");
		Schemas.addProperty(schema, "custom_name", "string", "Display name of the item.");
		Schemas.addProperty(schema, "player", "string", "Player name. Defaults to the first connected player.");

		return new Tool("mc_give_item",
				"Gives items to a player. If the inventory is full the rest is dropped nearby. "
						+ "Returns how much was actually placed.",
				schema,
				args -> {
					String itemRaw = Args.requireString(args, "item");
					int count = Args.optInt(args, "count", 1);
					String customName = Args.optString(args, "custom_name", null);
					String playerName = Args.optString(args, "player", null);

					if (count < 1 || count > 6400) {
						throw new IllegalArgumentException("count must be between 1 and 6400");
					}
					Item item = resolveItem(itemRaw);

					return MainThread.call(() -> {
						ServerPlayer p = Worlds.player(playerName);

						int remaining = count;
						int maxStack = Math.max(1, item.getDefaultMaxStackSize());
						while (remaining > 0) {
							int batch = Math.min(remaining, maxStack);
							ItemStack stack = new ItemStack(item, batch);
							if (customName != null) {
								stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
										net.minecraft.network.chat.Component.literal(customName));
							}
							if (!p.getInventory().add(stack)) {
								p.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
							}
							remaining -= batch;
						}

						return ToolResult.ok(Args.json(
								"player", p.getName().getString(),
								"item", BuiltInRegistries.ITEM.getKey(item).toString(),
								"count", count));
					});
				});
	}

	private static Tool clearInventory() {
		JsonObject schema = Schemas.object();
		Schemas.addProperty(schema, "player", "string", "Player name. Defaults to the first connected player.");

		return new Tool("mc_clear_inventory",
				"Clears the player's inventory completely.",
				schema,
				args -> {
					String playerName = Args.optString(args, "player", null);
					return MainThread.call(() -> {
						ServerPlayer p = Worlds.player(playerName);
						p.getInventory().clearContent();
						return ToolResult.ok(Args.json("player", p.getName().getString(), "cleared", true));
					});
				});
	}

	static Item resolveItem(String raw) {
		String id = Args.normalizeId(raw);
		Identifier loc = Identifier.tryParse(id);
		if (loc == null) {
			throw new IllegalArgumentException("Invalid item id: " + raw);
		}
		Item item = BuiltInRegistries.ITEM.getValue(loc);
		if (item == null) {
			throw new IllegalArgumentException("Unknown item: " + raw);
		}
		return item;
	}

	static JsonArray inventoryJson(ServerPlayer p) {
		JsonArray arr = new JsonArray();
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			arr.add(Args.json(
					"slot", i,
					"item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
					"count", stack.getCount()));
		}
		return arr;
	}
}
