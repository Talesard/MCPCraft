package dev.mcpcraft.util;

import dev.mcpcraft.McpCraftMod;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Build metadata for the mod: version, build time, git commit.
 *
 * <p>Values are substituted at build time into {@code mcpcraft-build.properties}.
 * They let an agent tell the installed jar apart from the working copy: on a
 * mismatch the stale build tag is immediately visible.</p>
 */
public final class BuildInfo {
	private static final String UNKNOWN = "unknown";

	private static final String VERSION;
	private static final String BUILD_TIME;
	private static final String GIT_COMMIT;

	static {
		Properties props = new Properties();
		try (InputStream in = BuildInfo.class.getResourceAsStream("/mcpcraft-build.properties")) {
			if (in != null) {
				props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
			}
		} catch (Exception e) {
			McpCraftMod.LOGGER.warn("Minecraft MCP: failed to read build metadata", e);
		}
		VERSION = props.getProperty("version", UNKNOWN);
		BUILD_TIME = props.getProperty("build_time", UNKNOWN);
		GIT_COMMIT = props.getProperty("git_commit", UNKNOWN);
	}

	private BuildInfo() {
	}

	public static String version() {
		return VERSION;
	}

	public static String buildTime() {
		return BUILD_TIME;
	}

	public static String gitCommit() {
		return GIT_COMMIT;
	}

	public static String describe() {
		return VERSION + " (" + BUILD_TIME + ", commit " + GIT_COMMIT + ")";
	}
}
