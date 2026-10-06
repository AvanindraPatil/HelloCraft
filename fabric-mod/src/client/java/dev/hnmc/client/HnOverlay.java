package dev.hnmc.client;

import dev.hnmc.HnMc;
import dev.hnmc.link.McLink;
import dev.hnmc.link.OverlayLink;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLVideo;

/**
 * Overlay mode: while Hello Neighbor is in a level, Minecraft stops drawing its own world (LevelRendererMixin),
 * so its main render target holds only the hand, HUD and screens on a transparent background. That image is
 * captured every frame (MinecraftMixin -> FrameExporter) and drawn over Hello Neighbor by native/hn_gfx.
 * The Minecraft window is hidden, sized to Hello Neighbor's back buffer, and told it has focus (WindowMixin).
 *
 * Run with -Dhnmc.showWindow=true to keep the Minecraft window visible while debugging.
 */
public final class HnOverlay {
	private static final boolean SHOW_WINDOW = Boolean.getBoolean("hnmc.showWindow");

	/** Read by the mixins on the render thread. */
	private static volatile boolean active;
	private static OverlayLink overlay;
	private static boolean windowHidden;
	private static int appliedW, appliedH;
	private static long nextInputLog = 1;
	private static boolean hostMenu;
	/** The player's own F1 (hide HUD) setting while Hello Neighbor's menu / a cutscene hides it for them. */
	private static boolean hudHiddenBefore;

	private HnOverlay() {}

	public static boolean active() { return active; }

	/** Hello Neighbor's own input (OverlayLink.TAP_USE/APPLY/THROW): presses so far, held now. False when not in overlay mode. */
	static boolean hostAction(int kind, int presses, boolean held) {
		if (!active || overlay == null) return false;
		overlay.setHostAction(kind, presses, held);
		return true;
	}

	static void init() {
		try {
			overlay = OverlayLink.openDefault();
			HnMc.LOGGER.info("Overlay channel open (created: {})", overlay.created);
			overlay.publishBlank(1, 1);   // whatever an earlier Minecraft left there is not shown again
		} catch (Throwable t) {
			HnMc.LOGGER.error("Could not open the overlay channel; Minecraft will not draw into Hello Neighbor", t);
		}
	}

	/** Client tick: decide whether we are in overlay mode, and keep the window hidden and sized. */
	static void tick(Minecraft mc, McLink link) {
		if (HnMc.RELEASE && !SHOW_WINDOW) setWindowHidden(mc, true);   // a release never shows it, not even while loading
		boolean now = overlay != null && mc.level != null && mc.player != null && link.hostAlive(2000) && link.hostInGame();
		if (now != active) {
			if (!now) {
				InputBridge.releaseAll(mc);  // while still active: lift held keys/buttons
				// Nothing of Minecraft stays on Hello Neighbor's screen (this one, or one started later).
				if (overlay != null) overlay.publishBlank(Math.min(Math.max(appliedW, 1), OverlayLink.MAX_W), Math.min(Math.max(appliedH, 1), OverlayLink.MAX_H));
			}
			active = now;
			HnMc.LOGGER.info("Overlay mode {}", now ? "ON: drawing hand + HUD into Hello Neighbor, taking its input" : "OFF");
			if (!SHOW_WINDOW) setWindowHidden(mc, now || HnMc.RELEASE);
			// Grabbed = in-game mouse mode, so the first click attacks/mines instead of "grabbing" the cursor.
			// The native grab is cancelled (InputConstantsMixin): Hello Neighbor keeps the real mouse.
			if (now && mc.gui.screen() == null) mc.mouseHandler.grabMouse();
		}
		if (!active) return;
		boolean menu = link.hostMenuOpen();
		if (menu != hostMenu) {
			hostMenu = menu;
			// Hello Neighbor's menu or a cutscene: no Minecraft hand / hotbar / hearts over it (F1's hide-HUD). Back as
			// the player had it afterwards.
			if (menu) {
				hudHiddenBefore = mc.gui.hud.isHidden();
				if (!hudHiddenBefore) mc.gui.hud.toggle();
			} else if (mc.gui.hud.isHidden() != hudHiddenBefore) {
				mc.gui.hud.toggle();
			}
			if (menu) InputBridge.releaseAll(mc);   // nothing stays "held" while Hello Neighbor's menu has the input
			HnMc.LOGGER.info("Hello Neighbor menu {}", menu ? "open: all input goes to it" : "closed");
		}
		int w = Math.min(overlay.hostViewportW(), OverlayLink.MAX_W), h = Math.min(overlay.hostViewportH(), OverlayLink.MAX_H);
		if (w > 0 && h > 0 && (w != appliedW || h != appliedH)) {
			appliedW = w;
			appliedH = h;
			mc.getWindow().setWindowed(w, h);
			HnMc.LOGGER.info("Sized Minecraft to Hello Neighbor's back buffer {}x{}", w, h);
		}
	}

	/** Render thread, start of every frame: replay Hello Neighbor's input and tell it whether a screen is open. */
	public static void beginFrame(Minecraft mc) {
		if (!active || overlay == null) return;
		try {
			InputBridge.drain(mc, overlay);
			int flags = mc.gui.screen() != null ? OverlayLink.MC_SCREEN_OPEN : 0;
			if (HnWorld.mcDriving()) {
				flags |= OverlayLink.MC_MOVE_KEYS;   // every key except Esc and the mod's keys now goes to Minecraft
				HnWorld.publishFrame(mc);
			}
			if (hostMenu) flags = OverlayLink.HOST_MENU;   // Hello Neighbor's pause menu: it gets everything
			overlay.setMcState(flags, mc.getWindow().getGuiScale());
			if (mc.gui.screen() == null && !mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.grabMouse(); // screen closed
			long n = InputBridge.eventCount();
			if (n > 0 && n >= nextInputLog) {
				nextInputLog = n * 2;
				HnMc.LOGGER.info("Input from Hello Neighbor: {} events so far", n);
			}
		} catch (Throwable t) {
			HnMc.LOGGER.error("Input replay failed", t);
		}
	}

	/** Render thread, right after GameRenderer.render(). */
	public static void afterRender(Minecraft mc) {
		if (!active) return;
		try {
			HnEntities.frame(mc);   // the player model (F5), mobs, items, chests..., particles
			HnHeld.push();          // where the held Hello Neighbor item is this frame
			FrameExporter.capture(mc, overlay);
		} catch (Throwable t) {
			HnMc.LOGGER.error("Overlay capture failed; overlay mode off", t);
			active = false;
		}
	}

	private static void setWindowHidden(Minecraft mc, boolean hide) {
		if (hide == windowHidden) return;
		windowHidden = hide;
		if (hide) SDLVideo.SDL_HideWindow(mc.getWindow().handle());
		else SDLVideo.SDL_ShowWindow(mc.getWindow().handle());
		HnMc.LOGGER.info("Minecraft window {}", hide ? "hidden" : "shown");
	}
}
