package dev.hnmc.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.hnmc.HnMc;
import dev.hnmc.link.OverlayLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.sdl.SDLKeyboard;

/**
 * Replays the keyboard/mouse captured in Hello Neighbor (native/hn_gfx/hn_input.cpp) into Minecraft's own input
 * handlers, as if the hidden Minecraft window had focus. Keeps a virtual keyboard so InputConstants.isKeyDown()
 * (held keys, e.g. for mining) works while the real window never has focus.
 *
 * Adapted from SkyCraft's InputBridge (https://github.com/chasmlol/SkyCraft, MIT License,
 * Copyright (c) SkyCraft contributors). Render thread only.
 */
public final class InputBridge {
	private static final boolean[] KEYS = new boolean[512];
	private static final boolean[] BUTTONS = new boolean[8];
	private static final int[] EVENT = new int[5];
	private static double cursorX, cursorY;
	private static int modifiers;
	private static long events;

	private InputBridge() {}

	public static boolean isKeyDown(int scancode) {
		return scancode >= 0 && scancode < KEYS.length && KEYS[scancode];
	}

	static long eventCount() { return events; }

	/** Replay everything Hello Neighbor sent since the last frame. */
	static void drain(Minecraft mc, OverlayLink overlay) {
		while (overlay.popInput(EVENT)) {
			events++;
			try {
				dispatch(mc, EVENT[0], EVENT[1], EVENT[2], EVENT[3]);
			} catch (Throwable t) {
				HnMc.LOGGER.error("Input event {} failed", EVENT[0], t);
			}
		}
	}

	private static void dispatch(Minecraft mc, int type, int code, int a, int b) {
		long handle = mc.getWindow().handle();
		switch (type) {
			case OverlayLink.IN_KEY -> key(mc, handle, code, a != 0);
			case OverlayLink.IN_MOUSE_BUTTON -> {
				if (code > 0 && code < BUTTONS.length) BUTTONS[code] = a != 0;
				mc.mouseHandler.onButton(handle, new MouseButtonInfo(code, modifiers), a != 0 ? 1 : 0);
			}
			case OverlayLink.IN_SCROLL -> mc.mouseHandler.onScroll(handle, 0.0, a / 120.0);
			case OverlayLink.IN_CURSOR -> {
				// Host sends back-buffer pixels; Minecraft's window is sized to the same back buffer.
				double dx = a - cursorX, dy = b - cursorY;
				cursorX = a;
				cursorY = b;
				mc.mouseHandler.onMove(handle, a, b, dx, dy);
			}
			case OverlayLink.IN_TEXT -> {
				if (mc.gui.screen() != null) mc.keyboardHandler.textInput(handle, new String(Character.toChars(a)));
			}
			case OverlayLink.IN_RELEASE_ALL -> releaseAll(mc);
			default -> { }
		}
	}

	private static void key(Minecraft mc, long handle, int scancode, boolean down) {
		if (scancode <= 0 || scancode >= KEYS.length) return;
		boolean wasDown = KEYS[scancode];
		KEYS[scancode] = down;
		updateModifiers();
		int action = down ? (wasDown ? InputConstants.REPEAT : InputConstants.PRESS) : InputConstants.RELEASE;
		int keycode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) modifiers, true);
		mc.keyboardHandler.keyPress(handle, action, new KeyEvent(scancode, keycode, modifiers));
	}

	private static void updateModifiers() {
		int m = 0;
		if (KEYS[225]) m |= 0x0001; // SDL_KMOD_LSHIFT
		if (KEYS[229]) m |= 0x0002; // SDL_KMOD_RSHIFT
		if (KEYS[224]) m |= 0x0040; // SDL_KMOD_LCTRL
		if (KEYS[228]) m |= 0x0080; // SDL_KMOD_RCTRL
		if (KEYS[226]) m |= 0x0100; // SDL_KMOD_LALT
		if (KEYS[230]) m |= 0x0200; // SDL_KMOD_RALT
		modifiers = m;
	}

	/** Lift every key and button we think is held (Hello Neighbor lost focus, link dropped, ...). */
	static void releaseAll(Minecraft mc) {
		long handle = mc.getWindow().handle();
		for (int sc = 0; sc < KEYS.length; sc++) {
			if (KEYS[sc]) {
				KEYS[sc] = false;
				updateModifiers();
				mc.keyboardHandler.keyPress(handle, 0, new KeyEvent(sc, SDLKeyboard.SDL_GetKeyFromScancode(sc, (short) 0, true), modifiers));
			}
		}
		for (int button = 1; button < BUTTONS.length; button++) {
			if (BUTTONS[button]) {
				BUTTONS[button] = false;
				mc.mouseHandler.onButton(handle, new MouseButtonInfo(button, 0), 0);
			}
		}
	}
}
