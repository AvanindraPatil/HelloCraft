package dev.hnmc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Hello Neighbor's walls and floors inside Minecraft (step 3b: Minecraft moves the player, so it must collide with
 * them). HnLink scans the space around the player at quarter-block resolution and sends one 64-bit occupancy mask per
 * cell; each non-empty cell holds an invisible, unbreakable `hnmc:proxy` block whose collision AND outline shape come
 * from that mask (so the crosshair also hits Hello Neighbor geometry exactly, and right-click places against it).
 *
 * Shapes live in a static map shared by the integrated server and the client (same JVM). Proxies are placed with
 * client updates but no neighbour updates, and BlockWatch ignores them (never sent to Hello Neighbor, never meshed).
 */
public final class HnProxy {
	public static final ResourceKey<Block> KEY = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(HnMc.MOD_ID, "proxy"));
	public static Block BLOCK;

	/** Client updates, no neighbour/shape updates, no onPlace side effects. */
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

	private static final Map<BlockPos, Long> MASKS = new ConcurrentHashMap<>();
	private static final Map<Long, VoxelShape> SHAPES = new ConcurrentHashMap<>();

	private HnProxy() {}

	public static void register() {
		BLOCK = Blocks.register(KEY, ProxyBlock::new, BlockBehaviour.Properties.of()
			.strength(-1.0f, 3_600_000.0f)
			.noLootTable()
			.noOcclusion()
			.dynamicShape()
			// Solid: Minecraft decides that from the shape at BlockPos.ZERO, which for a proxy is empty, so water and lava
			// flowed INTO Hello Neighbor's floors (replacing them: the floor was gone in Minecraft) and fell into the void.
			.forceSolidOn()
			.isSuffocating((s, l, p) -> false)
			.isViewBlocking((s, l, p, a) -> false));
	}

	public static boolean isProxy(BlockState state) { return BLOCK != null && state.getBlock() == BLOCK; }

	public static int count() { return MASKS.size(); }

	/** The Hello Neighbor shape known for this cell (0 = none). */
	public static long maskAt(BlockPos pos) { Long m = MASKS.get(pos); return m == null ? 0L : m; }

	/** Server thread: put the proxy block back in a cell that has Hello Neighbor geometry (a Minecraft block was removed from it). */
	public static void restore(ServerLevel level, BlockPos pos) {
		if (maskAt(pos) != 0 && level.getBlockState(pos).isAir()) quietSet(level, pos, BLOCK.defaultBlockState());
	}

	/** Server thread: set what Hello Neighbor has in this cell (mask 0 = nothing). Never overwrites a real block. */
	public static void setCell(ServerLevel level, BlockPos pos, long mask) {
		BlockState cur = level.getBlockState(pos);
		boolean proxyThere = isProxy(cur);
		if (mask == 0) {
			MASKS.remove(pos);
			if (proxyThere) quietSet(level, pos, Blocks.AIR.defaultBlockState());
			return;
		}
		// A Minecraft block lives here: it wins. Except water or lava that flowed in before this floor was scanned:
		// Hello Neighbor's geometry replaces it (or the floor would never appear there).
		if (!proxyThere && !cur.isAir() && !(cur.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) return;
		MASKS.put(pos.immutable(), mask);
		Integer low = minY;
		if (low == null || pos.getY() < low) minY = pos.getY();
		if (!proxyThere) quietSet(level, pos, BLOCK.defaultBlockState());
	}

	/** Server thread: remove every proxy (map change). */
	public static void clear(ServerLevel level) {
		for (BlockPos pos : MASKS.keySet().toArray(new BlockPos[0])) {
			if (isProxy(level.getBlockState(pos))) quietSet(level, pos, Blocks.AIR.defaultBlockState());
		}
		MASKS.clear();
		minY = null;
	}

	/** The lowest cell with Hello Neighbor geometry so far in this level (null: none yet). Below it is the void. */
	private static volatile Integer minY;

	public static Integer minY() { return minY; }

	private static void quietSet(ServerLevel level, BlockPos pos, BlockState state) {
		SurfaceProxy.quiet = true;
		try {
			level.setBlock(pos, state, FLAGS);
		} finally {
			SurfaceProxy.quiet = false;
		}
	}

	static VoxelShape shapeAt(BlockPos pos) {
		Long mask = MASKS.get(pos);
		if (mask == null || mask == 0) return Shapes.empty();
		return SHAPES.computeIfAbsent(mask, HnProxy::buildShape);
	}

	/** Bit (sx + 4*sy + 16*sz) = quarter-block sub-cell. Runs along x are merged into one box each. */
	private static VoxelShape buildShape(long mask) {
		VoxelShape shape = Shapes.empty();
		for (int sz = 0; sz < 4; sz++) {
			for (int sy = 0; sy < 4; sy++) {
				int sx = 0;
				while (sx < 4) {
					if ((mask >>> (sx + 4 * sy + 16 * sz) & 1L) == 0) { sx++; continue; }
					int start = sx;
					while (sx < 4 && (mask >>> (sx + 4 * sy + 16 * sz) & 1L) != 0) sx++;
					shape = Shapes.or(shape, Shapes.box(start / 4.0, sy / 4.0, sz / 4.0, sx / 4.0, (sy + 1) / 4.0, (sz + 1) / 4.0));
				}
			}
		}
		return shape.optimize();
	}

	/** The proxy block: invisible, unbreakable, shaped like the Hello Neighbor geometry in its cell. */
	public static final class ProxyBlock extends Block {
		public ProxyBlock(BlockBehaviour.Properties properties) {
			super(properties);
		}

		@Override
		protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

		@Override
		protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return shapeAt(pos); }

		@Override
		protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return shapeAt(pos); }

		@Override
		protected VoxelShape getOcclusionShape(BlockState state) { return Shapes.empty(); }

		/** Mobs' path finding: Hello Neighbor geometry is solid (the default asks the shape at BlockPos.ZERO, which for
		 * a proxy is empty, so every floor and wall looked like air: no mob could path anywhere, HnMobs). */
		@Override
		protected boolean isPathfindable(BlockState state, net.minecraft.world.level.pathfinder.PathComputationType type) {
			return false;
		}

		/** Solid ground for rails, torches, carpets...: a Hello Neighbor floor whose top quarter is (nearly) full. The
		 * quarter-block scan can leave a sub-cell or two empty at seams, which must not make the floor "not sturdy". */
		@Override
		protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
			Long m = MASKS.get(pos);
			if (m != null) {
				int top = 0;
				for (int sz = 0; sz < 4; sz++) for (int sx = 0; sx < 4; sx++) if ((m >>> (sx + 12 + 16 * sz) & 1L) != 0) top++;
				if (top >= 12) return Shapes.block();
			}
			return shapeAt(pos);
		}

		/** No fluid ever replaces Hello Neighbor geometry (see forceSolidOn above). */
		@Override
		protected boolean canBeReplaced(BlockState state, net.minecraft.world.level.material.Fluid fluid) { return false; }

		@Override
		protected boolean propagatesSkylightDown(BlockState state) { return true; }
	}
}
