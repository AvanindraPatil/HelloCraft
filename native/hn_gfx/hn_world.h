// hn_world.h - step 4: drawing in Hello Neighbor's 3D scene (camera + scene depth).
// Step 4a (this file): find Unreal's scene depth buffer, read the camera, and draw a depth-tested test cube.
#pragma once
#include <d3d11_1.h>

namespace hnworld {

using LogFn = void (*)(const char* fmt, ...);

// Once per device, on the render thread: hook the immediate context so we see which depth buffers Unreal binds.
void install(ID3D11Device* dev, ID3D11DeviceContext* ctx, const char* exeDir, LogFn log);

// Game back buffer is about to be resized / the device is going away: drop every reference we hold.
void releaseAll();

// Render thread, inside our own device context state with the back buffer bound as render target 0
// (viewport = whole back buffer, w x h). Draws whatever the world layer has this frame.
void draw(ID3D11DeviceContext1* ctx, ID3D11RenderTargetView* rtv, UINT w, UINT h);

// End of a game frame (after our drawing, game state restored): reset per-frame depth statistics, re-apply hooks.
void endFrame();

}  // namespace hnworld
