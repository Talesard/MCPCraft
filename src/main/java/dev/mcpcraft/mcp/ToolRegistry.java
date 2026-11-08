package dev.mcpcraft.mcp;

import dev.mcpcraft.tools.BlockTools;
import dev.mcpcraft.tools.BuildTools;
import dev.mcpcraft.tools.EffectsTool;
import dev.mcpcraft.tools.EntityTools;
import dev.mcpcraft.tools.FunTools;
import dev.mcpcraft.tools.ItemTools;
import dev.mcpcraft.tools.MetaTools;
import dev.mcpcraft.tools.PlayerTools;
import dev.mcpcraft.tools.QueryTools;
import dev.mcpcraft.tools.WorldSettingTools;

/** Registry of MCP tools, populated during mod initialization. */
public final class ToolRegistry {
	/** Mutable map so client-side modules can add their own tools later. */
	private static final java.util.Map<String, Tool> TOOLS = new java.util.LinkedHashMap<>();

	private ToolRegistry() {
	}

	public static synchronized void register(Tool tool) {
		Tool prev = TOOLS.put(tool.name(), tool);
		if (prev != null) {
			throw new IllegalStateException("Duplicate tool: " + tool.name());
		}
	}

	public static void registerAll(java.util.List<Tool> tools) {
		for (Tool t : tools) {
			register(t);
		}
	}

	public static Tool get(String name) {
		return TOOLS.get(name);
	}

	public static java.util.Collection<Tool> all() {
		return TOOLS.values();
	}

	public static int size() {
		return TOOLS.size();
	}

	/** Registers the base tool set (logic side). */
	public static synchronized void bootstrap() {
		if (!TOOLS.isEmpty()) {
			return;
		}
		registerAll(MetaTools.create());
		registerAll(QueryTools.create());
		registerAll(BlockTools.create());
		registerAll(BuildTools.create());
		registerAll(EntityTools.create());
		registerAll(ItemTools.create());
		registerAll(EffectsTool.create());
		registerAll(PlayerTools.create());
		registerAll(WorldSettingTools.create());
		registerAll(FunTools.create());
	}
}
