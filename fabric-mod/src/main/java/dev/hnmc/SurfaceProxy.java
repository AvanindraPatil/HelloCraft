package dev.hnmc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Lets the player click Hello Neighbor's own walls and floors, which do not exist in the (void) Minecraft world.
 *
 * The client swaps its "missed" crosshair hit for the Hello Neighbor surface the camera looks at (a cell that is air
 * in Minecraft) and registers that cell here. When the server then handles the click on that cell, it briefly puts a
 * barrier there, so vanilla placement treats it as a solid block: the new block goes against its face, torches and
 * ladders find support. Afterwards the barrier is removed WITHOUT neighbour updates, so attached blocks stay.
 *
 * The barrier exists only for the duration of one useItemOn call, so it can never collide with the player.
 * The client never sees it and Hello Neighbor is never told about it (BlockWatch is muted while we touch it).
 */
public final class SurfaceProxy {
	private static final long VALID_MS = 1500; // a click arrives a few ticks after the crosshair saw the cell
	/** No neighbour/shape updates, no client update, no onPlace side effects. */
	private static final int QUIET = Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_INVISIBLE | Block.UPDATE_SKIP_ON_PLACE;

	private static final Map<BlockPos, Long> CLICKABLE = new ConcurrentHashMap<>();
	/** Server thread: true while we place/remove a barrier, so BlockWatch ignores it. */
	public static boolean quiet;

	private SurfaceProxy() {}

	/** Client: the crosshair currently targets this Hello Neighbor surface cell. */
	public static void offer(BlockPos cell) {
		long now = System.currentTimeMillis();
		CLICKABLE.put(cell.immutable(), now);
		if (CLICKABLE.size() > 64) CLICKABLE.values().removeIf(t -> now - t > VALID_MS);
	}

	/** Server, before useItemOn: put a barrier in a recently offered empty cell. Returns true if it did. */
	public static boolean begin(Level level, BlockPos pos) {
		Long t = CLICKABLE.get(pos);
		if (t == null || System.currentTimeMillis() - t > VALID_MS || !level.getBlockState(pos).isAir()) return false;
		quiet = true;
		try {
			HnMc.LOGGER.info("Click on a Hello Neighbor surface at {}", pos.toShortString());
			return level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), QUIET);
		} finally {
			quiet = false;
		}
	}

	/** Server, after useItemOn: take the barrier away again. */
	public static void end(Level level, BlockPos pos) {
		if (level.getBlockState(pos).getBlock() != Blocks.BARRIER) return;
		quiet = true;
		try {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), QUIET);
		} finally {
			quiet = false;
		}
	}
}
