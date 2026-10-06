package dev.hnmc.client;

import dev.hnmc.HnMc;
import dev.hnmc.link.McLink;
import dev.hnmc.link.WorldLink;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;

public class HnMcClient implements ClientModInitializer {
	private static final int LOG_EVERY_TICKS = 20; // once per second

	private McLink link;
	private HnWorld world;
	private HnBlocks blocks;
	private HnWorldMesh mesh;
	private net.minecraft.client.multiplayer.ClientLevel meshLevel;
	private static final long QUIT_AFTER_MS = 30_000;
	/** Play.bat creates this file in the game folder once Hello Neighbor has exited: close at once. */
	private static final java.nio.file.Path QUIT_FLAG = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("hnmc-quit.flag");
	private boolean hostWasAlive;
	private long lostAtMs;   // when the heartbeat was lost (0: it is there, or was never there)

	@Override
	public void onInitializeClient() {
		try {
			link = McLink.openDefault();
		} catch (Throwable t) {
			// Never take Minecraft down because the link failed (e.g. not Windows).
			HnMc.LOGGER.error("Could not open the shared-memory link; running without Hello Neighbor", t);
			return;
		}
		HnMc.LOGGER.info("Shared-memory link open (created mapping: {})", link.createdMapping());
		if (HnMc.RELEASE) {
			try { java.nio.file.Files.deleteIfExists(QUIT_FLAG); } catch (java.io.IOException ignored) { }   // left from a last run
		}
		world = new HnWorld(link);
		blocks = new HnBlocks(link);
		link.setBlockHandler(blocks); // block commands now change the real Minecraft world
		HnOverlay.init();
		HnNeighbor.register();
		HnInteract.register();
		HnHeld.link = link;
		try {
			WorldLink wl = WorldLink.openDefault();
			mesh = new HnWorldMesh(wl, blocks);
			HnTextures.init(wl);
			HnEntities.init(wl);
			HnMc.LOGGER.info("World channel open (created: {})", wl.created);
		} catch (Throwable t) {
			HnMc.LOGGER.error("Could not open the world channel; blocks will not be drawn in Hello Neighbor", t);
		}

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> link.close());
	}

	private void onTick(Minecraft mc) {
		try {
			// The hidden Minecraft must keep ticking while Hello Neighbor has focus: a paused singleplayer game freezes
			// the integrated server, and the player stops following. (Set here, not at init: options may not exist yet.)
			mc.options.pauseOnLostFocus = false;
			if (link.hostAlive(2000) && mc.gui.screen() instanceof PauseScreen) {
				mc.gui.setScreen(null); // Esc in the Minecraft window would otherwise pause the link
				HnMc.LOGGER.info("Closed the pause screen: Hello Neighbor is connected");
			}
			link.poll();
			world.tick(mc);   // moves the real MC player and decides what to publish
			HnSurface.tick(link); // the Hello Neighbor wall/floor under the crosshair
			HnNeighbor.tick(mc, link); // the neighbour as a Minecraft target
			HnInteract.tick(mc, link); // Hello Neighbor's own interactions (doors, pick-ups, its items in the hand)
			int changed = blocks.flush();   // block results from the server thread -> event ring
			if (mesh != null) {
				if (changed > 0 || mc.level != meshLevel) mesh.markDirty();
				meshLevel = mc.level;
				try {
					mesh.tick(mc);   // textured block mesh + atlas for Hello Neighbor
				} catch (Throwable t) {
					HnMc.LOGGER.error("World mesh failed; drawing in Hello Neighbor stopped", t);
					mesh = null;
				}
			}
			HnOverlay.tick(mc, link);   // overlay mode on/off, window hidden + sized to Hello Neighbor
			link.publish();
		} catch (Throwable t) {
			HnMc.LOGGER.error("hnmc tick failed", t);
			return;
		}

		boolean alive = link.hostAlive(2000);
		if (alive != hostWasAlive) {
			hostWasAlive = alive;
			HnMc.LOGGER.info(alive ? "Hello Neighbor connected" : "Hello Neighbor disconnected (heartbeat lost)");
			lostAtMs = alive ? 0 : System.currentTimeMillis();
		}
		// The released game starts this hidden Minecraft itself: close it with Hello Neighbor. Play.bat says so with
		// the flag file the moment the game exits; without it (Play.bat's window was closed), once the heartbeat has
		// been gone for a while (a level load can stall it for a few seconds).
		if (HnMc.RELEASE && link.ticks() % 10 == 0) {
			boolean flag = java.nio.file.Files.exists(QUIT_FLAG);
			if (flag || (lostAtMs != 0 && System.currentTimeMillis() - lostAtMs > QUIT_AFTER_MS)) {
				HnMc.LOGGER.info("Hello Neighbor is gone ({}): closing Minecraft", flag ? "Play.bat" : "no heartbeat");
				try { java.nio.file.Files.deleteIfExists(QUIT_FLAG); } catch (java.io.IOException ignored) { }
				lostAtMs = 0;
				mc.stop();
			}
		}
		if (HnMc.VERBOSE && alive && link.ticks() % LOG_EVERY_TICKS == 0 && link.host.valid) {
			McLink.HostSnapshot h = link.host;
			HnMc.LOGGER.info("host feet ({}, {}, {}) yaw {} -> target ({}, {}, {}); MC player ({}, {}, {}); inGame={} world={}",
				f(h.posX, 1), f(h.posY, 1), f(h.posZ, 1), f(h.yaw, 1),
				f(link.targetX(), 2), f(link.targetY(), 2), f(link.targetZ(), 2),
				f(link.x(), 2), f(link.y(), 2), f(link.z(), 2),
				link.hostInGame(), mc.level != null);
		}
	}

	private static String f(double v, int digits) { return String.format("%." + digits + "f", v); }
}
