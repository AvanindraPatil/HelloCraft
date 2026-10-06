package dev.hnmc.link;

/**
 * Hello Neighbor (Unreal: Z-up, cm) <-> Minecraft (Y-up, blocks). Mirrors protocol/hn_coords.h.
 * mc.x = host.x, mc.y = host.z, mc.z = host.y, all divided by UNITS_PER_BLOCK.
 * (Not -host.y: Unreal is left-handed, Minecraft right-handed, so negating y MIRRORED the worlds: A and D swapped.)
 */
public final class Coords {
	private Coords() {}

	/** Measured: HN player is 151.2 uu tall (capsule half-height 75.6), MC player is 1.8 blocks: 151.2 / 1.8 = 84. Tunable. */
	public static final double UNITS_PER_BLOCK = 84.0;

	public static double mcX(double hostX) { return hostX / UNITS_PER_BLOCK; }
	public static double mcY(double hostZ) { return hostZ / UNITS_PER_BLOCK; }
	public static double mcZ(double hostY) { return hostY / UNITS_PER_BLOCK; }

	public static double hostX(double mcX) { return mcX * UNITS_PER_BLOCK; }
	public static double hostY(double mcZ) { return mcZ * UNITS_PER_BLOCK; }
	public static double hostZ(double mcY) { return mcY * UNITS_PER_BLOCK; }

	/** Unreal forward is (cos y, sin y); Minecraft forward is (-sin y, cos y). With mc.z = host.y: mc = host - 90. */
	public static double hostYawToMc(double yaw) { return yaw - 90.0; }
	public static double mcYawToHost(double yaw) { return yaw + 90.0; }

	/** Unreal pitch is positive looking UP (and may arrive as 0..360); Minecraft pitch is positive looking DOWN. */
	public static double hostPitchToMc(double pitch) {
		double p = ((pitch % 360.0) + 540.0) % 360.0 - 180.0; // -> [-180, 180)
		return Math.max(-90.0, Math.min(90.0, -p));
	}
}
