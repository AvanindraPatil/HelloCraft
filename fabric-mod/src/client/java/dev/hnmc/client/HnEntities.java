package dev.hnmc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hnmc.HnMc;
import dev.hnmc.HnProxy;
import dev.hnmc.client.mixin.RenderSetupAccessor;
import dev.hnmc.client.mixin.RenderTypeAccessor;
import dev.hnmc.client.mixin.TextureBindingAccessor;
import dev.hnmc.link.WorldLink;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's own entity rendering, captured as textured triangles for Hello Neighbor (world channel v2): the player
 * model in third person (F5) with skin, armour, held items, elytra and animations; mobs, dropped and thrown items,
 * arrows, boats, ...; block entities (chests, beds, signs, banners, ...); and particles. The renderers submit into
 * this collector instead of the GPU; hn_gfx draws the result in the 3D scene, depth-tested like the blocks.
 *
 * Adapted from SkyCraft's AvatarExporter (https://github.com/chasmlol/SkyCraft, MIT License, Copyright (c) 2026
 * chasmlol). Differences: one capture per frame, each batch binds its own texture (no combined atlas), no lighting.
 * Render thread only.
 */
final class HnEntities implements SubmitNodeCollector {
	private static final Direction[] FACES_AND_NONE = { null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
	private static final double RANGE = 48.0;
	private static final int MAX_ENTITIES = 64, MAX_BLOCK_ENTITIES = 256;

	private static final HnEntities INSTANCE = new HnEntities();
	private static WorldLink world;
	private static boolean warnedEntity, warnedBlockEntity, warnedParticles;
	private static long published;
	private static @Nullable ModelBlockRenderer movingBlocks;

	private final Map<Long, Batch> batches = new HashMap<>();
	private final Capture capture = new Capture();
	// Added to every position (particles come relative to the camera).
	private float offX, offY, offZ;

	private HnEntities() {}

	static void init(WorldLink link) { world = link; }

	/** Render thread, after GameRenderer.render(): capture and publish this frame's entities. */
	static void frame(Minecraft mc) {
		if (world == null) return;
		HnTextures.pump();
		try {
			INSTANCE.capture(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		} catch (RuntimeException e) {
			if (!warnedEntity) {
				warnedEntity = true;
				HnMc.LOGGER.warn("Entity capture failed", e);
			}
		}
	}

	private void capture(Minecraft mc, float partialTick) {
		ClientLevel level = mc.level;
		var player = mc.player;
		if (level == null || player == null) return;
		int slot = world.pickEntitySlot();
		if (slot == WorldLink.NONE) return;   // Hello Neighbor still reading both: skip a frame

		Camera camera = mc.gameRenderer.mainCamera();
		Vec3 cam = camera.position();
		double ox = Math.floor(player.getX()), oy = Math.floor(player.getY()), oz = Math.floor(player.getZ());
		for (Batch b : batches.values()) b.count = 0;

		var dispatcher = mc.getEntityRenderDispatcher();
		dispatcher.prepare(camera, mc.crosshairPickEntity);
		CameraRenderState cameraState = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		PoseStack pose = new PoseStack();
		int entities = 0;
		HnHeld.beginCapture(ox, oy, oz);   // a held Hello Neighbor item in Steve's hand: its pose, relative to this origin
		for (Entity e : level.entitiesForRendering()) {
			// The player only in third person: in first person Minecraft's hand (the overlay) stands for it.
			if (e == player && !camera.isDetached()) continue;
			// The neighbour's invisible stand-in for mobs (HnMobs: a frozen villager at his feet) is never drawn.
			if (e.getId() == dev.hnmc.HnMobs.bodyId || (e instanceof net.minecraft.world.entity.npc.villager.Villager && e.isInvisible())) continue;
			if (e.distanceToSqr(cam) > RANGE * RANGE || entities >= MAX_ENTITIES) continue;
			entities++;
			try {
				EntityRenderState state = dispatcher.extractEntity(e, partialTick);
				dispatcher.submit(state, cameraState, state.x - ox, state.y - oy, state.z - oz, pose, this);
			} catch (RuntimeException ex) {
				if (!warnedEntity) {
					warnedEntity = true;
					HnMc.LOGGER.warn("Couldn't capture {} for Hello Neighbor", e, ex);
				}
			}
		}
		HnHeld.endCapture();
		submitBlockEntities(mc, level, cam, ox, oy, oz, partialTick, cameraState, pose);
		capture.flush();
		// Particles were extracted this frame relative to Minecraft's camera.
		offX = (float) (cam.x - ox);
		offY = (float) (cam.y - oy);
		offZ = (float) (cam.z - oz);
		try {
			for (var group : mc.gameRenderer.gameRenderState().levelRenderState.particlesRenderState.particles) group.submit(this, cameraState);
		} catch (RuntimeException ex) {
			if (!warnedParticles) {
				warnedParticles = true;
				HnMc.LOGGER.warn("Couldn't capture particles for Hello Neighbor", ex);
			}
		}
		capture.flush();
		offX = offY = offZ = 0f;
		try {
			submitTargetDecals(mc, level, ox, oy, oz);
		} catch (RuntimeException ex) {
			if (!warnedDecals) {
				warnedDecals = true;
				HnMc.LOGGER.warn("Couldn't capture the block outline/crack for Hello Neighbor", ex);
			}
		}
		addFluids(ox, oy, oz);
		write(slot, ox, oy, oz);
	}

	/** Water and lava (built by HnFluids when blocks change), block-atlas textured. */
	private void addFluids(double ox, double oy, double oz) {
		for (HnFluids.Layer layer : HnFluids.layers()) {
			if (layer.vertices() == 0) continue;
			Batch b = batch(0, layer.translucent());
			float[] d = layer.data();
			for (int i = 0; i < layer.vertices(); i++) {
				int o = i * 6;
				b.add((float) (d[o] - ox), (float) (d[o + 1] - oy), (float) (d[o + 2] - oz), d[o + 3], d[o + 4],
					Float.floatToRawIntBits(d[o + 5]), OverlayTexture.NO_OVERLAY);
			}
		}
	}

	// ---- the targeted block's outline and the mining crack ----
	// Minecraft draws both in LevelRenderer, not through entity renderers, so they are built here from the state it
	// extracted this frame (levelRenderState.blockOutlineRenderState / blockBreakingRenderStates).

	private static final double OUTLINE_PULL = 0.998;   // outline vertices pulled toward the eye: in front of the faces
	private static final float CRACK_PUSH = 0.002f;     // crack quads pushed off the block's faces (blocks)
	private static boolean warnedDecals;

	private void submitTargetDecals(Minecraft mc, ClientLevel level, double ox, double oy, double oz) {
		var gameState = mc.gameRenderer.gameRenderState();
		var levelState = gameState.levelRenderState;
		CameraRenderState camState = levelState.cameraRenderState;
		double ex = camState.pos.x - ox, ey = camState.pos.y - oy, ez = camState.pos.z - oz;

		// Mining crack: Minecraft's destroy_stage textures projected onto the block model, like its crumbling pass.
		List<BlockStateModelPart> parts = new java.util.ArrayList<>();
		var random = net.minecraft.util.RandomSource.create();
		for (var state : levelState.blockBreakingRenderStates) {
			var bs = state.blockState();
			if (bs.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) continue;   // proxies are invisible
			int stage = Math.max(0, Math.min(9, state.progress()));
			int tex = HnTextures.idFor(Identifier.withDefaultNamespace("textures/block/destroy_stage_" + stage + ".png"));
			if (tex < 0) continue;
			Batch b = batch(tex, true);
			var pos = state.blockPos();
			Vec3 shift = bs.getOffset(pos);
			float bx = (float) (pos.getX() - ox + shift.x), by = (float) (pos.getY() - oy + shift.y), bz = (float) (pos.getZ() - oz + shift.z);
			var model = mc.getModelManager().getBlockStateModelSet().get(bs);
			random.setSeed(bs.getSeed(pos));
			parts.clear();
			model.collectParts(random, parts);
			for (BlockStateModelPart part : parts) {
				for (Direction face : FACES_AND_NONE) {
					for (BakedQuad quad : part.getQuads(face)) {
						Direction d = quad.direction();
						float nx = d.getStepX() * CRACK_PUSH, ny = d.getStepY() * CRACK_PUSH, nz = d.getStepZ() * CRACK_PUSH;
						for (int k = 0; k < 4; k++) {
							var p = quad.position(k);
							b.add(bx + p.x() + nx, by + p.y() + ny, bz + p.z() + nz, crackU(d, p), crackV(d, p), -1, OverlayTexture.NO_OVERLAY);
						}
					}
				}
			}
		}

		// Outline: the edges of the targeted block's shape as thin camera-facing strips, as wide on screen as
		// Minecraft's own lines. Never for proxies (invisible Hello Neighbor geometry; Minecraft would outline it).
		var outline = levelState.blockOutlineRenderState;
		if (outline == null || HnProxy.isProxy(level.getBlockState(outline.pos()))) return;
		int white = HnTextures.white();
		if (white < 0) return;
		Batch b = batch(white, true);
		int color = outline.highContrast() ? -11010079 : net.minecraft.util.ARGB.black(102);
		// Half a line width in blocks per block of distance: px * 2 / (projection y scale * screen height) / 2.
		double halfPerDist = gameState.windowRenderState.appropriateLineWidth / (camState.projectionMatrix.m11() * Math.max(1, mc.getWindow().getHeight()));
		double px = outline.pos().getX() - ox, py = outline.pos().getY() - oy, pz = outline.pos().getZ() - oz;
		outline.shape().forAllEdges((x1, y1, z1, x2, y2, z2) ->
			addLine(b, px + x1, py + y1, pz + z1, px + x2, py + y2, pz + z2, ex, ey, ez, halfPerDist, color));
	}

	/** One line as a quad facing the eye (ex, ey, ez), extended by its half width at both ends so corners close. */
	private static void addLine(Batch b, double ax, double ay, double az, double bx, double by, double bz,
		double ex, double ey, double ez, double halfPerDist, int color) {
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-6) return;
		dx /= len; dy /= len; dz /= len;
		// Side vector: perpendicular to the line and to the view ray at its middle.
		double vx = (ax + bx) / 2 - ex, vy = (ay + by) / 2 - ey, vz = (az + bz) / 2 - ez;
		double sx = dy * vz - dz * vy, sy = dz * vx - dx * vz, sz = dx * vy - dy * vx;
		double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
		if (sl < 1e-9) return;   // looking straight along the line: it covers no area
		sx /= sl; sy /= sl; sz /= sl;
		double ha = halfPerDist * dist(ax, ay, az, ex, ey, ez), hb = halfPerDist * dist(bx, by, bz, ex, ey, ez);
		ax -= dx * ha; ay -= dy * ha; az -= dz * ha;
		bx += dx * hb; by += dy * hb; bz += dz * hb;
		lineVertex(b, ax - sx * ha, ay - sy * ha, az - sz * ha, ex, ey, ez, color);
		lineVertex(b, bx - sx * hb, by - sy * hb, bz - sz * hb, ex, ey, ez, color);
		lineVertex(b, bx + sx * hb, by + sy * hb, bz + sz * hb, ex, ey, ez, color);
		lineVertex(b, ax + sx * ha, ay + sy * ha, az + sz * ha, ex, ey, ez, color);
	}

	private static double dist(double x, double y, double z, double ex, double ey, double ez) {
		return Math.sqrt((x - ex) * (x - ex) + (y - ey) * (y - ey) + (z - ez) * (z - ez));
	}

	private static void lineVertex(Batch b, double x, double y, double z, double ex, double ey, double ez, int color) {
		b.add((float) (ex + (x - ex) * OUTLINE_PULL), (float) (ey + (y - ey) * OUTLINE_PULL), (float) (ez + (z - ez) * OUTLINE_PULL),
			0.5f, 0.5f, color, OverlayTexture.NO_OVERLAY);
	}

	/** The crack texture projected along the face's axis (one copy per block, like Minecraft's sheeted decal). */
	private static float crackU(Direction d, org.joml.Vector3fc p) {
		return switch (d) {
			case DOWN, UP, SOUTH -> p.x();
			case NORTH -> 1 - p.x();
			case WEST -> p.z();
			case EAST -> 1 - p.z();
		};
	}

	private static float crackV(Direction d, org.joml.Vector3fc p) {
		return switch (d) {
			case DOWN -> 1 - p.z();
			case UP -> p.z();
			default -> 1 - p.y();
		};
	}

	/** Chests, beds, signs, banners, shulker boxes, heads, ...: drawn by their own renderers, not as block models. */
	private void submitBlockEntities(Minecraft mc, ClientLevel level, Vec3 cam, double ox, double oy, double oz, float partialTick,
		CameraRenderState cameraState, PoseStack pose) {
		var dispatcher = mc.getBlockEntityRenderDispatcher();
		dispatcher.prepare(cam);
		double range2 = RANGE * RANGE;
		int count = 0;
		int cx0 = (int) Math.floor((cam.x - RANGE) / 16.0), cx1 = (int) Math.floor((cam.x + RANGE) / 16.0);
		int cz0 = (int) Math.floor((cam.z - RANGE) / 16.0), cz1 = (int) Math.floor((cam.z + RANGE) / 16.0);
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				var chunk = level.getChunkSource().getChunk(cx, cz, false);
				if (chunk == null) continue;
				for (var blockEntity : chunk.getBlockEntities().values()) {
					var pos = blockEntity.getBlockPos();
					if (blockEntity.isRemoved() || pos.distToCenterSqr(cam) > range2 || count >= MAX_BLOCK_ENTITIES) continue;
					try {
						var state = dispatcher.tryExtractRenderState(blockEntity, partialTick, null, false);
						if (state == null) state = dispatcher.tryExtractRenderState(blockEntity, partialTick, null, true);
						if (state == null) continue;
						count++;
						pose.pushPose();
						pose.translate(pos.getX() - ox, pos.getY() - oy, pos.getZ() - oz);
						dispatcher.submit(state, pose, this, cameraState);
						pose.popPose();
					} catch (RuntimeException ex) {
						if (!warnedBlockEntity) {
							warnedBlockEntity = true;
							HnMc.LOGGER.warn("Couldn't capture {} at {} for Hello Neighbor", blockEntity.getType(), pos, ex);
						}
					}
				}
			}
		}
	}

	/** Batches -> the shared slot (triangles), then publish. */
	private void write(int slot, double ox, double oy, double oz) {
		MemorySegment seg = world.segment();
		long batchOff = WorldLink.entBatchOffset(slot), vertOff = WorldLink.entVertexOffset(slot);
		int nb = 0, nv = 0;
		for (Batch b : batches.values()) {
			int quads = b.count / 4;
			if (quads == 0) continue;
			int verts = Math.min(quads * 6, WorldLink.ENT_MAX_VERTS - nv);
			verts -= verts % 6;
			if (verts == 0 || nb >= WorldLink.MAX_BATCHES) break;
			long e = batchOff + (long) nb * WorldLink.BATCH_BYTES;
			seg.set(ValueLayout.JAVA_INT, e, b.texture);
			seg.set(ValueLayout.JAVA_INT, e + 4, nv);
			seg.set(ValueLayout.JAVA_INT, e + 8, verts);
			seg.set(ValueLayout.JAVA_INT, e + 12, b.translucent ? 1 : 0);
			long o = vertOff + (long) nv * WorldLink.VERTEX_BYTES;
			for (int q = 0; q < verts / 6; q++) {
				for (int k : TRI) {
					int i = (q * 4 + k) * 6;
					seg.set(ValueLayout.JAVA_FLOAT, o, Float.intBitsToFloat(b.data[i]));
					seg.set(ValueLayout.JAVA_FLOAT, o + 4, Float.intBitsToFloat(b.data[i + 1]));
					seg.set(ValueLayout.JAVA_FLOAT, o + 8, Float.intBitsToFloat(b.data[i + 2]));
					seg.set(ValueLayout.JAVA_FLOAT, o + 12, Float.intBitsToFloat(b.data[i + 3]));
					seg.set(ValueLayout.JAVA_FLOAT, o + 16, Float.intBitsToFloat(b.data[i + 4]));
					seg.set(ValueLayout.JAVA_INT, o + 20, b.data[i + 5]);
					o += WorldLink.VERTEX_BYTES;
				}
			}
			nb++;
			nv += verts;
		}
		world.publishEntities(slot, nb, nv, ox, oy, oz);
		if (++published == 1 || published % 3000 == 0) HnMc.LOGGER.info("Entities for Hello Neighbor: {} batch(es), {} triangles (frame {})", nb, nv / 3, published);
	}

	private static final int[] TRI = { 0, 1, 2, 0, 2, 3 };

	// ---- batches ----

	private Batch batch(int texture, boolean translucent) {
		long key = ((long) texture << 1) | (translucent ? 1 : 0);
		return batches.computeIfAbsent(key, k -> new Batch(texture, translucent));
	}

	/** Triangles for one texture; vertices arrive as quads: x, y, z, u, v, rgba (r low byte). */
	private final class Batch {
		final int texture;
		final boolean translucent;
		int[] data = new int[6 * 256];
		int count;

		Batch(int texture, boolean translucent) {
			this.texture = texture;
			this.translucent = translucent;
		}

		void add(float x, float y, float z, float u, float v, int argb, int overlay) {
			if ((count + 1) * 6 > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
			// Minecraft's red "hurt" flash is an overlay texture: tint instead.
			if (((overlay >>> 16) & 0xFFFF) < 8) {
				int r = (argb >> 16) & 0xFF, g = (int) (((argb >> 8) & 0xFF) * 0.55f), b = (int) ((argb & 0xFF) * 0.55f);
				argb = (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
			}
			int o = count * 6;
			data[o] = Float.floatToRawIntBits(x + offX);
			data[o + 1] = Float.floatToRawIntBits(y + offY);
			data[o + 2] = Float.floatToRawIntBits(z + offZ);
			data[o + 3] = Float.floatToRawIntBits(u);
			data[o + 4] = Float.floatToRawIntBits(v);
			data[o + 5] = ((argb >> 16) & 0xFF) | (((argb >> 8) & 0xFF) << 8) | ((argb & 0xFF) << 16) | ((argb >>> 24) << 24);
			count++;
		}
	}

	/** A VertexConsumer that records into the current batch (models call addVertex + setters). */
	private final class Capture implements VertexConsumer {
		private Batch batch;
		private boolean pending;
		private float x, y, z, u, v;
		private int color, overlay;

		void begin(Batch b) {
			flush();
			batch = b;
		}

		void flush() {
			if (pending && batch != null) batch.add(x, y, z, u, v, color, overlay);
			pending = false;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			flush();
			this.x = x;
			this.y = y;
			this.z = z;
			color = -1;
			overlay = OverlayTexture.NO_OVERLAY;
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
		public VertexConsumer setUv1(int u, int v) {
			overlay = (u & 0xFFFF) | (v << 16);
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) { return this; }

		@Override
		public VertexConsumer setUv3(float u, float v) { return this; }

		@Override
		public VertexConsumer setNormal(float x, float y, float z) { return this; }

		@Override
		public VertexConsumer setLineWidth(float width) { return this; }
	}

	/** The batch for a render type's texture. Null: Hello Neighbor can't show it (yet). */
	private @Nullable Batch batchFor(RenderType renderType) {
		RenderTypeAccessor type = (RenderTypeAccessor) renderType;
		String name = type.hnmc$name();
		if (name.contains("glint") || name.contains("outline") || name.contains("shadow")) return null;
		Object binding = ((RenderSetupAccessor) (Object) type.hnmc$state()).hnmc$textures().get("Sampler0");
		if (binding == null) return null;
		int id = HnTextures.idFor(((TextureBindingAccessor) binding).hnmc$location());
		return id < 0 ? null : batch(id, name.contains("translucent"));
	}

	private @Nullable Batch batchForAtlas(Identifier atlas) {
		int id = HnTextures.idFor(atlas);
		return id < 0 ? null : batch(id, false);
	}

	private void addQuad(Matrix4f pose, BakedQuad quad, int[] tintLayers, int overlayCoords) {
		var material = quad.materialInfo();
		Batch b = batchForAtlas(material.sprite().atlasLocation());
		if (b == null) return;
		int layer = material.tintIndex();
		int color = layer >= 0 && layer < tintLayers.length ? tintLayers[layer] : -1;
		Vector3f p = new Vector3f();
		for (int k = 0; k < 4; k++) {
			pose.transformPosition(quad.position(k), p);
			long uv = quad.packedUV(k);
			b.add(p.x(), p.y(), p.z(), UVPair.unpackU(uv), UVPair.unpackV(uv), color, overlayCoords);
		}
	}

	// ---- SubmitNodeCollector: models, items, blocks and particles are kept, the rest skipped ----

	@Override
	public OrderedSubmitNodeCollector order(int order) { return this; }

	@Override
	public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords, int overlayCoords,
		int tintedColor, @Nullable UvMapping uvMapping, int outlineColor) {
		Batch b = batchFor(renderType);
		if (b == null) return;
		capture.begin(b);
		VertexConsumer buffer = uvMapping != null ? uvMapping.wrap(capture) : capture;
		model.setupAnim(state);
		model.renderToBuffer(poseStack, buffer, lightCoords, overlayCoords, tintedColor);
		capture.flush();
	}

	@Override
	public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords, int outlineColor, int[] tintLayers,
		ItemQuads quads, ItemStackRenderState.FoilType foilType) {
		capture.flush();
		Matrix4f pose = poseStack.last().pose();
		for (BakedQuad quad : quads.all()) addQuad(pose, quad, tintLayers, overlayCoords);
	}

	/** Blocks drawn as entities: lit TNT, TNT minecarts, blocks carried by endermen, ... */
	@Override
	public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts, int[] tintLayers, int lightCoords, int overlayCoords,
		int outlineColor) {
		capture.flush();
		Matrix4f pose = poseStack.last().pose();
		for (BlockStateModelPart part : parts) {
			for (Direction face : FACES_AND_NONE) {
				for (BakedQuad quad : part.getQuads(face)) addQuad(pose, quad, tintLayers, overlayCoords);
			}
		}
	}

	// Fabric's renderer API routes block models and items through its own variants (which also carry a Fabric mesh).

	@Override
	public void submitBlockModel(PoseStack poseStack, java.util.function.Function<ChunkSectionLayer, RenderType> renderTypes, boolean translucentLayer,
		List<BlockStateModelPart> parts, net.fabricmc.fabric.api.client.renderer.v1.mesh.@Nullable Mesh mesh, int[] tintLayers, int lightCoords, int overlayCoords,
		int outlineColor) {
		submitBlockModel(poseStack, (RenderType) null, parts, tintLayers, lightCoords, overlayCoords, outlineColor);
		addMesh(poseStack.last().pose(), mesh, overlayCoords);
	}

	@Override
	public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords, int outlineColor, int[] tintLayers,
		ItemQuads quads, @Nullable MeshView mesh, ItemStackRenderState.FoilType foilType) {
		submitItem(poseStack, displayContext, lightCoords, overlayCoords, outlineColor, tintLayers, quads, foilType);
		addMesh(poseStack.last().pose(), mesh, overlayCoords);
	}

	@Override
	public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, net.fabricmc.fabric.api.client.renderer.v1.mesh.@Nullable Mesh mesh,
		int progress, boolean isBlockTranslucent) {
	}

	/** A Fabric mesh's quads (atlas UVs, per-vertex colour). */
	private void addMesh(Matrix4f pose, @Nullable MeshView mesh, int overlayCoords) {
		if (mesh == null || mesh.size() == 0) return;
		Vector3f p = new Vector3f();
		mesh.forEach(quad -> {
			Batch b = batchForAtlas(quad.atlas() == QuadAtlas.ITEM ? TextureAtlas.LOCATION_ITEMS : TextureAtlas.LOCATION_BLOCKS);
			if (b == null) return;
			for (int k = 0; k < 4; k++) {
				pose.transformPosition(quad.x(k), quad.y(k), quad.z(k), p);
				b.add(p.x(), p.y(), p.z(), quad.u(k), quad.v(k), quad.color(k), overlayCoords);
			}
		});
	}

	/** Falling sand, gravel, anvils, concrete powder: Minecraft's block renderer, posed. */
	@Override
	public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state, int outlineColor) {
		capture.flush();
		Minecraft mc = Minecraft.getInstance();
		if (movingBlocks == null) movingBlocks = new ModelBlockRenderer(false, true, mc.getBlockColors());
		var model = mc.getModelManager().getBlockStateModelSet().get(state.blockState);
		Matrix4f pose = new Matrix4f(poseStack.last().pose());
		Vector3f p = new Vector3f();
		movingBlocks.tesselateBlock((float x, float y, float z, BakedQuad quad, QuadInstance instance) -> {
			Batch b = batchForAtlas(quad.materialInfo().sprite().atlasLocation());
			if (b == null) return;
			for (int k = 0; k < 4; k++) {
				var q = quad.position(k);
				pose.transformPosition(q.x() + x, q.y() + y, q.z() + z, p);
				long uv = quad.packedUV(k);
				b.add(p.x(), p.y(), p.z(), UVPair.unpackU(uv), UVPair.unpackV(uv), instance.getColor(k), OverlayTexture.NO_OVERLAY);
			}
		}, 0.0F, 0.0F, 0.0F, state, state.blockPos, state.blockState, model, state.blockState.getSeed(state.randomSeedPos));
	}

	/** Particles: smoke, explosions, block debris, crits, ... in their atlas. */
	@Override
	public void submitQuadParticleGroup(QuadParticleRenderState particles) {
		capture.flush();
		for (var layer : particles.layers()) {
			int id = HnTextures.idFor(layer.textureAtlasLocation());
			if (id < 0) continue;
			capture.begin(batch(id, layer.translucent()));
			particles.buildLayer(layer, capture);
			capture.flush();
		}
	}

	@Override
	public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {}

	@Override
	public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name, boolean seeThrough, int lightCoords,
		CameraRenderState camera) {}

	@Override
	public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow, Font.DisplayMode displayMode, int lightCoords,
		int color, int backgroundColor, int outlineColor) {}

	@Override
	public void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int color, Font.DisplayMode displayMode, int lightCoords) {}

	@Override
	public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {}

	@Override
	public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {}

	@Override
	public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords, int overlayCoords,
		int tintedColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {}

	@Override
	public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress, boolean isBlockTranslucent) {}

	@Override
	public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float width, boolean afterTerrain) {}

	@Override
	public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {}

	@Override
	public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {}
}
