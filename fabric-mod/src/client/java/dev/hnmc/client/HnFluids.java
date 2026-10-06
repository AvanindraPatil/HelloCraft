package dev.hnmc.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Water and lava for Hello Neighbor. Minecraft draws fluids with its FluidRenderer, not from block models, so the
 * world mesh (HnWorldMesh) has none. This runs Minecraft's own FluidRenderer over the world's fluid cells (flowing
 * surfaces, slopes, biome water colour, waterlogged blocks) into a recorder, and HnEntities sends the result each
 * frame as block-atlas batches: water in the translucent pass, lava with the solid ones.
 *
 * Rebuilt together with the world mesh (on block changes, which include fluid spreading). Render thread only.
 */
final class HnFluids {
	/** Quads: per vertex x, y, z (world), u, v, ARGB (as float bits). */
	record Layer(boolean translucent, float[] data, int vertices) {}

	private static List<Layer> layers = List.of();
	private static FluidRenderer renderer;
	private static FluidStateModelSet rendererModels;

	private HnFluids() {}

	static List<Layer> layers() { return layers; }

	/** Tesselate every fluid among these cells. Returns how many fluid cells there were. */
	static int rebuild(Minecraft mc, ClientLevel level, Iterable<BlockPos> cells) {
		FluidStateModelSet models = mc.getModelManager().getFluidStateModelSet();
		if (renderer == null || rendererModels != models) {   // new after a resource reload
			renderer = new FluidRenderer(models);
			rendererModels = models;
		}
		Recorder solid = new Recorder(), translucent = new Recorder();
		int count = 0;
		for (BlockPos pos : cells) {
			BlockState state = level.getBlockState(pos);
			FluidState fluid = state.getFluidState();
			if (fluid.isEmpty()) continue;
			count++;
			// FluidRenderer writes section-relative positions (pos & 15): add the section origin back.
			float sx = pos.getX() & ~15, sy = pos.getY() & ~15, sz = pos.getZ() & ~15;
			solid.origin(sx, sy, sz);
			translucent.origin(sx, sy, sz);
			renderer.tesselate(level, pos, layer -> layer == ChunkSectionLayer.TRANSLUCENT ? translucent : solid, state, fluid);
		}
		solid.flush();
		translucent.flush();
		layers = List.of(new Layer(false, solid.data, solid.count), new Layer(true, translucent.data, translucent.count));
		return count;
	}

	/** Records FluidRenderer's vertices (it always uses the all-in-one addVertex). */
	private static final class Recorder implements VertexConsumer {
		float[] data = new float[6 * 64];
		int count;
		private float ox, oy, oz;
		// Fallback path (addVertex + setters), in case a mod's fluid renderer uses it.
		private float x, y, z, u, v;
		private int color = -1;
		private boolean pending;

		void origin(float x, float y, float z) {
			flush();
			ox = x;
			oy = y;
			oz = z;
		}

		private void put(float x, float y, float z, float u, float v, int argb) {
			if ((count + 1) * 6 > data.length) data = Arrays.copyOf(data, data.length * 2);
			int o = count * 6;
			data[o] = ox + x;
			data[o + 1] = oy + y;
			data[o + 2] = oz + z;
			data[o + 3] = u;
			data[o + 4] = v;
			data[o + 5] = Float.intBitsToFloat(argb);
			count++;
		}

		private void flush() {
			if (pending) put(x, y, z, u, v, color);
			pending = false;
		}

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny, float nz) {
			flush();
			put(x, y, z, u, v, color);
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			flush();
			this.x = x;
			this.y = y;
			this.z = z;
			color = -1;
			pending = true;
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			color = (a << 24) | (r << 16) | (g << 8) | b;
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			this.color = color;
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			this.u = u;
			this.v = v;
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) { return this; }

		@Override
		public VertexConsumer setUv2(int u, int v) { return this; }

		@Override
		public VertexConsumer setUv3(float u, float v) { return this; }

		@Override
		public VertexConsumer setNormal(float x, float y, float z) { return this; }

		@Override
		public VertexConsumer setLineWidth(float width) { return this; }
	}
}
