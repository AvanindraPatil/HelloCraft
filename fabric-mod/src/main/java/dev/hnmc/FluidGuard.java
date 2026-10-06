package dev.hnmc;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Water and lava on Hello Neighbor's floors. Its geometry only exists in Minecraft where it has been scanned (around
 * the player), and water spreads 7 blocks: past the scanned area it fell into the void. So every new fluid block asks
 * for the cells under and around it to be scanned first (urgent, like a projectile's path), and fluid that still gets
 * below the lowest Hello Neighbor geometry of the level is removed instead of falling 60 blocks.
 * Server thread (LevelMixin).
 */
public final class FluidGuard {
	private static final long ASK_AGAIN_MS = 20_000;     // a cell asked for once is not asked for again this soon
	private static final int MARGIN_BELOW = 2;            // fluid may go this far below the lowest geometry
	private static final Map<Long, Long> asked = new HashMap<>();

	private FluidGuard() {}

	public static void changed(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.getFluidState().isEmpty()) return;
		Integer minY = HnProxy.minY();
		if (minY != null && pos.getY() < minY - MARGIN_BELOW && state.getBlock() instanceof LiquidBlock) {
			// Below everything Hello Neighbor has: the void. Removed on the next tick (not inside this setBlock).
			BlockPos p = pos.immutable();
			level.getServer().execute(() -> {
				if (level.getBlockState(p).getBlock() instanceof LiquidBlock) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
			});
			return;
		}
		long now = System.currentTimeMillis();
		if (asked.size() > 50_000) asked.clear();
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 0; dy++) {
			int x = pos.getX() + dx, y = pos.getY() + dy, z = pos.getZ() + dz;
			long k = BlockPos.asLong(x, y, z);
			Long t = asked.get(k);
			if (t != null && now - t < ASK_AGAIN_MS) continue;
			asked.put(k, now);
			HnImpacts.scanCellUrgent(x, y, z);
		}
	}
}
