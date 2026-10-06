package dev.hnmc.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.hnmc.HnMc;
import dev.hnmc.link.WorldLink;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.data.AtlasIds;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;

/**
 * Step 4: Minecraft's blocks for Hello Neighbor's 3D scene. Builds a triangle mesh of every block in the (otherwise
 * empty) world from Minecraft's own baked block models, with block-atlas UVs, biome tint and Minecraft's face shading,
 * and publishes it with the block atlas through the world channel (WorldLink). hn_gfx draws it, depth-tested.
 *
 * Client/render thread only. Rebuilt whenever a block changes (cheap: the void world only holds what was placed).
 */
final class HnWorldMesh {
	private static final Direction[] FACES = { null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
	private static final int USAGE_MAP_READ_COPY_DST = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;

	private final WorldLink world;
	private final HnBlocks blocks;
	private final List<BlockStateModelPart> parts = new ArrayList<>();
	private final RandomSource random = RandomSource.create();
	private boolean dirty = true;
	private int againIn;              // rebuild once more a few ticks later: the client level lags the server
	private long builds;
	private int lastFluidCount;

	// Atlas readback
	private GpuTexture atlasExported;
	private GpuBuffer atlasBuffer;
	private volatile boolean atlasCopied;
	private int atlasW, atlasH;

	HnWorldMesh(WorldLink world, HnBlocks blocks) {
		this.world = world;
		this.blocks = blocks;
	}

	void markDirty() {
		dirty = true;
		againIn = 4;
	}

	void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null) return;
		exportAtlas(mc);
		if (dirty) {
			if (build(mc, level)) dirty = false;
		} else if (againIn > 0 && --againIn == 0) {
			build(mc, level);
		}
	}

	// ---- atlas ----

	private void exportAtlas(Minecraft mc) {
		if (atlasBuffer != null) {
			if (!atlasCopied) return;
			try (GpuBufferSlice.MappedView view = atlasBuffer.map(true, false)) {
				MemorySegment src = MemorySegment.ofBuffer(view.data());
				MemorySegment.copy(src, 0, world.segment(), WorldLink.ATLAS_OFF, Math.min((long) atlasW * atlasH * 4, src.byteSize()));
			}
			atlasBuffer.close();
			atlasBuffer = null;
			world.publishAtlas(atlasW, atlasH);
			HnMc.LOGGER.info("Block atlas {}x{} published for Hello Neighbor", atlasW, atlasH);
			return;
		}
		GpuTexture tex = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getTexture();
		if (tex == null || tex == atlasExported) return;
		atlasExported = tex;
		int w = tex.getWidth(0), h = tex.getHeight(0);
		if (w > WorldLink.ATLAS_MAX || h > WorldLink.ATLAS_MAX) {
			HnMc.LOGGER.error("Block atlas {}x{} is bigger than the world channel allows ({}); no textures", w, h, WorldLink.ATLAS_MAX);
			return;
		}
		atlasW = w;
		atlasH = h;
		atlasCopied = false;
		atlasBuffer = RenderSystem.getDevice().createBuffer(() -> "hnmc atlas readback", USAGE_MAP_READ_COPY_DST, (long) w * h * 4);
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(tex, atlasBuffer, 0L, () -> atlasCopied = true, 0);
		HnMc.LOGGER.info("Reading back the block atlas ({}x{}, {})", w, h, tex.getFormat());
		markDirty(); // UVs may have changed (resource reload)
	}

	// ---- mesh ----

	private boolean build(Minecraft mc, ClientLevel level) {
		int slot = world.pickMeshSlot();
		if (slot == WorldLink.NONE) return false;
		MemorySegment seg = world.segment();
		long base = WorldLink.slotOffset(slot);
		var models = mc.getModelManager().getBlockStateModelSet();
		int n = 0;
		boolean full = false;
		int blockCount = 0;
		outer:
		for (BlockPos pos : blocks.knownBlocks()) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;
			blockCount++;
			BlockStateModel model = models.get(state);
			parts.clear();
			random.setSeed(state.getSeed(pos));
			model.collectParts(random, parts);
			List<BlockTintSource> tints = null;
			for (BlockStateModelPart part : parts) {
				for (Direction face : FACES) {
					if (face != null && !Block.shouldRenderFace(state, level.getBlockState(pos.relative(face)), face)) continue;
					for (BakedQuad q : part.getQuads(face)) {
						if (n + 6 > WorldLink.MAX_VERTS) { full = true; break outer; }
						int argb = -1;
						int tintIndex = q.materialInfo().tintIndex();
						if (tintIndex != -1) {
							if (tints == null) tints = mc.getBlockColors().getTintSources(state);
							if (tintIndex < tints.size()) argb = tints.get(tintIndex).colorInWorld(state, level, pos);
						}
						Direction shadeDir = q.materialInfo().shadeDirectionOverride() != null ? q.materialInfo().shadeDirectionOverride() : q.direction();
						int color = pack(argb, shade(shadeDir));
						long o = base + (long) n * WorldLink.VERTEX_BYTES;
						o = vertex(seg, o, q, 0, pos, color);
						o = vertex(seg, o, q, 1, pos, color);
						o = vertex(seg, o, q, 2, pos, color);
						o = vertex(seg, o, q, 0, pos, color);
						o = vertex(seg, o, q, 2, pos, color);
						vertex(seg, o, q, 3, pos, color);
						n += 6;
					}
				}
			}
		}
		world.publishMesh(slot, n);
		int fluids = HnFluids.rebuild(mc, level, blocks.knownBlocks());   // sent with the entities (translucent pass)
		if (++builds <= 3 || builds % 50 == 0 || full || (fluids > 0) != (lastFluidCount > 0)) {
			HnMc.LOGGER.info("World mesh #{}: {} block(s), {} triangles, {} fluid cell(s){}", builds, blockCount, n / 3, fluids,
				full ? " (FULL: some blocks left out)" : "");
		}
		lastFluidCount = fluids;
		return true;
	}

	private static long vertex(MemorySegment seg, long o, BakedQuad q, int i, BlockPos pos, int color) {
		Vector3fc p = q.position(i);
		long uv = q.packedUV(i);
		seg.set(ValueLayout.JAVA_FLOAT, o, pos.getX() + p.x());
		seg.set(ValueLayout.JAVA_FLOAT, o + 4, pos.getY() + p.y());
		seg.set(ValueLayout.JAVA_FLOAT, o + 8, pos.getZ() + p.z());
		seg.set(ValueLayout.JAVA_FLOAT, o + 12, UVPair.unpackU(uv));
		seg.set(ValueLayout.JAVA_FLOAT, o + 16, UVPair.unpackV(uv));
		seg.set(ValueLayout.JAVA_INT, o + 20, color);
		return o + WorldLink.VERTEX_BYTES;
	}

	/** Minecraft's fixed directional face shading (no lightmap: Hello Neighbor's scene is lit its own way). */
	private static float shade(Direction d) {
		return switch (d) {
			case DOWN -> 0.5f;
			case UP -> 1.0f;
			case NORTH, SOUTH -> 0.8f;
			case WEST, EAST -> 0.6f;
		};
	}

	/** ARGB tint * shade -> RGBA8 with r in the low byte. */
	private static int pack(int argb, float shade) {
		int r = (int) (((argb >> 16) & 255) * shade), g = (int) (((argb >> 8) & 255) * shade), b = (int) ((argb & 255) * shade);
		return r | (g << 8) | (b << 16) | (255 << 24);
	}
}
