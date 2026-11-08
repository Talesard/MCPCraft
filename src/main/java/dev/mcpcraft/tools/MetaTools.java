package dev.mcpcraft.tools;

import com.google.gson.JsonObject;
import dev.mcpcraft.mcp.Tool;
import dev.mcpcraft.mcp.ToolRegistry;
import dev.mcpcraft.mcp.ToolResult;
import dev.mcpcraft.util.BuildInfo;

import java.util.ArrayList;
import java.util.List;

/** Build information: which exact jar answers requests. */
public final class MetaTools {
	private MetaTools() {
	}

	public static List<Tool> create() {
		List<Tool> tools = new ArrayList<>();
		tools.add(version());
		return tools;
	}

	private static Tool version() {
		return new Tool("mc_version",
				"Returns the mod version, build time and git commit. "
						+ "The first call reveals which build is actually installed in the game.",
				Schemas.object(),
				args -> {
					JsonObject o = new JsonObject();
					o.addProperty("mod_version", BuildInfo.version());
					o.addProperty("build_time", BuildInfo.buildTime());
					o.addProperty("git_commit", BuildInfo.gitCommit());
					o.addProperty("tool_count", ToolRegistry.size());
					o.addProperty("protocol_version", dev.mcpcraft.mcp.McpProtocol.PROTOCOL_VERSION);
					return ToolResult.ok(o);
				});
	}
}
