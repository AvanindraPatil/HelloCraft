package dev.hnmc.link;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * Producer side of the overlay image channel (protocol/hn_overlay.h): Minecraft's hand + HUD + screens on a
 * transparent background, triple-buffered for the Hello Neighbor graphics plugin to draw over the game.
 * Single producer: call from the render thread only.
 */
public final class OverlayLink implements AutoCloseable {
	public static final String MAPPING_NAME = "Local\\HelloNeighborMC_overlay_v0";
	public static final int MAGIC = 0x564F4E48; // "HNOV"
	public static final int VERSION = 1; // v1: input ring + mcFlags
	public static final int MAX_W = 3840, MAX_H = 2160;
	public static final int SLOTS = 3;
	public static final long SLOT_BYTES = (long) MAX_W * MAX_H * 4;
	public static final long PIXELS_OFF = 65536; // header at 0, input ring at 4096, pixels from 64 KiB
	public static final long MAPPING_BYTES = PIXELS_OFF + SLOTS * SLOT_BYTES;
	public static final int NONE = 3;
	public static final int FLAG_BOTTOM_UP = 1;

	// OverlayHeader offsets
	public static final long H_MAGIC = 0, H_VERSION = 4, H_VIEWPORT_W = 8, H_VIEWPORT_H = 12, H_CTL = 16,
		H_FRAMES_PUBLISHED = 24, H_FRAMES_SHOWN = 32, H_MC_FLAGS = 40, H_GUI_SCALE = 44, H_HOST_TAPS = 48, H_SLOTS = 64, HEADER_SIZE = 192;
	// hostTaps[kind]: Hello Neighbor inputs to press once per increment (hn_overlay.h HostTap)
	public static final int TAP_USE = 0, TAP_APPLY = 1, TAP_THROW = 2, TAP_SLOT = 3;
	// OverlaySlot offsets (32 bytes each)
	public static final long S_WIDTH = 0, S_HEIGHT = 4, S_FLAGS = 8, S_FRAME_ID = 16, SLOT_HEADER_SIZE = 32;

	// mcFlags bits (Minecraft -> host)
	public static final int MC_SCREEN_OPEN = 1, MC_MOVE_KEYS = 2, HOST_MENU = 4;

	// Input ring (host -> Minecraft): head u64 at +0, tail u64 at +64, 16-byte events at +128
	public static final long IN_RING_OFF = 4096, IN_SLOTS = 2048, IN_EVENT_SIZE = 16;
	public static final int IN_KEY = 1, IN_MOUSE_BUTTON = 2, IN_SCROLL = 3, IN_CURSOR = 4, IN_TEXT = 5, IN_RELEASE_ALL = 6;

	private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();
	private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle();

	private final MemorySegment handle;
	private final MemorySegment view;
	public final boolean created;
	private boolean closed;

	private OverlayLink(MemorySegment handle, MemorySegment view, boolean created) {
		this.handle = handle;
		this.view = view;
		this.created = created;
	}

	public static OverlayLink open(String name) {
		Win32.Created c = Win32.createFileMapping(name, MAPPING_BYTES);
		MemorySegment view = Win32.mapView(c.handle(), MAPPING_BYTES);
		OverlayLink o = new OverlayLink(c.handle(), view, !c.alreadyExisted());
		if (o.created) { // fresh pages are zero; LATEST = READING = none
			view.set(ValueLayout.JAVA_INT, H_VERSION, VERSION);
			INT.setRelease(view, H_CTL, NONE | (NONE << 8));
			INT.setRelease(view, H_MAGIC, MAGIC);
		}
		return o;
	}

	public static OverlayLink openDefault() { return open(MAPPING_NAME); }

	public MemorySegment segment() { return view; }

	/** The size the host wants us to render at (0 until the host has drawn a frame). */
	public int hostViewportW() { return (int) INT.getAcquire(view, H_VIEWPORT_W); }
	public int hostViewportH() { return (int) INT.getAcquire(view, H_VIEWPORT_H); }
	public long framesShown() { return (long) LONG.getOpaque(view, H_FRAMES_SHOWN); }
	public long framesPublished() { return (long) LONG.getOpaque(view, H_FRAMES_PUBLISHED); }

	/** A slot that is neither LATEST nor being READ by the host. */
	public int pickWriteSlot() {
		int ctl = (int) INT.getAcquire(view, H_CTL);
		int latest = ctl & 3, reading = (ctl >>> 8) & 3;
		for (int s = 0; s < SLOTS; s++) if (s != latest && s != reading) return s;
		return NONE;
	}

	public static long pixelsOffset(int slot) { return PIXELS_OFF + slot * SLOT_BYTES; }

	/** Write the slot header, then make it LATEST. Pixels must already be in place. */
	public void publish(int slot, int width, int height, int flags, long frameId) {
		long s = H_SLOTS + slot * SLOT_HEADER_SIZE;
		view.set(ValueLayout.JAVA_INT, s + S_WIDTH, width);
		view.set(ValueLayout.JAVA_INT, s + S_HEIGHT, height);
		view.set(ValueLayout.JAVA_INT, s + S_FLAGS, flags);
		view.set(ValueLayout.JAVA_LONG, s + S_FRAME_ID, frameId);
		int cur;
		do {
			cur = (int) INT.getVolatile(view, H_CTL);
		} while (!INT.compareAndSet(view, H_CTL, cur, (cur & ~3) | slot));
		LONG.getAndAdd(view, H_FRAMES_PUBLISHED, 1L);
	}

	/**
	 * Publish a fully transparent frame, so the host shows nothing of Minecraft until the next real one. Without it,
	 * the last frame stayed in the mapping: a Hello Neighbor started later drew it (a frozen "Loading terrain").
	 */
	public void publishBlank(int width, int height) {
		int slot = pickWriteSlot();
		if (slot == NONE || width <= 0 || height <= 0) return;
		view.asSlice(pixelsOffset(slot), (long) width * height * 4).fill((byte) 0);
		publish(slot, width, height, 0, System.nanoTime());
	}

	/** Tell the host whether a Minecraft screen wants keyboard + cursor, and the GUI scale. Render thread. */
	public void setMcState(int flags, int guiScale) {
		view.set(ValueLayout.JAVA_INT, H_GUI_SCALE, guiScale);
		INT.setRelease(view, H_MC_FLAGS, flags);
	}

	/** Hello Neighbor's TAP_USE / TAP_APPLY / TAP_THROW input: presses so far and whether Minecraft's key is held now. */
	public void setHostAction(int kind, int presses, boolean held) {
		INT.setRelease(view, H_HOST_TAPS + 4L * kind, (presses << 1) | (held ? 1 : 0));
	}

	/** Ask the host to tap Hello Neighbor's inventory slot key 1..4 once. */
	public void tapSlot(int slot) {
		long o = H_HOST_TAPS + 4L * TAP_SLOT;
		int cur;
		do {
			cur = (int) INT.getVolatile(view, o);
		} while (!INT.compareAndSet(view, o, cur, ((cur + 1) & 0xFFFFFF) | (slot << 24)));
	}

	/** Pops one input event into e = {type, code, a, b, c}. Returns false if the ring is empty. Single consumer. */
	public boolean popInput(int[] e) {
		long t = (long) LONG.getOpaque(view, IN_RING_OFF + 64);
		long h = (long) LONG.getAcquire(view, IN_RING_OFF);
		if (t == h) return false;
		long o = IN_RING_OFF + 128 + (t & (IN_SLOTS - 1)) * IN_EVENT_SIZE;
		e[0] = Short.toUnsignedInt(view.get(ValueLayout.JAVA_SHORT, o));
		e[1] = Short.toUnsignedInt(view.get(ValueLayout.JAVA_SHORT, o + 2));
		e[2] = view.get(ValueLayout.JAVA_INT, o + 4);
		e[3] = view.get(ValueLayout.JAVA_INT, o + 8);
		e[4] = view.get(ValueLayout.JAVA_INT, o + 12);
		LONG.setRelease(view, IN_RING_OFF + 64, t + 1);
		return true;
	}

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		Win32.unmapView(view);
		Win32.closeHandle(handle);
	}
}
