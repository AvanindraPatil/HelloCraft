package dev.hnmc;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Every block change in the server-side world (mining, placing, our own commands, water flow...) lands here,
 * so Hello Neighbor can mirror it. Filled on the server thread by LevelMixin, drained on the client thread.
 * The new state travels with the position, so the client never reads the server's world.
 */
public final class BlockWatch {
	public record Change(BlockPos pos, BlockState state) {}

	private static final Queue<Change> CHANGED = new ConcurrentLinkedQueue<>();
	private static final int MAX_PENDING = 100_000; // never let a runaway (e.g. a huge fill) eat memory

	/** Every cell a player or a fluid has put a block in (not Hello Neighbor's proxy blocks): what /hnreset takes away again. */
	public static final java.util.Set<Long> PLACED = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final int MAX_PLACED = 2_000_000;

	private BlockWatch() {}

	public static void changed(BlockPos pos, BlockState state) {
		if (state.isAir()) PLACED.remove(pos.asLong());
		else if (PLACED.size() < MAX_PLACED) PLACED.add(pos.asLong());
		if (CHANGED.size() < MAX_PENDING) CHANGED.add(new Change(pos.immutable(), state));
	}

	public static Change poll() { return CHANGED.poll(); }
}
