package dev.hnmc.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Two views of one mapping in one JVM, like two processes. */
class SharedMemoryTest {
	private static String name() { return "Local\\HelloNeighborMC_javatest_" + System.nanoTime(); }

	@Test
	void secondOpenSeesFirstsMagic() {
		String n = name();
		try (SharedMemory a = SharedMemory.open(n); SharedMemory b = SharedMemory.open(n)) {
			assertTrue(a.created);
			assertFalse(b.created);
			assertTrue(b.ready());
		}
	}

	@Test
	void seqlockNeverReturnsTornReads() throws Exception {
		String n = name();
		try (SharedMemory w = SharedMemory.open(n); SharedMemory r = SharedMemory.open(n)) {
			final long o = Layout.OFF_MC_STATE;
			final int writes = 200_000;
			AtomicBoolean stop = new AtomicBoolean();
			AtomicLong torn = new AtomicLong(), stable = new AtomicLong();

			Thread writer = new Thread(() -> {
				for (int i = 1; i <= writes; i++) {
					w.beginWrite(o);
					w.putDouble(o + Layout.MS_X, i);
					w.putDouble(o + Layout.MS_Y, i);
					w.putDouble(o + Layout.MS_Z, i);
					w.putLong(o + Layout.MS_TICK_COUNTER, i);
					w.endWrite(o);
				}
				stop.set(true);
			});
			Thread reader = new Thread(() -> {
				while (!stop.get()) {
					int s = r.readBegin(o);
					if (s < 0) continue;
					double x = r.getDouble(o + Layout.MS_X), y = r.getDouble(o + Layout.MS_Y), z = r.getDouble(o + Layout.MS_Z);
					long t = r.getLong(o + Layout.MS_TICK_COUNTER);
					if (!r.readValid(o, s)) continue;
					stable.incrementAndGet();
					if (x != y || y != z || (long) x != t) torn.incrementAndGet();
				}
			});
			writer.start(); reader.start(); writer.join(); reader.join();
			System.out.println("seqlock: " + stable + " stable reads, " + torn + " torn");
			assertEquals(0, torn.get());
			assertTrue(stable.get() > 0);
		}
	}

	@Test
	void ringDeliversInOrderAndHonoursCapacity() throws Exception {
		String n = name();
		try (SharedMemory a = SharedMemory.open(n); SharedMemory b = SharedMemory.open(n)) {
			SharedMemory.Ring prod = a.ring(Layout.OFF_CMD_RING, Layout.CMD_RING_SLOTS);
			SharedMemory.Ring cons = b.ring(Layout.OFF_CMD_RING, Layout.CMD_RING_SLOTS);
			final int total = 200_000;
			AtomicLong bad = new AtomicLong(), got = new AtomicLong();

			Thread p = new Thread(() -> {
				int[] m = new int[7];
				for (int i = 0; i < total; i++) {
					m[0] = Layout.CMD_BREAK_BLOCK; m[2] = i; m[6] = i;
					while (!prod.push(m)) Thread.onSpinWait();
				}
			});
			Thread c = new Thread(() -> {
				int[] m = new int[7];
				int expect = 0;
				while (got.get() < total) {
					if (!cons.pop(m)) { Thread.onSpinWait(); continue; }
					if (m[2] != expect || m[0] != Layout.CMD_BREAK_BLOCK) bad.incrementAndGet();
					expect++;
					got.incrementAndGet();
				}
			});
			p.start(); c.start(); p.join(); c.join();
			System.out.println("ring: " + got + " messages, " + bad + " bad");
			assertEquals(total, got.get());
			assertEquals(0, bad.get());

			// capacity: an empty event ring accepts exactly EVENT_RING_SLOTS messages
			SharedMemory.Ring ev = a.ring(Layout.OFF_EVENT_RING, Layout.EVENT_RING_SLOTS);
			int[] m = new int[7];
			int pushed = 0;
			while (ev.push(m)) pushed++;
			assertEquals(Layout.EVENT_RING_SLOTS, pushed);
			assertFalse(b.ring(Layout.OFF_CMD_RING, Layout.CMD_RING_SLOTS).pop(m), "cmd ring drained");
		}
	}
}
