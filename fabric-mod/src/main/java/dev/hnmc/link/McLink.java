package dev.hnmc.link;

/**
 * The Minecraft side of the link. Two ways to drive it, once per Minecraft tick (20 Hz):
 *
 *  - {@link #tick()}: poll + echo the host position straight into McState + publish. Used by tests
 *    and as the fallback when no Minecraft world is running.
 *  - {@link #poll()}, then {@link #setPublished}, then {@link #publish()}: the mod reads where the host wants
 *    the player (the target), moves the real Minecraft player, and publishes what the real player did.
 *
 * Block commands go to the {@link BlockHandler} if one is set (the mod applies them to the real world and
 * reports back with {@link #pushEvent}); without one they are echoed as BlockChange events (tests, fake hosts).
 * No Minecraft classes are used, so this class is unit-testable on its own.
 *
 * Threading: everything here, including {@link #pushEvent}, must be called from ONE thread (the event ring
 * has a single producer). The mod uses the client tick thread.
 */
public final class McLink implements AutoCloseable {
	/** Last host state read. Plain fields; written and read on the Minecraft tick thread. */
	public static final class HostSnapshot {
		public int flags, worldId, teleportSeq, onGround;
		public double posX, posY, posZ;
		public float yaw, pitch;
		public boolean valid;
	}

	/** Receives block commands from the host. Called from {@link #poll()}. */
	public interface BlockHandler {
		void breakBlock(int x, int y, int z);
		void placeBlock(int x, int y, int z, int blockId);
		/** Host asks for every block it should be showing (after a reconnect or map change). */
		void resync();
		/** Hello Neighbor geometry in one cell: 64-bit quarter-block occupancy mask (0 = empty). */
		default void proxyCell(int x, int y, int z, long mask) {}
		/** Forget all Hello Neighbor geometry. */
		default void proxyClear() {}
	}

	private final SharedMemory shm;
	private final SharedMemory.Ring commands;
	private final SharedMemory.Ring events;
	private final int[] msg = new int[7];
	private final int[] out = new int[7];
	private BlockHandler blockHandler;

	public final HostSnapshot host = new HostSnapshot();

	// Where the host wants the player, in Minecraft coordinates (valid once the host is in game).
	private double tx, ty, tz;
	private float tYaw, tPitch;
	private boolean tOnGround;
	private int tTeleportSeq;

	// Last kCmdSurface: what Hello Neighbor's camera looks at, in Minecraft coordinates.
	private double surfaceX, surfaceY, surfaceZ;
	private int surfaceFace, surfaceSeq;

	// Last kCmdNeighbor / kCmdNeighborSize: Hello Neighbor's AI character, in Minecraft coordinates.
	private double neighborX, neighborY, neighborZ;
	private float neighborYaw, neighborRadius = 0.3f, neighborHeight = 2.0f;
	private boolean neighborPresent;

	// What we publish in McState.
	private double x, y, z;
	private float yaw, pitch;
	private int mcFlags, teleportAck, hotbarSlot;
	private float eyeHeight = 1.62f;
	private float camDX, camDY, camDZ, camYaw, camPitch, camFov = 70f;

	private long tickCounter, commandsHandled, eventsDropped;
	private int eventSeq;

	public McLink(String mappingName) {
		shm = SharedMemory.open(mappingName);
		commands = shm.ring(Layout.OFF_CMD_RING, Layout.CMD_RING_SLOTS);
		events = shm.ring(Layout.OFF_EVENT_RING, Layout.EVENT_RING_SLOTS);
		shm.putInt(Layout.H_MC_PID, (int) ProcessHandle.current().pid());
	}

	public static McLink openDefault() { return new McLink(Layout.MAPPING_NAME); }

	public boolean createdMapping() { return shm.created; }
	public long ticks() { return tickCounter; }
	public long commandsHandled() { return commandsHandled; }
	public long eventsDropped() { return eventsDropped; }
	public int hotbarSlot() { return hotbarSlot; }
	public void setBlockHandler(BlockHandler handler) { this.blockHandler = handler; }

	/** Published position (what the host sees). */
	public double x() { return x; }
	public double y() { return y; }
	public double z() { return z; }

	/** True if the host wrote a heartbeat within the last {@code timeoutMs}. */
	public boolean hostAlive(long timeoutMs) {
		long hb = shm.getLong(Layout.H_HOST_HEARTBEAT);
		return hb != 0 && Win32.tickCount64() - hb <= timeoutMs;
	}

	// ---- target: where the host wants the player ----
	public boolean hostInGame() { return host.valid && (host.flags & Layout.HOST_IN_GAME) != 0; }
	/** The host wants Minecraft's physics to move the player (step 3b). */
	public boolean hostWantsMcDrive() { return hostInGame() && (host.flags & Layout.HOST_MC_DRIVES) != 0; }
	/** Hello Neighbor's own menu is open (game paused): all input must go to it. */
	public boolean hostMenuOpen() { return host.valid && (host.flags & Layout.HOST_MENU_OPEN) != 0; }
	public double targetX() { return tx; }
	public double targetY() { return ty; }
	public double targetZ() { return tz; }
	public float targetYaw() { return tYaw; }
	public float targetPitch() { return tPitch; }
	public boolean targetOnGround() { return tOnGround; }
	/** Bumps when the host teleported the player (map change, big jump). */
	public int targetTeleportSeq() { return tTeleportSeq; }

	// ---- surface: the Hello Neighbor wall/floor the host camera looks at (kCmdSurface) ----
	/** Face as Minecraft Direction ordinal + 1, or 0 if the host's trace hit nothing. */
	public int surfaceFace() { return surfaceFace; }
	public double surfaceX() { return surfaceX; }
	public double surfaceY() { return surfaceY; }
	public double surfaceZ() { return surfaceZ; }
	/** Bumps with every kCmdSurface received. */
	public int surfaceSeq() { return surfaceSeq; }

	// ---- the neighbour (kCmdNeighbor / kCmdNeighborSize), client thread ----
	public boolean neighborPresent() { return neighborPresent; }
	public double neighborX() { return neighborX; }
	public double neighborY() { return neighborY; }
	public double neighborZ() { return neighborZ; }
	public float neighborYaw() { return neighborYaw; }
	public float neighborRadius() { return neighborRadius; }
	public float neighborHeight() { return neighborHeight; }

	// ---- the tick ----

	/** Heartbeat, read the host, drain commands. Call {@link #publish()} afterwards. */
	public void poll() {
		shm.putLongRelease(Layout.H_MC_HEARTBEAT, Win32.tickCount64());
		if (readHost()) computeTarget();
		drainCommands();
	}

	/** Echo mode: the published state is simply the target. */
	public void tick() {
		poll();
		if (hostInGame()) setPublished(tx, ty, tz, tYaw, tPitch, tOnGround, tTeleportSeq);
		publish();
	}

	/** Set what McState will say: the real Minecraft player's state. */
	public void setPublished(double x, double y, double z, float yaw, float pitch, boolean onGround, int teleportAck) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.yaw = yaw;
		this.pitch = pitch;
		this.teleportAck = teleportAck;
		this.mcFlags = Layout.MC_IN_WORLD | (onGround ? Layout.MC_ON_GROUND : 0);
	}

	/** Eye height above the feet (blocks): changes when sneaking. */
	public void setEyeHeight(float eye) { this.eyeHeight = eye; }

	/** Minecraft's camera: position relative to the feet (blocks), rotation (Minecraft degrees), vertical FOV. */
	public void setCamera(float dx, float dy, float dz, float yaw, float pitch, float fov) {
		camDX = dx;
		camDY = dy;
		camDZ = dz;
		camYaw = yaw;
		camPitch = pitch;
		camFov = fov;
	}

	/** Adds flag bits (e.g. MC_DRIVING) to what setPublished set. */
	public void addFlags(int flags) { this.mcFlags |= flags; }

	/** Clears the in-world flag (no Minecraft world to report on). */
	public void setNotInWorld() { mcFlags = 0; }

	public void publish() {
		long o = Layout.OFF_MC_STATE;
		shm.beginWrite(o);
		shm.putInt(o + Layout.MS_FLAGS, mcFlags);
		shm.putDouble(o + Layout.MS_X, x);
		shm.putDouble(o + Layout.MS_Y, y);
		shm.putDouble(o + Layout.MS_Z, z);
		shm.putFloat(o + Layout.MS_YAW, yaw);
		shm.putFloat(o + Layout.MS_PITCH, pitch);
		shm.putFloat(o + Layout.MS_EYE_HEIGHT, eyeHeight);
		shm.putFloat(o + Layout.MS_HEALTH, 20f);
		shm.putInt(o + Layout.MS_TELEPORT_ACK, teleportAck);
		shm.putInt(o + Layout.MS_HOTBAR_SLOT, hotbarSlot);
		shm.putLong(o + Layout.MS_TICK_COUNTER, tickCounter + 1);
		shm.putFloat(o + Layout.MS_CAM_DX, camDX);
		shm.putFloat(o + Layout.MS_CAM_DY, camDY);
		shm.putFloat(o + Layout.MS_CAM_DZ, camDZ);
		shm.putFloat(o + Layout.MS_CAM_YAW, camYaw);
		shm.putFloat(o + Layout.MS_CAM_PITCH, camPitch);
		shm.putFloat(o + Layout.MS_CAM_FOV, camFov);
		shm.endWrite(o);
		tickCounter++;
	}

	/** Sends one event to the host. Returns false (and counts it) if the event ring is full. */
	public boolean pushEvent(int type, int a, int b, int c, int d) {
		out[0] = type;
		out[1] = 0;
		out[2] = a;
		out[3] = b;
		out[4] = c;
		out[5] = d;
		out[6] = ++eventSeq;
		boolean ok = events.push(out);
		if (!ok) eventsDropped++;
		return ok;
	}

	/** Reads HostState. The fields change only on a clean (seqlock-valid) copy, so a torn read never leaks in. */
	private boolean readHost() {
		long o = Layout.OFF_HOST_STATE;
		for (int attempt = 0; attempt < 1024; attempt++) {
			int s = shm.readBegin(o);
			if (s < 0) {
				if (attempt > 64) Thread.onSpinWait();
				continue;
			}
			int flags = shm.getInt(o + Layout.HS_FLAGS);
			int worldId = shm.getInt(o + Layout.HS_WORLD_ID);
			int teleportSeq = shm.getInt(o + Layout.HS_TELEPORT_SEQ);
			double x = shm.getDouble(o + Layout.HS_POS_X), y = shm.getDouble(o + Layout.HS_POS_Y), z = shm.getDouble(o + Layout.HS_POS_Z);
			float yaw = shm.getFloat(o + Layout.HS_YAW), pitch = shm.getFloat(o + Layout.HS_PITCH);
			int onGround = shm.getInt(o + Layout.HS_ON_GROUND);
			if (shm.readValid(o, s)) {
				host.flags = flags;
				host.worldId = worldId;
				host.teleportSeq = teleportSeq;
				host.posX = x;
				host.posY = y;
				host.posZ = z;
				host.yaw = yaw;
				host.pitch = pitch;
				host.onGround = onGround;
				host.valid = true;
				return (host.flags & Layout.HOST_IN_GAME) != 0;
			}
		}
		return hostInGame();   // the writer was busy the whole time: keep the last clean state
	}

	private void computeTarget() {
		tx = Coords.mcX(host.posX);
		ty = Coords.mcY(host.posZ);
		tz = Coords.mcZ(host.posY);
		tYaw = (float) Coords.hostYawToMc(host.yaw);
		tPitch = (float) Coords.hostPitchToMc(host.pitch);
		tOnGround = host.onGround != 0;
		tTeleportSeq = host.teleportSeq;
	}

	private void drainCommands() {
		while (commands.pop(msg)) {
			commandsHandled++;
			int type = msg[0], a = msg[2], b = msg[3], c = msg[4], d = msg[5];
			switch (type) {
				case Layout.CMD_BREAK_BLOCK -> {
					if (blockHandler != null) blockHandler.breakBlock(a, b, c);
					else pushEvent(Layout.EV_BLOCK_CHANGE, a, b, c, Layout.BLOCK_AIR);
				}
				case Layout.CMD_PLACE_BLOCK -> {
					if (blockHandler != null) blockHandler.placeBlock(a, b, c, d);
					else pushEvent(Layout.EV_BLOCK_CHANGE, a, b, c, d == 0 ? Layout.BLOCK_STONE : d);
				}
				case Layout.CMD_SELECT_SLOT -> hotbarSlot = Math.floorMod(a, 9);
				case Layout.CMD_SCROLL_SLOT -> hotbarSlot = Math.floorMod(hotbarSlot + a, 9);
				case Layout.CMD_SURFACE -> {
					surfaceX = a / 1000.0;
					surfaceY = b / 1000.0;
					surfaceZ = c / 1000.0;
					surfaceFace = d;
					surfaceSeq++;
				}
				case Layout.CMD_PROXY_CELL -> {
					if (blockHandler != null) blockHandler.proxyCell(a, (short) b, b >> 16, (c & 0xFFFFFFFFL) | ((long) d << 32));
				}
				case Layout.CMD_PROXY_CLEAR -> {
					if (blockHandler != null) blockHandler.proxyClear();
				}
				case Layout.CMD_NEIGHBOR -> {
					neighborX = a / 1000.0;
					neighborY = b / 1000.0;
					neighborZ = c / 1000.0;
					neighborYaw = d / 100.0f;
				}
				case Layout.CMD_NEIGHBOR_SIZE -> {
					neighborRadius = a / 1000.0f;
					neighborHeight = b / 1000.0f;
					neighborPresent = c != 0;
				}
				case Layout.CMD_HN_ITEM -> dev.hnmc.HnItems.hostItem(a, b, c, d);
				case Layout.CMD_RESYNC -> {
					if (blockHandler != null) blockHandler.resync();
					else pushEvent(Layout.EV_RESYNC_DONE, 0, 0, 0, 0);
				}
				default -> { }
			}
		}
	}

	@Override
	public void close() { shm.close(); }
}
