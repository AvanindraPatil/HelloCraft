package dev.hnmc.link;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Block commands go to the handler (not echoed) when one is set, and pushEvent reaches the host. */
class McLinkBlocksTest {
	@Test
	void commandsReachHandlerAndEventsReachHost() {
		String name = "Local\\HelloNeighborMC_blocks_" + System.nanoTime();
		try (McLink link = new McLink(name); SharedMemory host = SharedMemory.open(name)) {
			List<String> calls = new ArrayList<>();
			link.setBlockHandler(new McLink.BlockHandler() {
				@Override public void breakBlock(int x, int y, int z) { calls.add("break " + x + " " + y + " " + z); }
				@Override public void placeBlock(int x, int y, int z, int id) { calls.add("place " + x + " " + y + " " + z + " " + id); }
				@Override public void resync() { calls.add("resync"); }
			});

			SharedMemory.Ring cmds = host.ring(Layout.OFF_CMD_RING, Layout.CMD_RING_SLOTS);
			cmds.push(new int[] { Layout.CMD_PLACE_BLOCK, 0, -87, 1, -34, Layout.BLOCK_BRICKS, 1 });
			cmds.push(new int[] { Layout.CMD_BREAK_BLOCK, 0, -87, 1, -34, 0, 2 });
			cmds.push(new int[] { Layout.CMD_RESYNC, 0, 0, 0, 0, 0, 3 });
			link.poll();

			assertEquals(List.of("place -87 1 -34 5", "break -87 1 -34", "resync"), calls);
			SharedMemory.Ring events = host.ring(Layout.OFF_EVENT_RING, Layout.EVENT_RING_SLOTS);
			int[] m = new int[7];
			assertFalse(events.pop(m), "with a handler set, nothing is echoed");

			assertTrue(link.pushEvent(Layout.EV_BLOCK_CHANGE, -87, 1, -34, Layout.BLOCK_BRICKS));
			assertTrue(events.pop(m));
			assertArrayEquals(new int[] { Layout.EV_BLOCK_CHANGE, -87, 1, -34, Layout.BLOCK_BRICKS },
				new int[] { m[0], m[2], m[3], m[4], m[5] });
		}
	}

	@Test
	void withoutHandlerPlaceIsEchoedWithItsBlockId() {
		String name = "Local\\HelloNeighborMC_echo_" + System.nanoTime();
		try (McLink link = new McLink(name); SharedMemory host = SharedMemory.open(name)) {
			host.ring(Layout.OFF_CMD_RING, Layout.CMD_RING_SLOTS).push(new int[] { Layout.CMD_PLACE_BLOCK, 0, 1, 2, 3, Layout.BLOCK_GLASS, 1 });
			link.poll();
			int[] m = new int[7];
			assertTrue(host.ring(Layout.OFF_EVENT_RING, Layout.EVENT_RING_SLOTS).pop(m));
			assertEquals(Layout.BLOCK_GLASS, m[5]);
		}
	}
}
