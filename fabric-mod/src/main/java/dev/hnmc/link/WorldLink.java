package dev.hnmc.link;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * Producer side of the world channel (protocol/hn_world.h): Minecraft's blocks as a textured triangle mesh plus the
 * block atlas, for the Hello Neighbor graphics plugin to draw in the 3D scene. Single producer (client thread).
 */
public final class WorldLink implements AutoCloseable {
	public static final String MAPPING_NAME = "Local\\HelloNeighborMC_world_v2";
	public static final int MAGIC = 0x44574E48; // "HNWD"
	public static final int VERSION = 2;
	public static final int NONE = 3;

	public static final int VERTEX_BYTES = 24;
	public static final int MAX_VERTS = 6 * 65536;
	public static final long SLOT_BYTES = (long) MAX_VERTS * VERTEX_BYTES;
	public static final long MESH_OFF = 65536;
	public static final long ATLAS_OFF = MESH_OFF + 2 * SLOT_BYTES;
	public static final int ATLAS_MAX = 4096;
	// v2: textures + entities
	public static final long TEX_TABLE_OFF = 4096;
	public static final int MAX_TEXTURES = 512, TEXTURE_ENTRY = 16;
	public static final long ENT_OFF = ATLAS_OFF + (long) ATLAS_MAX * ATLAS_MAX * 4;
	public static final int MAX_BATCHES = 1024, BATCH_BYTES = 16;
	public static final int ENT_MAX_VERTS = 196608;
	public static final long ENT_SLOT_BYTES = 73L * 65536;
	public static final long TEX_HEAP_OFF = ENT_OFF + 2 * ENT_SLOT_BYTES;
	public static final long TEX_HEAP_BYTES = 64L << 20;
	public static final long MAPPING_BYTES = TEX_HEAP_OFF + TEX_HEAP_BYTES;

	public static final long H_MAGIC = 0, H_VERSION = 4, H_CTL = 8, H_MESH_SEQ = 16, H_VERTEX_COUNT = 24, H_ATLAS_W = 32,
		H_ATLAS_H = 36, H_ATLAS_SEQ = 40, H_HOST_FRAMES = 48, H_HOST_VERTS = 56, H_TEX_COUNT = 64, H_ENT_CTL = 68, H_ENT_SEQ = 72,
		H_ENT_BATCH_COUNT = 80, H_ENT_VERTEX_COUNT = 88, H_ENT_ORIGIN = 96;

	private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();
	private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle();

	private final MemorySegment handle;
	private final MemorySegment view;
	public final boolean created;
	private boolean closed;

	private WorldLink(MemorySegment handle, MemorySegment view, boolean created) {
		this.handle = handle;
		this.view = view;
		this.created = created;
	}

	public static WorldLink open(String name) {
		Win32.Created c = Win32.createFileMapping(name, MAPPING_BYTES);
		MemorySegment view = Win32.mapView(c.handle(), MAPPING_BYTES);
		WorldLink w = new WorldLink(c.handle(), view, !c.alreadyExisted());
		if (w.created) {
			view.set(ValueLayout.JAVA_INT, H_VERSION, VERSION);
			INT.setRelease(view, H_CTL, NONE | (NONE << 8));
			INT.setRelease(view, H_ENT_CTL, NONE | (NONE << 8));
			INT.setRelease(view, H_MAGIC, MAGIC);
		}
		return w;
	}

	public static WorldLink openDefault() { return open(MAPPING_NAME); }

	public MemorySegment segment() { return view; }

	public long hostFrames() { return (long) LONG.getOpaque(view, H_HOST_FRAMES); }
	public int hostVertsDrawn() { return (int) INT.getOpaque(view, H_HOST_VERTS); }

	/** A mesh slot that is neither ACTIVE nor being READ by the host, or NONE. */
	public int pickMeshSlot() {
		int ctl = (int) INT.getAcquire(view, H_CTL);
		int active = ctl & 3, reading = (ctl >>> 8) & 3;
		for (int s = 0; s < 2; s++) if (s != active && s != reading) return s;
		return NONE;
	}

	public static long slotOffset(int slot) { return MESH_OFF + slot * SLOT_BYTES; }

	/** Vertices are already in the slot: make it ACTIVE. */
	public void publishMesh(int slot, int vertexCount) {
		view.set(ValueLayout.JAVA_INT, H_VERTEX_COUNT + 4L * slot, vertexCount);
		int cur;
		do {
			cur = (int) INT.getVolatile(view, H_CTL);
		} while (!INT.compareAndSet(view, H_CTL, cur, (cur & ~3) | slot));
		LONG.getAndAdd(view, H_MESH_SEQ, 1L);
	}

	/** Atlas pixels are already at ATLAS_OFF. */
	public void publishAtlas(int width, int height) {
		view.set(ValueLayout.JAVA_INT, H_ATLAS_W, width);
		view.set(ValueLayout.JAVA_INT, H_ATLAS_H, height);
		LONG.getAndAdd(view, H_ATLAS_SEQ, 1L);
	}

	// ---- v2: textures (append-only table + heap) ----
	private long texHeapUsed;

	/** Reserves heap space for a w x h RGBA8 texture; returns its heap offset, or -1 if the heap or table is full. */
	public long reserveTexture(int width, int height) {
		long bytes = (long) width * height * 4;
		int n = (int) INT.getAcquire(view, H_TEX_COUNT);
		if (n >= MAX_TEXTURES || texHeapUsed + bytes > TEX_HEAP_BYTES) return -1;
		long off = texHeapUsed;
		texHeapUsed += (bytes + 255) & ~255L;
		return off;
	}

	/** Pixels are already at TEX_HEAP_OFF + heapOffset: append the table entry. */
	public void publishTexture(int id, int width, int height, long heapOffset) {
		int n = (int) INT.getAcquire(view, H_TEX_COUNT);
		long e = TEX_TABLE_OFF + (long) n * TEXTURE_ENTRY;
		view.set(ValueLayout.JAVA_INT, e, id);
		view.set(ValueLayout.JAVA_INT, e + 4, width);
		view.set(ValueLayout.JAVA_INT, e + 8, height);
		view.set(ValueLayout.JAVA_INT, e + 12, (int) heapOffset);
		INT.setRelease(view, H_TEX_COUNT, n + 1);
	}

	// ---- v2: entities ----
	public int pickEntitySlot() {
		int ctl = (int) INT.getAcquire(view, H_ENT_CTL);
		int active = ctl & 3, reading = (ctl >>> 8) & 3;
		for (int s = 0; s < 2; s++) if (s != active && s != reading) return s;
		return NONE;
	}

	public static long entBatchOffset(int slot) { return ENT_OFF + slot * ENT_SLOT_BYTES; }
	public static long entVertexOffset(int slot) { return ENT_OFF + slot * ENT_SLOT_BYTES + (long) MAX_BATCHES * BATCH_BYTES; }

	public void publishEntities(int slot, int batches, int vertices, double ox, double oy, double oz) {
		view.set(ValueLayout.JAVA_INT, H_ENT_BATCH_COUNT + 4L * slot, batches);
		view.set(ValueLayout.JAVA_INT, H_ENT_VERTEX_COUNT + 4L * slot, vertices);
		view.set(ValueLayout.JAVA_DOUBLE, H_ENT_ORIGIN + 24L * slot, ox);
		view.set(ValueLayout.JAVA_DOUBLE, H_ENT_ORIGIN + 24L * slot + 8, oy);
		view.set(ValueLayout.JAVA_DOUBLE, H_ENT_ORIGIN + 24L * slot + 16, oz);
		int cur;
		do {
			cur = (int) INT.getVolatile(view, H_ENT_CTL);
		} while (!INT.compareAndSet(view, H_ENT_CTL, cur, (cur & ~3) | slot));
		LONG.getAndAdd(view, H_ENT_SEQ, 1L);
	}

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		Win32.unmapView(view);
		Win32.closeHandle(handle);
	}
}
