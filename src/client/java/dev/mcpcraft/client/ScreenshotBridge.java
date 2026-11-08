package dev.mcpcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.mcpcraft.McpCraftMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Screenshot bridge: an HTTP worker orders a frame and the capture runs in a
 * client tick, since only there is it safe to read the frame buffer.
 *
 * <p>In 26.3 {@link NativeImage} can only be written to a file, so the PNG is
 * encoded through a temporary file and then read back into memory.</p>
 */
public final class ScreenshotBridge {
	private static final Object LOCK = new Object();
	private static CompletableFuture<byte[]> pending;
	private static long requestedAt;

	private ScreenshotBridge() {
	}

	/**
	 * Requests a PNG screenshot and waits for it.
	 *
	 * @return PNG bytes, or null when capture failed
	 */
	public static byte[] requestScreenshot(long timeoutMs) {
		CompletableFuture<byte[]> future;
		synchronized (LOCK) {
			if (pending != null) {
				throw new IllegalStateException("A screenshot is already in progress, retry in a second");
			}
			pending = new CompletableFuture<>();
			requestedAt = System.currentTimeMillis();
			future = pending;
		}
		try {
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			synchronized (LOCK) {
				pending = null;
			}
			McpCraftMod.LOGGER.warn("Minecraft MCP: failed to obtain a screenshot", e);
			return null;
		}
	}

	/** Called on every client tick. */
	public static void tick(Minecraft client) {
		CompletableFuture<byte[]> future;
		synchronized (LOCK) {
			if (pending == null) {
				return;
			}
			if (System.currentTimeMillis() - requestedAt > 10_000) {
				var stale = pending;
				pending = null;
				stale.completeExceptionally(new IllegalStateException("Timed out waiting for a screenshot"));
				return;
			}
			if (client.level == null || client.player == null) {
				var noWorld = pending;
				pending = null;
				noWorld.completeExceptionally(
						new IllegalStateException("Screenshots are only available while a world is open"));
				return;
			}
			future = pending;
			pending = null;
		}

		try {
			Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
				try (image) {
					Path tmp = Files.createTempFile("mcpcraft-shot", ".png");
					try {
						image.writeToFile(tmp);
						future.complete(Files.readAllBytes(tmp));
					} finally {
						Files.deleteIfExists(tmp);
					}
				} catch (Exception e) {
					future.completeExceptionally(e);
				}
			});
		} catch (Throwable t) {
			future.completeExceptionally(t);
		}
	}
}
