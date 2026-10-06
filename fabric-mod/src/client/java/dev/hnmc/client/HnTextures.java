package dev.hnmc.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.hnmc.HnMc;
import dev.hnmc.link.WorldLink;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/**
 * Textures for Hello Neighbor (world channel v2): any Minecraft texture an entity, item or particle uses gets an id;
 * its pixels are read back from the GPU once (asynchronously, like the overlay frames) into the shared texture heap,
 * then announced in the texture table. Id 0 is the block atlas, which travels separately. Render thread only.
 */
final class HnTextures {
	private static final int USAGE_MAP_READ_COPY_DST = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;

	private record Pending(Identifier location, int id, int width, int height, long heapOffset, GpuBuffer buffer, boolean[] done) {}

	private static WorldLink world;
	private static final Map<Identifier, Integer> IDS = new HashMap<>();
	private static final Set<Identifier> UNUSABLE = new HashSet<>();
	private static final Map<Identifier, Pending> PENDING = new HashMap<>();
	private static int nextId = 1;

	private HnTextures() {}

	static void init(WorldLink link) { world = link; }

	/** The texture's id, or -1 if it is not (yet) available to Hello Neighbor; a first call starts the readback. */
	static int idFor(Identifier location) {
		if (location.equals(TextureAtlas.LOCATION_BLOCKS)) return 0;
		Integer known = IDS.get(location);
		if (known != null) return known;
		if (world == null || UNUSABLE.contains(location) || PENDING.containsKey(location)) return -1;
		try {
			AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location);
			GpuTexture gpu = texture.getTexture();
			if (gpu == null || gpu.getFormat() != GpuFormat.RGBA8_UNORM) {
				UNUSABLE.add(location);
				HnMc.LOGGER.info("Texture {} is not RGBA8 ({}); what uses it is left out", location, gpu == null ? "none" : gpu.getFormat());
				return -1;
			}
			int w = gpu.getWidth(0), h = gpu.getHeight(0);
			long off = world.reserveTexture(w, h);
			if (off < 0) {
				UNUSABLE.add(location);
				HnMc.LOGGER.warn("No room for texture {} ({}x{}) in the shared texture heap", location, w, h);
				return -1;
			}
			GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "hnmc texture readback", USAGE_MAP_READ_COPY_DST, (long) w * h * 4);
			boolean[] done = { false };
			RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(gpu, buffer, 0L, () -> done[0] = true, 0);
			PENDING.put(location, new Pending(location, nextId++, w, h, off, buffer, done));
		} catch (RuntimeException e) {
			UNUSABLE.add(location);
			HnMc.LOGGER.warn("Could not read texture {} for Hello Neighbor", location, e);
		}
		return -1;
	}

	private static final Identifier WHITE = Identifier.fromNamespaceAndPath(HnMc.MOD_ID, "white");

	/** A 1x1 opaque white texture, for untextured geometry (the block outline); -1 if there is no room. */
	static int white() {
		Integer known = IDS.get(WHITE);
		if (known != null) return known;
		if (world == null || UNUSABLE.contains(WHITE)) return -1;
		long off = world.reserveTexture(1, 1);
		if (off < 0) {
			UNUSABLE.add(WHITE);
			return -1;
		}
		world.segment().set(ValueLayout.JAVA_INT, WorldLink.TEX_HEAP_OFF + off, -1);
		int id = nextId++;
		world.publishTexture(id, 1, 1, off);
		IDS.put(WHITE, id);
		return id;
	}

	/** Render thread, once a frame: finished readbacks go into the heap and the table. */
	static void pump() {
		if (PENDING.isEmpty()) return;
		List<Identifier> finished = new ArrayList<>();
		for (Pending p : PENDING.values()) {
			if (!p.done()[0]) continue;
			try (GpuBufferSlice.MappedView view = p.buffer().map(true, false)) {
				MemorySegment src = MemorySegment.ofBuffer(view.data());
				MemorySegment.copy(src, 0, world.segment(), WorldLink.TEX_HEAP_OFF + p.heapOffset(), Math.min((long) p.width() * p.height() * 4, src.byteSize()));
			}
			p.buffer().close();
			world.publishTexture(p.id(), p.width(), p.height(), p.heapOffset());
			IDS.put(p.location(), p.id());
			finished.add(p.location());
			HnMc.LOGGER.info("Texture {} ({}x{}) sent to Hello Neighbor as id {}", p.location(), p.width(), p.height(), p.id());
		}
		finished.forEach(PENDING::remove);
	}
}
