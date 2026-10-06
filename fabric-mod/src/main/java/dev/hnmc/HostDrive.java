package dev.hnmc;

/** True while Hello Neighbor moves the Minecraft player (set by the client each tick, read on the server thread). */
public final class HostDrive {
	public static volatile boolean active;

	private HostDrive() {}
}
