package dev.hnmc.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.hnmc.HnItems;
import dev.hnmc.HnMc;
import dev.hnmc.link.Layout;
import dev.hnmc.link.McLink;
import dev.hnmc.link.OverlayLink;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Hello Neighbor's own interactions (open doors, press switches, pick things up, use and throw them) while Minecraft
 * moves the player. Its pick-up/use code is native and not callable, so Minecraft asks hn_gfx to press Hello
 * Neighbor's own keys: the game then does exactly what it does for its own player, aimed by its own camera, which
 * sits at Minecraft's eye. hn_gfx presses whatever key the player bound to that action in Hello Neighbor's settings
 * (HnLink reads them into hn_keys.txt), and holds it as long as the Minecraft key is held (picking up is a hold).
 *
 * Bindings (Options > Controls > Hello Neighbor): R = pick up / interact, G = use the held item, X = throw it.
 * Right-click on something Hello Neighbor can interact with (HnSurface.interactableTarget) does the same as R, held
 * as long as the button is; sneak + right-click places a block there as usual.
 * Use and throw only act while a Hello Neighbor item (HnItems) is in the Minecraft hand. Which one is in the hand
 * goes to HnLink as kEvHnSelect, so Hello Neighbor takes that item out, or puts its item away for a Minecraft one.
 */
public final class HnInteract {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(HnMc.MOD_ID, "hello_neighbor"));
	private static final String[] NAMES = { "pick up / interact", "use", "throw" };
	private static final KeyMapping[] KEYS = new KeyMapping[3];   // index = OverlayLink.TAP_USE / TAP_APPLY / TAP_THROW
	private static final int[] presses = new int[3];
	private static final boolean[] wasHeld = new boolean[3];
	private static int selectedId = -1, ticks;
	/** Right-click on a Hello Neighbor interactable: holds its pick-up/interact key while the use key is held. */
	private static boolean rightHold;

	private HnInteract() {}

	static void register() {
		KEYS[OverlayLink.TAP_USE] = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.hnmc.hn_use", InputConstants.KEY_R, CATEGORY));
		KEYS[OverlayLink.TAP_APPLY] = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.hnmc.hn_apply", InputConstants.KEY_G, CATEGORY));
		KEYS[OverlayLink.TAP_THROW] = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.hnmc.hn_throw", InputConstants.KEY_X, CATEGORY));
	}

	/** Mixin (Minecraft.startUseItem): a right-click that goes to Hello Neighbor. True = Minecraft must not use it. */
	public static boolean rightClick(Minecraft mc) {
		if (rightHold) return true;   // still held: Minecraft's repeat-use must not place behind it
		if (!HnOverlay.active() || mc.player == null || mc.player.isShiftKeyDown() || !HnSurface.interactableTarget(mc)) return false;
		rightHold = true;
		presses[OverlayLink.TAP_USE]++;
		HnMc.LOGGER.info("Right-click: Hello Neighbor interact");
		return true;
	}

	/** Client tick: which item is in the hand, and each action's press count and held state. */
	static void tick(Minecraft mc, McLink link) {
		if (KEYS[0] == null) return;
		int id = mc.player == null ? 0 : HnItems.idOf(mc.player.getMainHandItem());
		if (id != selectedId || ++ticks % 40 == 0) {
			if (id != selectedId) HnMc.LOGGER.info("Minecraft hand: {}", id == 0 ? "a Minecraft item (Hello Neighbor's item put away)" : "Hello Neighbor item " + id);
			selectedId = id;
			link.pushEvent(Layout.EV_HN_SELECT, id, 0, 0, 0);
		}
		for (int k = 0; k < 3; k++) {
			boolean gated = k != OverlayLink.TAP_USE && id == 0;   // use / throw need a Hello Neighbor item in hand
			while (KEYS[k].consumeClick()) {
				if (gated) HnMc.LOGGER.info("Hello Neighbor {} ignored: no Hello Neighbor item in the hand", NAMES[k]);
				else presses[k]++;   // counts presses shorter than a tick too
			}
			if (k == OverlayLink.TAP_USE && rightHold && !mc.options.keyUse.isDown()) rightHold = false;
			boolean held = (KEYS[k].isDown() || (k == OverlayLink.TAP_USE && rightHold)) && mc.gui.screen() == null && !gated;
			boolean sent = HnOverlay.hostAction(k, presses[k], held);
			if (held != wasHeld[k]) {
				wasHeld[k] = held;
				HnMc.LOGGER.info("Hello Neighbor {} {}{}", NAMES[k], held ? "pressed" : "released", sent ? "" : " (not in Hello Neighbor, ignored)");
			}
		}
	}
}
