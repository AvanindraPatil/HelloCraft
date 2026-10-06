package dev.hnmc.client;

import dev.hnmc.HnMc;
import dev.hnmc.link.Layout;
import dev.hnmc.link.McLink;
import dev.hnmc.BlockWatch;
import dev.hnmc.HnProxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Minecraft is the source of truth for blocks. Host commands are applied to the real (integrated-server) world,
 * and what the world actually contains afterwards goes back to the host as BlockChange events.
 *
 * Threading: commands arrive on the client tick thread (from {@link McLink#poll()}), are applied on the server
 * thread, and the results come back through a queue that {@link #flush} drains on the client thread, because
 * only one thread may push to the event ring.
 */
final class HnBlocks implements McLink.BlockHandler {
	private static final Map<Integer, Block> PALETTE = Map.of(
		Layout.BLOCK_STONE, Blocks.STONE,
		Layout.BLOCK_PLANKS, Blocks.OAK_PLANKS,
		Layout.BLOCK_DIRT, Blocks.DIRT,
		Layout.BLOCK_COBBLE, Blocks.COBBLESTONE,
		Layout.BLOCK_BRICKS, Blocks.BRICKS,
		Layout.BLOCK_GLASS, Blocks.GLASS);

	private final McLink link;
	/** Results waiting to be sent: {type, x, y, z, blockId}. Filled on the server thread. */
	private final Queue<int[]> pending = new ConcurrentLinkedQueue<>();
	/** Non-air cells seen in the world (updated from BlockWatch on the client thread, read by resync on the server
	 * thread). The world is otherwise empty (void). */
	private final Set<BlockPos> known = ConcurrentHashMap.newKeySet();

	HnBlocks(McLink link) {
		this.link = link;
	}

	/** Every non-air cell Hello Neighbor has been told about (the whole content of the void world). */
	Set<BlockPos> knownBlocks() { return known; }

	/** What Hello Neighbor gets for this state: a collision cube type, or AIR for anything you can walk through
	 * (water, lava, torches, flowers, ...). Those would otherwise be solid invisible cubes there, blocking the
	 * neighbour and the surface trace. Minecraft still draws them (HnWorldMesh / HnFluids track every non-air cell). */
	static int idOf(BlockState state) {
		if (state.isAir() || state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) return Layout.BLOCK_AIR;
		for (Map.Entry<Integer, Block> e : PALETTE.entrySet()) {
			if (state.getBlock() == e.getValue()) return e.getKey();
		}
		return Layout.BLOCK_OTHER;
	}

	@Override
	public void breakBlock(int x, int y, int z) {
		onServer("break", level -> {
			BlockPos pos = new BlockPos(x, y, z);
			// A real change is reported by BlockWatch; if nothing changed (already air), still answer the host.
			if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)) report(level, pos);
		});
	}

	@Override
	public void placeBlock(int x, int y, int z, int blockId) {
		Block block = PALETTE.getOrDefault(blockId, Blocks.STONE);
		onServer("place", level -> {
			BlockPos pos = new BlockPos(x, y, z);
			if (!level.getBlockState(pos).isAir()) { report(level, pos); return; } // occupied: tell the host what is there
			if (!level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL)) report(level, pos);
		});
	}

	@Override
	public void resync() {
		onServer("resync", level -> {
			known.removeIf(pos -> level.getBlockState(pos).isAir());
			for (BlockPos pos : known) {
				pending.add(new int[] { Layout.EV_BLOCK_CHANGE, pos.getX(), pos.getY(), pos.getZ(), idOf(level.getBlockState(pos)) });
			}
			pending.add(new int[] { Layout.EV_RESYNC_DONE, known.size(), 0, 0, 0 });
			HnMc.LOGGER.info("Resync: sent {} block(s) to Hello Neighbor", known.size());
		});
	}

	// ---- Hello Neighbor geometry (proxy blocks), batched: HnLink sends hundreds of cells a second ----
	private record ProxyOp(int x, int y, int z, long mask, boolean clear) {}
	private final Queue<ProxyOp> proxyOps = new ConcurrentLinkedQueue<>();
	private final java.util.concurrent.atomic.AtomicBoolean proxyTaskQueued = new java.util.concurrent.atomic.AtomicBoolean();
	private long proxyCellsApplied;

	@Override
	public void proxyCell(int x, int y, int z, long mask) {
		proxyOps.add(new ProxyOp(x, y, z, mask, false));
		scheduleProxyOps();
	}

	@Override
	public void proxyClear() {
		proxyOps.add(new ProxyOp(0, 0, 0, 0, true));
		scheduleProxyOps();
	}

	private void scheduleProxyOps() {
		if (proxyOps.isEmpty() || !proxyTaskQueued.compareAndSet(false, true)) return;
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			// No world yet: keep the ops queued and let the next call (flush, every tick) try again. Leaving the
			// flag set here once blocked ALL geometry for a whole session (2026-10-02 19:50).
			proxyTaskQueued.set(false);
			return;
		}
		server.execute(() -> {
			proxyTaskQueued.set(false);
			ServerLevel level = server.overworld();
			ProxyOp op;
			while ((op = proxyOps.poll()) != null) {
				if (op.clear()) {
					HnProxy.clear(level);
					HnMc.LOGGER.info("Hello Neighbor geometry cleared");
				} else {
					HnProxy.setCell(level, new BlockPos(op.x(), op.y(), op.z()), op.mask());
					if (++proxyCellsApplied == 1 || proxyCellsApplied % 2000 == 0) {
						HnMc.LOGGER.info("Hello Neighbor geometry: {} cell updates applied, {} solid cells", proxyCellsApplied, HnProxy.count());
					}
				}
			}
		});
	}

	/** Server thread: queue what the cell holds now (used when a command changed nothing). */
	private void report(ServerLevel level, BlockPos pos) {
		int id = idOf(level.getBlockState(pos));
		pending.add(new int[] { Layout.EV_BLOCK_CHANGE, pos.getX(), pos.getY(), pos.getZ(), id });
	}

	private void onServer(String what, java.util.function.Consumer<ServerLevel> action) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			HnMc.LOGGER.warn("Dropped {} command: no Minecraft world yet", what);
			return;
		}
		server.execute(() -> {
			try {
				action.accept(server.overworld());
			} catch (Throwable t) {
				HnMc.LOGGER.error("Block {} failed", what, t);
			}
		});
	}

	/** Client thread: send real world changes (mining, placing, commands), then queued answers, to the host.
	 * Returns how many world changes were sent. */
	int flush() {
		scheduleProxyOps();   // geometry that arrived before the world existed
		BlockWatch.Change c;
		int n = 0;
		while ((c = BlockWatch.poll()) != null) {
			int id = idOf(c.state());
			if (c.state().isAir()) known.remove(c.pos()); else known.add(c.pos());
			if (!link.pushEvent(Layout.EV_BLOCK_CHANGE, c.pos().getX(), c.pos().getY(), c.pos().getZ(), id)) {
				HnMc.LOGGER.warn("Event ring full; dropped a block change (host will need a resync)");
			}
			if (++n >= 2000) break;   // the ring holds 4096; leave the rest for the next tick
		}
		int[] e;
		while ((e = pending.poll()) != null) {
			if (!link.pushEvent(e[0], e[1], e[2], e[3], e[4])) {
				HnMc.LOGGER.warn("Event ring full; dropped an event (host will need a resync)");
			}
		}
		return n;
	}
}
