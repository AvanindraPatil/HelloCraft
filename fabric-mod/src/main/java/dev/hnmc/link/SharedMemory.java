package dev.hnmc.link;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * The named shared mapping plus the two concurrency primitives the protocol uses:
 * seqlocked latest-value slots and single-producer/single-consumer rings.
 * Mirrors protocol/hn_shm.h.
 */
public final class SharedMemory implements AutoCloseable {
	private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();
	private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle();

	private final MemorySegment handle;
	private final MemorySegment view;
	private boolean closed;
	/** True if this call created the mapping (the other side had not started yet). */
	public final boolean created;

	private SharedMemory(MemorySegment handle, MemorySegment view, boolean created) {
		this.handle = handle;
		this.view = view;
		this.created = created;
	}

	public static SharedMemory open(String name) {
		Win32.Created c = Win32.createFileMapping(name, Layout.MAPPING_BYTES);
		MemorySegment view = Win32.mapView(c.handle(), Layout.MAPPING_BYTES);
		SharedMemory m = new SharedMemory(c.handle(), view, !c.alreadyExisted());
		if (m.created) { // fresh pages are zero already
			m.putInt(Layout.H_VERSION, Layout.VERSION);
			INT.setRelease(view, Layout.H_MAGIC, Layout.MAGIC);
		}
		return m;
	}

	/** True once whichever side created the mapping has written the magic. */
	public boolean ready() { return (int) INT.getAcquire(view, Layout.H_MAGIC) == Layout.MAGIC; }

	// ---- plain accessors (offsets are absolute within the mapping) ----
	public int getInt(long off) { return view.get(ValueLayout.JAVA_INT, off); }
	public void putInt(long off, int v) { view.set(ValueLayout.JAVA_INT, off, v); }
	public long getLong(long off) { return view.get(ValueLayout.JAVA_LONG, off); }
	public void putLong(long off, long v) { view.set(ValueLayout.JAVA_LONG, off, v); }
	public double getDouble(long off) { return view.get(ValueLayout.JAVA_DOUBLE, off); }
	public void putDouble(long off, double v) { view.set(ValueLayout.JAVA_DOUBLE, off, v); }
	public float getFloat(long off) { return view.get(ValueLayout.JAVA_FLOAT, off); }
	public void putFloat(long off, float v) { view.set(ValueLayout.JAVA_FLOAT, off, v); }
	public void putShort(long off, short v) { view.set(ValueLayout.JAVA_SHORT, off, v); }
	public int getIntVolatile(long off) { return (int) INT.getVolatile(view, off); }
	public void putLongRelease(long off, long v) { LONG.setRelease(view, off, v); }

	// ---- seqlock ----
	/** Writer side: seq goes odd, caller writes fields, then {@link #endWrite}. Single writer per slot. */
	public void beginWrite(long slotOff) {
		int s = (int) INT.getOpaque(view, slotOff);
		INT.setOpaque(view, slotOff, s + 1);
		VarHandle.storeStoreFence();
	}

	public void endWrite(long slotOff) {
		int s = (int) INT.getOpaque(view, slotOff);
		INT.setRelease(view, slotOff, s + 1);
	}

	/** Reader side: returns the stable seq (even) before the reads, or -1 if the writer is mid-write. */
	public int readBegin(long slotOff) {
		int s = (int) INT.getAcquire(view, slotOff);
		return (s & 1) != 0 ? -1 : s;
	}

	/** True if no write happened since {@link #readBegin} returned {@code seq}. */
	public boolean readValid(long slotOff, int seq) {
		VarHandle.loadLoadFence();
		return (int) INT.getOpaque(view, slotOff) == seq;
	}

	// ---- SPSC ring ----
	public Ring ring(long ringOff, long slots) { return new Ring(ringOff, slots); }

	public final class Ring {
		private final long base;
		private final long slots;

		private Ring(long base, long slots) {
			this.base = base;
			this.slots = slots;
		}

		private long slotOff(long index) { return base + Layout.RING_DATA_OFF + (index & (slots - 1)) * Layout.MESSAGE_SIZE; }

		/** m = {type, flags, a, b, c, d, seq}. Returns false if the ring is full (message dropped). */
		public boolean push(int[] m) {
			long h = (long) LONG.getOpaque(view, base + Layout.RING_HEAD_OFF);
			long t = (long) LONG.getAcquire(view, base + Layout.RING_TAIL_OFF);
			if (h - t >= slots) return false;
			long o = slotOff(h);
			putInt(o + Layout.M_TYPE, m[0]);
			putInt(o + Layout.M_FLAGS, m[1]);
			putInt(o + Layout.M_A, m[2]);
			putInt(o + Layout.M_B, m[3]);
			putInt(o + Layout.M_C, m[4]);
			putInt(o + Layout.M_D, m[5]);
			putInt(o + Layout.M_SEQ, m[6]);
			LONG.setRelease(view, base + Layout.RING_HEAD_OFF, h + 1);
			return true;
		}

		/** Fills m = {type, flags, a, b, c, d, seq}. Returns false if the ring is empty. */
		public boolean pop(int[] m) {
			long t = (long) LONG.getOpaque(view, base + Layout.RING_TAIL_OFF);
			long h = (long) LONG.getAcquire(view, base + Layout.RING_HEAD_OFF);
			if (t == h) return false;
			long o = slotOff(t);
			m[0] = getInt(o + Layout.M_TYPE);
			m[1] = getInt(o + Layout.M_FLAGS);
			m[2] = getInt(o + Layout.M_A);
			m[3] = getInt(o + Layout.M_B);
			m[4] = getInt(o + Layout.M_C);
			m[5] = getInt(o + Layout.M_D);
			m[6] = getInt(o + Layout.M_SEQ);
			LONG.setRelease(view, base + Layout.RING_TAIL_OFF, t + 1);
			return true;
		}
	}

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		Win32.unmapView(view);
		Win32.closeHandle(handle);
	}
}
