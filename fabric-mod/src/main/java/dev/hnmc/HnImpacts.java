package dev.hnmc;

import dev.hnmc.link.Layout;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft things that should hit Hello Neighbor's world: a punch or sword on one of its walls/objects, an arrow
 * that lands in its geometry, an explosion. Recorded on whatever thread sees them (client for melee, server for
 * arrows and explosions) and pushed to the host by the client thread, the only one allowed to write the event ring.
 */
public final class HnImpacts {
	public static final int KIND_MELEE = 0, KIND_ARROW = 1;

	/** One kEvSurfaceHit / kEvExplosion / kEvNeighborHit, ready to push. */
	public record Event(int type, int a, int b, int c, int d) {}

	private static final Queue<Event> QUEUE = new ConcurrentLinkedQueue<>();

	private HnImpacts() {}

	/** Something hit Hello Neighbor's geometry at this point (Minecraft coordinates). */
	public static void surface(Vec3 at, float strength, int kind) {
		int s = Math.min(0xFFFFFF, Math.max(0, Math.round(strength * 100)));
		QUEUE.add(new Event(Layout.EV_SURFACE_HIT, mm(at.x), mm(at.y), mm(at.z), s | (kind << 24)));
	}

	/** An explosion (TNT, creeper, ...) centred here. */
	public static void explosion(Vec3 centre, float radius) {
		QUEUE.add(new Event(Layout.EV_EXPLOSION, mm(centre.x), mm(centre.y), mm(centre.z), Math.round(radius * 100)));
	}

	/** The player hit the neighbour (melee or arrow); dir = horizontal push direction. */
	public static void neighbour(float strength, double dirX, double dirZ) {
		double len = Math.max(1e-6, Math.sqrt(dirX * dirX + dirZ * dirZ));
		QUEUE.add(new Event(Layout.EV_NEIGHBOR_HIT, Math.round(strength * 100), (int) Math.round(dirX / len * 1000), (int) Math.round(dirZ / len * 1000), 0));
	}

	/**
	 * A fresh arrow: predict its flight (vanilla arrow physics: drag 0.99, gravity 0.05 per tick) and ask Hello
	 * Neighbor to scan every cell on the way. Its geometry only exists in Minecraft where it has been scanned (around
	 * the player), so far arrows used to fly through walls.
	 */
	public static void arrowPath(Vec3 start, Vec3 velocity, double minY) { projectilePath(start, velocity, minY, 0.05); }

	/** The same for any projectile, with its own gravity per tick (ender pearl, bobber: 0.03). */
	public static void projectilePath(Vec3 start, Vec3 velocity, double minY, double gravity) {
		java.util.Set<Long> seen = new java.util.LinkedHashSet<>();
		double x = start.x, y = start.y, z = start.z, vx = velocity.x, vy = velocity.y, vz = velocity.z;
		for (int tick = 0; tick < 100 && seen.size() < MAX_PATH_CELLS && y > minY; tick++) {
			for (int s = 1; s <= PATH_SAMPLES; s++) {   // dense enough that no crossed cell is skipped
				double f = (double) s / PATH_SAMPLES;
				int cx = (int) Math.floor(x + vx * f), cy = (int) Math.floor(y + vy * f), cz = (int) Math.floor(z + vz * f);
				// d = 1: urgent (the projectile is on its way). Also the cell below: a slightly different real flight
				// (the thrower's own speed is added) must still find the floor it lands on.
				if (seen.add(net.minecraft.core.BlockPos.asLong(cx, cy, cz))) QUEUE.add(new Event(Layout.EV_SCAN_CELL, cx, cy, cz, 1));
				if (seen.add(net.minecraft.core.BlockPos.asLong(cx, cy - 1, cz))) QUEUE.add(new Event(Layout.EV_SCAN_CELL, cx, cy - 1, cz, 1));
			}
			x += vx;
			y += vy;
			z += vz;
			vx *= 0.99;
			vy = vy * 0.99 - gravity;
			vz *= 0.99;
		}
	}

	private static final int PATH_SAMPLES = 8, MAX_PATH_CELLS = 800;

	/** Please scan this cell for Hello Neighbor geometry soon (mobs walking there, see HnMobs). */
	public static void scanCell(int x, int y, int z) { QUEUE.add(new Event(Layout.EV_SCAN_CELL, x, y, z, 0)); }

	/** The same, before anything else (something is about to get there: water or lava flowing, see FluidGuard). */
	public static void scanCellUrgent(int x, int y, int z) { QUEUE.add(new Event(Layout.EV_SCAN_CELL, x, y, z, 1)); }

	public static Event poll() { return QUEUE.poll(); }

	private static int mm(double v) { return (int) Math.round(v * 1000); }
}
