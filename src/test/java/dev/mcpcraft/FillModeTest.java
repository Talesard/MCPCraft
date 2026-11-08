package dev.mcpcraft;

import dev.mcpcraft.tools.Schemas;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * Fill mode semantics. Exercises the pure functions without a live world:
 * {@code hollow} is the surface, {@code outline} is the 12 edges only.
 */
class FillModeTest {

	private static final BlockPos A = new BlockPos(0, 0, 0);
	private static final BlockPos B = new BlockPos(4, 4, 4); // 5x5x5 cube

	@Test
	void hollowOn5CubeIsSurfaceOnly() {
		int shell = 0;
		for (BlockPos p : Schemas.iterRegion(A, B)) {
			if (Schemas.isOnShell(A, B, p)) {
				shell++;
			}
		}
		// 5^3 - 3^3 = 125 - 27 = 98
		assert shell == 98 : "hollow should cover 98 blocks, got " + shell;
	}

	@Test
	void outlineOn5CubeIsTwelveEdges() {
		int edges = 0;
		for (BlockPos p : Schemas.iterRegion(A, B)) {
			if (Schemas.isOnEdge(A, B, p)) {
				edges++;
			}
		}
		// 12 edges of 5 blocks each, but the 8 corners are counted three times,
		// so the real count is 12*(5-2) + 8 = 44.
		assert edges == 44 : "outline should cover 44 blocks (12 edges), got " + edges;
	}

	@Test
	void hollowIsStrictSupersetOfOutline() {
		for (BlockPos p : Schemas.iterRegion(A, B)) {
			if (Schemas.isOnEdge(A, B, p)) {
				assert Schemas.isOnShell(A, B, p) : "an edge must be part of the surface";
			}
		}
	}

	@Test
	void centerIsInteriorForBothModes() {
		BlockPos center = new BlockPos(2, 2, 2);
		assert !Schemas.isOnShell(A, B, center) : "the center must not be in hollow";
		assert !Schemas.isOnEdge(A, B, center) : "the center must not be in outline";
	}

	@Test
	void faceCenterIsShellButNotEdge() {
		BlockPos faceCenter = new BlockPos(2, 0, 2); // center of the bottom face
		assert Schemas.isOnShell(A, B, faceCenter) : "a face belongs to hollow";
		assert !Schemas.isOnEdge(A, B, faceCenter) : "a face center is not an edge";
	}

	@Test
	void boundaryCountIsCorrect() {
		assert Schemas.boundaryCount(A, B, new BlockPos(2, 2, 2)) == 0;
		assert Schemas.boundaryCount(A, B, new BlockPos(0, 2, 2)) == 1;
		assert Schemas.boundaryCount(A, B, new BlockPos(0, 0, 2)) == 2;
		assert Schemas.boundaryCount(A, B, new BlockPos(0, 0, 0)) == 3;
	}

	@Test
	void volumeIsInclusive() {
		assert Schemas.volume(A, B) == 125;
		assert Schemas.volume(new BlockPos(0, 0, 0), new BlockPos(0, 0, 0)) == 1;
	}
}
