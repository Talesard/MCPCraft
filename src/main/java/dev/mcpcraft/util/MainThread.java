package dev.mcpcraft.util;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bridge between HTTP workers and the game main thread.
 *
 * <p>Any access to the world, inventories or entities must happen on the
 * Minecraft server thread. This class keeps a reference to the current
 * {@link MinecraftServer} and forwards work through {@code submit(Supplier)}
 * with a timeout.</p>
 */
public final class MainThread {
	private static volatile MinecraftServer server;

	private MainThread() {
	}

	public static void init() {
	}

	public static void setServer(MinecraftServer s) {
		server = s;
	}

	public static void clearServer() {
		server = null;
	}

	public static boolean hasServer() {
		return server != null;
	}

	public static MinecraftServer server() {
		return server;
	}

	/**
	 * Runs a task on the game main thread and waits for the result.
	 *
	 * @throws IllegalStateException when no world is loaded
	 * @throws RuntimeException      with the original cause if the task threw
	 */
	public static <T> T call(Callable<T> task, long timeoutMs) {
		MinecraftServer s = server;
		if (s == null) {
			throw new IllegalStateException("No world loaded");
		}
		CompletableFuture<T> future = new CompletableFuture<>();
		s.submit(() -> {
			try {
				future.complete(task.call());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		try {
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new IllegalStateException("Timed out waiting for the main thread (" + timeoutMs + " ms)");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for the main thread");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException re) {
				throw re;
			}
			throw new RuntimeException(cause);
		}
	}

	public static <T> T call(Callable<T> task) {
		return call(task, 30_000L);
	}
}
