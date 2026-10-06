package dev.hnmc.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.hnmc.HnMc;
import dev.hnmc.link.OverlayLink;
import java.lang.foreign.MemorySegment;
import net.minecraft.client.Minecraft;

/**
 * Copies Minecraft's main render target (hand + HUD + screens on a transparent background, because the
 * level render is skipped while Hello Neighbor is driving) back from the GPU and publishes it through the
 * overlay triple buffer.
 *
 * Adapted from SkyCraft's FrameExporter (https://github.com/chasmlol/SkyCraft, MIT License,
 * Copyright (c) SkyCraft contributors). The copy is asynchronous: a frame goes into one of three staging
 * buffers and is shipped once the GPU reports the copy finished, typically one frame later.
 */
final class FrameExporter {
	private static final int STAGING = 3;
	private static final int FREE = 0, PENDING = 1, READY = 2;
	private static final int USAGE_MAP_READ_COPY_DST = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;

	private static final Staging[] staging = new Staging[STAGING];
	private static long nextFrameId = 1;
	private static boolean loggedFormat;

	private static final class Staging {
		GpuBuffer buffer;
		int width, height;
		volatile int state = FREE;
		long frameId;
	}

	private FrameExporter() {}

	/** Render thread, right after GameRenderer.render(). */
	static void capture(Minecraft minecraft, OverlayLink overlay) {
		long t0 = System.nanoTime();
		shipReadyFrames(overlay);
		stats(t0, System.nanoTime() - t0);

		RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
		GpuTexture color = target.getColorTexture();
		if (color == null) return;
		int width = target.width, height = target.height;
		if (width > OverlayLink.MAX_W || height > OverlayLink.MAX_H) return;
		if (!loggedFormat) {
			loggedFormat = true;
			HnMc.LOGGER.info("Overlay capture {}x{} format {}", width, height, color.getFormat());
		}

		Staging slot = null;
		for (int i = 0; i < STAGING && slot == null; i++) {
			if (staging[i] == null) staging[i] = new Staging();
			if (staging[i].state == FREE) slot = staging[i];
		}
		if (slot == null) return; // all staging buffers still in flight; skip this frame

		long bytes = (long) width * height * 4L;
		if (slot.buffer == null || slot.width != width || slot.height != height) {
			if (slot.buffer != null) slot.buffer.close();
			slot.buffer = RenderSystem.getDevice().createBuffer(() -> "hnmc overlay readback", USAGE_MAP_READ_COPY_DST, bytes);
			slot.width = width;
			slot.height = height;
		}
		final Staging captured = slot;
		captured.state = PENDING;
		captured.frameId = nextFrameId++;
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(color, captured.buffer, 0L, () -> captured.state = READY, 0);
	}

	// Performance log, every 10 s: Minecraft's frame gaps (render thread, frame to frame) and what shipping a frame
	// into shared memory costs.
	private static long statStart, lastFrameAt, frames, slowFrames, maxGap, shipSum, shipMax;

	private static void stats(long now, long ship) {
		if (lastFrameAt != 0) {
			long gap = now - lastFrameAt;
			if (gap > maxGap) maxGap = gap;
			if (gap > 25_000_000L) slowFrames++;
		}
		lastFrameAt = now;
		frames++;
		shipSum += ship;
		if (ship > shipMax) shipMax = ship;
		if (statStart == 0) statStart = now;
		if (now - statStart >= 10_000_000_000L) {
			if (HnMc.VERBOSE) HnMc.LOGGER.info("Minecraft frames: {} in {} s, longest gap {} ms, gaps over 25 ms: {}; frame copy to Hello Neighbor avg {} ms, max {} ms",
				frames, String.format("%.1f", (now - statStart) / 1e9), String.format("%.1f", maxGap / 1e6), slowFrames,
				String.format("%.2f", shipSum / 1e6 / frames), String.format("%.1f", shipMax / 1e6));
			statStart = now;
			frames = slowFrames = maxGap = shipSum = shipMax = 0;
		}
	}

	/** Maps the newest finished readback and copies it into shared memory. */
	private static void shipReadyFrames(OverlayLink overlay) {
		Staging newest = null;
		for (Staging s : staging) {
			if (s != null && s.state == READY && (newest == null || s.frameId > newest.frameId)) newest = s;
		}
		if (newest == null) return;

		int slot = overlay.pickWriteSlot();
		if (slot != OverlayLink.NONE) {
			long bytes = (long) newest.width * newest.height * 4L;
			try (GpuBufferSlice.MappedView view = newest.buffer.map(true, false)) {
				MemorySegment src = MemorySegment.ofBuffer(view.data());
				MemorySegment.copy(src, 0, overlay.segment(), OverlayLink.pixelsOffset(slot), Math.min(bytes, src.byteSize()));
			}
			overlay.publish(slot, newest.width, newest.height, OverlayLink.FLAG_BOTTOM_UP, newest.frameId);
		}
		// Anything older than what we just shipped is useless now.
		for (Staging s : staging) {
			if (s != null && s.state == READY && s.frameId <= newest.frameId) s.state = FREE;
		}
	}
}
