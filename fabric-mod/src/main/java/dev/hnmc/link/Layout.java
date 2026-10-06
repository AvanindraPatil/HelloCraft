package dev.hnmc.link;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hand-mirrored copy of protocol/hn_protocol.h. LayoutTest compares {@link #ALL}
 * against the output of protocol/tools/layout_dump.cpp, so any drift fails a test.
 */
public final class Layout {
	private Layout() {}

	public static final String MAPPING_NAME = "Local\\HelloNeighborMC_v0";
	public static final int MAGIC = 0x434D4E48; // "HNMC"
	public static final int VERSION = 0;

	public static final long OFF_HOST_STATE = 0x0100;
	public static final long OFF_MC_STATE = 0x0200;
	public static final long OFF_CMD_RING = 0x1000;
	public static final long OFF_EVENT_RING = 0xA000;
	public static final long CMD_RING_SLOTS = 1024;
	public static final long EVENT_RING_SLOTS = 4096;
	public static final long RING_HEAD_OFF = 0, RING_TAIL_OFF = 64, RING_DATA_OFF = 128;
	public static final long MESSAGE_SIZE = 32;

	public static long ringBytes(long slots) { return RING_DATA_OFF + slots * MESSAGE_SIZE; }
	public static final long MAPPING_BYTES = (OFF_EVENT_RING + ringBytes(EVENT_RING_SLOTS) + 0xFFF) & ~0xFFFL;

	// Header (offsets relative to mapping start)
	public static final long H_MAGIC = 0, H_VERSION = 4, H_HOST_PID = 8, H_MC_PID = 12, H_HOST_HEARTBEAT = 16, H_MC_HEARTBEAT = 24;

	// HostState (relative to OFF_HOST_STATE)
	public static final long HS_SEQ = 0, HS_FLAGS = 4, HS_WORLD_ID = 8, HS_TELEPORT_SEQ = 12, HS_POS_X = 16, HS_POS_Y = 24, HS_POS_Z = 32,
		HS_YAW = 40, HS_PITCH = 44, HS_ON_GROUND = 48, HS_GAME_SECONDS = 56, HS_SIZE = 64;

	// McState (relative to OFF_MC_STATE)
	public static final long MS_SEQ = 0, MS_FLAGS = 4, MS_X = 8, MS_Y = 16, MS_Z = 24, MS_YAW = 32, MS_PITCH = 36, MS_EYE_HEIGHT = 40,
		MS_HEALTH = 44, MS_TELEPORT_ACK = 48, MS_HOTBAR_SLOT = 52, MS_HOTBAR_ITEM = 56, MS_TARGET_X = 76, MS_TARGET_Y = 80,
		MS_TARGET_Z = 84, MS_TARGET_FACE = 88, MS_TICK_COUNTER = 96, MS_CAM_DX = 104, MS_CAM_DY = 108, MS_CAM_DZ = 112,
		MS_CAM_YAW = 116, MS_CAM_PITCH = 120, MS_CAM_FOV = 124, MS_SIZE = 128;

	// Message (relative to slot start)
	public static final long M_TYPE = 0, M_FLAGS = 4, M_A = 8, M_B = 12, M_C = 16, M_D = 20, M_SEQ = 24;

	public static final int HOST_IN_GAME = 1, HOST_MENU_OPEN = 1 << 1, HOST_MC_DRIVES = 1 << 3;
	public static final int MC_IN_WORLD = 1, MC_ON_GROUND = 1 << 2, MC_DRIVING = 1 << 4, MC_CAM_DETACHED = 1 << 5;

	public static final int CMD_BREAK_BLOCK = 1, CMD_PLACE_BLOCK = 2, CMD_SELECT_SLOT = 3, CMD_SCROLL_SLOT = 4, CMD_RESYNC = 5, CMD_SURFACE = 6,
		CMD_PROXY_CELL = 7, CMD_PROXY_CLEAR = 8, CMD_NEIGHBOR = 9, CMD_NEIGHBOR_SIZE = 10, CMD_HN_ITEM = 11;
	public static final int EV_BLOCK_CHANGE = 1, EV_PLAYER_HURT = 2, EV_PLAYER_DIED = 3, EV_RESYNC_DONE = 4, EV_NEIGHBOR_HIT = 5,
		EV_SURFACE_HIT = 6, EV_EXPLOSION = 7, EV_SCAN_CELL = 8, EV_HN_SELECT = 9, EV_HELD_FIRST = 10, EV_HELD_THIRD = 11, EV_HELD_ROT = 12;

	/** Block palette (hn_protocol.h BlockId). Minecraft maps these to real blocks; the host picks a look per id. */
	public static final int BLOCK_AIR = 0, BLOCK_STONE = 1, BLOCK_PLANKS = 2, BLOCK_DIRT = 3, BLOCK_COBBLE = 4, BLOCK_BRICKS = 5,
		BLOCK_GLASS = 6, BLOCK_OTHER = 255;

	/** Same keys as protocol/tools/layout_dump.cpp prints. */
	public static final Map<String, Long> ALL = new LinkedHashMap<>();
	static {
		Map<String, Long> m = ALL;
		m.put("mappingBytes", MAPPING_BYTES);
		m.put("magic", (long) MAGIC & 0xFFFFFFFFL);
		m.put("version", (long) VERSION);
		m.put("offHostState", OFF_HOST_STATE);
		m.put("offMcState", OFF_MC_STATE);
		m.put("offCmdRing", OFF_CMD_RING);
		m.put("offEventRing", OFF_EVENT_RING);
		m.put("cmdRingSlots", CMD_RING_SLOTS);
		m.put("eventRingSlots", EVENT_RING_SLOTS);
		m.put("ringHeadOff", RING_HEAD_OFF);
		m.put("ringTailOff", RING_TAIL_OFF);
		m.put("ringDataOff", RING_DATA_OFF);
		m.put("Header.size", 64L);
		m.put("Header.magic", H_MAGIC);
		m.put("Header.version", H_VERSION);
		m.put("Header.hostPid", H_HOST_PID);
		m.put("Header.mcPid", H_MC_PID);
		m.put("Header.hostHeartbeatMs", H_HOST_HEARTBEAT);
		m.put("Header.mcHeartbeatMs", H_MC_HEARTBEAT);
		m.put("HostState.size", HS_SIZE);
		m.put("HostState.seq", HS_SEQ);
		m.put("HostState.flags", HS_FLAGS);
		m.put("HostState.worldId", HS_WORLD_ID);
		m.put("HostState.teleportSeq", HS_TELEPORT_SEQ);
		m.put("HostState.posX", HS_POS_X);
		m.put("HostState.posY", HS_POS_Y);
		m.put("HostState.posZ", HS_POS_Z);
		m.put("HostState.yaw", HS_YAW);
		m.put("HostState.pitch", HS_PITCH);
		m.put("HostState.onGround", HS_ON_GROUND);
		m.put("HostState.gameSeconds", HS_GAME_SECONDS);
		m.put("McState.size", MS_SIZE);
		m.put("McState.seq", MS_SEQ);
		m.put("McState.flags", MS_FLAGS);
		m.put("McState.x", MS_X);
		m.put("McState.y", MS_Y);
		m.put("McState.z", MS_Z);
		m.put("McState.yaw", MS_YAW);
		m.put("McState.pitch", MS_PITCH);
		m.put("McState.eyeHeight", MS_EYE_HEIGHT);
		m.put("McState.health", MS_HEALTH);
		m.put("McState.teleportAck", MS_TELEPORT_ACK);
		m.put("McState.hotbarSlot", MS_HOTBAR_SLOT);
		m.put("McState.hotbarItem", MS_HOTBAR_ITEM);
		m.put("McState.targetX", MS_TARGET_X);
		m.put("McState.targetY", MS_TARGET_Y);
		m.put("McState.targetZ", MS_TARGET_Z);
		m.put("McState.targetFace", MS_TARGET_FACE);
		m.put("McState.tickCounter", MS_TICK_COUNTER);
		m.put("McState.camDX", MS_CAM_DX);
		m.put("McState.camDY", MS_CAM_DY);
		m.put("McState.camDZ", MS_CAM_DZ);
		m.put("McState.camYaw", MS_CAM_YAW);
		m.put("McState.camPitch", MS_CAM_PITCH);
		m.put("McState.camFov", MS_CAM_FOV);
		m.put("Message.size", MESSAGE_SIZE);
		m.put("Message.type", M_TYPE);
		m.put("Message.flags", M_FLAGS);
		m.put("Message.a", M_A);
		m.put("Message.b", M_B);
		m.put("Message.c", M_C);
		m.put("Message.d", M_D);
		m.put("Message.seq", M_SEQ);
		m.put("kHostInGame", (long) HOST_IN_GAME);
		m.put("kHostMcDrives", (long) HOST_MC_DRIVES);
		m.put("kMcInWorld", (long) MC_IN_WORLD);
		m.put("kMcOnGround", (long) MC_ON_GROUND);
		m.put("kMcDriving", (long) MC_DRIVING);
		m.put("kCmdBreakBlock", (long) CMD_BREAK_BLOCK);
		m.put("kCmdPlaceBlock", (long) CMD_PLACE_BLOCK);
		m.put("kCmdSelectSlot", (long) CMD_SELECT_SLOT);
		m.put("kCmdScrollSlot", (long) CMD_SCROLL_SLOT);
		m.put("kCmdResync", (long) CMD_RESYNC);
		m.put("world.version", (long) WorldLink.VERSION);
		m.put("world.vertexBytes", (long) WorldLink.VERTEX_BYTES);
		m.put("world.maxVerts", (long) WorldLink.MAX_VERTS);
		m.put("world.meshOff", WorldLink.MESH_OFF);
		m.put("world.atlasOff", WorldLink.ATLAS_OFF);
		m.put("world.atlasMax", (long) WorldLink.ATLAS_MAX);
		m.put("world.texTableOff", WorldLink.TEX_TABLE_OFF);
		m.put("world.maxTextures", (long) WorldLink.MAX_TEXTURES);
		m.put("world.entOff", WorldLink.ENT_OFF);
		m.put("world.maxBatches", (long) WorldLink.MAX_BATCHES);
		m.put("world.entMaxVerts", (long) WorldLink.ENT_MAX_VERTS);
		m.put("world.entSlotBytes", WorldLink.ENT_SLOT_BYTES);
		m.put("world.texHeapOff", WorldLink.TEX_HEAP_OFF);
		m.put("world.mappingBytes", WorldLink.MAPPING_BYTES);
		m.put("world.H.texCount", WorldLink.H_TEX_COUNT);
		m.put("world.H.entCtl", WorldLink.H_ENT_CTL);
		m.put("world.H.entSeq", WorldLink.H_ENT_SEQ);
		m.put("world.H.entBatchCount", WorldLink.H_ENT_BATCH_COUNT);
		m.put("world.H.entVertexCount", WorldLink.H_ENT_VERTEX_COUNT);
		m.put("world.H.entOrigin", WorldLink.H_ENT_ORIGIN);
		m.put("kCmdSurface", (long) CMD_SURFACE);
		m.put("kCmdProxyCell", (long) CMD_PROXY_CELL);
		m.put("kCmdProxyClear", (long) CMD_PROXY_CLEAR);
		m.put("kCmdNeighbor", (long) CMD_NEIGHBOR);
		m.put("kCmdNeighborSize", (long) CMD_NEIGHBOR_SIZE);
		m.put("kCmdHnItem", (long) CMD_HN_ITEM);
		m.put("kEvBlockChange", (long) EV_BLOCK_CHANGE);
		m.put("kEvPlayerHurt", (long) EV_PLAYER_HURT);
		m.put("kEvPlayerDied", (long) EV_PLAYER_DIED);
		m.put("kEvResyncDone", (long) EV_RESYNC_DONE);
		m.put("kEvNeighborHit", (long) EV_NEIGHBOR_HIT);
		m.put("kEvSurfaceHit", (long) EV_SURFACE_HIT);
		m.put("kEvExplosion", (long) EV_EXPLOSION);
		m.put("kEvScanCell", (long) EV_SCAN_CELL);
		m.put("kEvHnSelect", (long) EV_HN_SELECT);
		m.put("kEvHeldFirst", (long) EV_HELD_FIRST);
		m.put("kEvHeldThird", (long) EV_HELD_THIRD);
		m.put("kEvHeldRot", (long) EV_HELD_ROT);
		m.put("kBlockAir", (long) BLOCK_AIR);
		m.put("kBlockStone", (long) BLOCK_STONE);
		m.put("kBlockPlanks", (long) BLOCK_PLANKS);
		m.put("kBlockDirt", (long) BLOCK_DIRT);
		m.put("kBlockCobble", (long) BLOCK_COBBLE);
		m.put("kBlockBricks", (long) BLOCK_BRICKS);
		m.put("kBlockGlass", (long) BLOCK_GLASS);
		m.put("kBlockOther", (long) BLOCK_OTHER);
	}
}
