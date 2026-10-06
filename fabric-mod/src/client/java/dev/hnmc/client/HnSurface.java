package dev.hnmc.client;

import dev.hnmc.HnProxy;
import dev.hnmc.SurfaceProxy;
import dev.hnmc.link.McLink;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Makes Hello Neighbor's walls and floors part of Minecraft's crosshair. HnLink traces from Hello Neighbor's camera
 * every sample and sends the hit (kCmdSurface). When Minecraft's own pick finds nothing closer, the crosshair target
 * becomes that surface: the Minecraft cell just behind it, with the face it was hit on. Right-click then places
 * against it like against any block (SurfaceProxy does the server half); left-click on it does nothing, as on air.
 */
public final class HnSurface {
	private static final long STALE_NS = 250_000_000L; // HnLink samples every ~33 ms; older than this = lost
	private static final double PROBE_SLACK = 0.35;     // blocks

	private record Hit(Vec3 location, Direction face, BlockPos cell, long nanos) {}

	private static volatile Hit latest;
	/** kCmdSurface d bit 8: Hello Neighbor can interact with what is under the crosshair (door, switch, item...). */
	private static volatile boolean interactable;
	private static int seenSeq = -1;

	private HnSurface() {}

	/** Client tick, after {@code link.poll()}. */
	static void tick(McLink link) {
		if (link.surfaceSeq() == seenSeq) return;
		seenSeq = link.surfaceSeq();
		int f = link.surfaceFace() & 0xFF;
		interactable = (link.surfaceFace() & 0x100) != 0;
		if (f < 1 || f > 6 || !link.hostInGame()) {
			latest = null;
			interactable = false;
			return;
		}
		Direction face = Direction.from3DDataValue(f - 1);
		Vec3 loc = new Vec3(link.surfaceX(), link.surfaceY(), link.surfaceZ());
		// The cell the surface belongs to: step a hair INTO it, against the face normal.
		BlockPos cell = BlockPos.containing(loc.x - face.getStepX() * 1e-3, loc.y - face.getStepY() * 1e-3, loc.z - face.getStepZ() * 1e-3);
		latest = new Hit(loc, face, cell, System.nanoTime());
	}

	/**
	 * Right-click goes to Hello Neighbor (open, press, pick up) instead of Minecraft: its surface under the crosshair
	 * is interactable, fresh, and what the crosshair targets is Hello Neighbor's (a proxy block or its surface), not
	 * a Minecraft block or entity.
	 */
	public static boolean interactableTarget(Minecraft mc) {
		Hit h = latest;
		if (!interactable || h == null || mc.player == null || mc.level == null || System.nanoTime() - h.nanos > STALE_NS) return false;
		if (!(mc.hitResult instanceof BlockHitResult bh) || bh.getType() != HitResult.Type.BLOCK) return false;
		var state = mc.level.getBlockState(bh.getBlockPos());
		return state.isAir() || HnProxy.isProxy(state);
	}

	/** End of Minecraft.pick: if the Hello Neighbor surface is closer than what Minecraft hit, target it instead. */
	public static void adjustPick(Minecraft mc) {
		Hit h = latest;
		if (h == null || mc.player == null || !HnOverlay.active() || System.nanoTime() - h.nanos > STALE_NS) return;
		Vec3 eye = mc.player.getEyePosition();
		double d = h.location.distanceTo(eye);
		if (d > mc.player.blockInteractionRange()) return;
		HitResult mcHit = mc.hitResult;
		if (mcHit != null && mcHit.getType() != HitResult.Type.MISS) {
			// A proxy IS Hello Neighbor geometry, and exact: Minecraft's own hit on it beats the host's estimate.
			if (mcHit instanceof BlockHitResult bh && HnProxy.isProxy(mc.level.getBlockState(bh.getBlockPos()))) return;
			// The host trace finds surfaces with a sphere probe, so it can read up to ~0.2 blocks short (more at
			// grazing angles). Only a surface CLEARLY in front of a Minecraft block hides it; otherwise aiming at a
			// block next to a wall would flicker between the two and restart the mining every time.
			if (mcHit.getLocation().distanceTo(eye) <= d + PROBE_SLACK) return;
		}
		mc.hitResult = new BlockHitResult(h.location, h.face, h.cell, false);
		mc.crosshairPickEntity = null;
		SurfaceProxy.offer(h.cell);
	}
}
