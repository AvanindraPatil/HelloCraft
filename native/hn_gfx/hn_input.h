// hn_input.h - routes Hello Neighbor's keyboard/mouse to the hidden Minecraft (step 3 part 1).
#pragma once
#include <windows.h>
#include <cstdint>

namespace hninput {

using LogFn = void (*)(const char* fmt, ...);

// Subclass the game window. overlayBase = the overlay mapping (input ring + mcFlags live there).
bool install(HWND gameWindow, uint8_t* overlayBase, LogFn log);

// Called by the Present hook every frame (render thread).
void setMinecraftAlive(bool alive);        // false => every message passes straight to the game
void setViewport(int w, int h);            // back-buffer size: cursor range
void pumpTaps();                           // Minecraft's hostTaps -> Hello Neighbor's own key/button presses

// For drawing the cursor while a Minecraft screen is open.
bool cursorVisible();
int cursorX();
int cursorY();

}  // namespace hninput
