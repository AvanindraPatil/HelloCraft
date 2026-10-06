// harness.cpp - tests hn_gfx.dll outside the game.
// 1. Creates the "game" D3D11 device + swap chain (B8G8R8A8, flip model, like UE4) on a window of class
//    "harness"; env HN_GFX_WINDOW_CLASS tells hn_gfx this is the game window, HN_GFX_OVERLAY_NAME gives it a
//    private overlay mapping (so a running Minecraft is not disturbed).
// 2. A NOISE thread presents uncapped on a second window (class "noise"), like UE4SS's GUI window in the game.
// 3. A CHECKER sits in the Present slot underneath hn_gfx's hook and reads back pixels after hn_gfx has drawn.
// 4. The harness plays Minecraft: before each Present it publishes an overlay frame at the size hn_gfx asks
//    for (hostViewport), stored bottom-up, transparent except a half-transparent red square in the UPPER half.
// Expected on the game window: the square blended over the blue background (premultiplied alpha), the lower
// half untouched (orientation), no leftover test pattern; noise window untouched. Includes a resize.
#include <windows.h>
#include <d3d11.h>
#include <atomic>
#include <cstdio>
#include <thread>

#include "../../../protocol/hn_overlay.h"

#pragma comment(lib, "d3d11.lib")

using PresentFn = HRESULT(STDMETHODCALLTYPE*)(IDXGISwapChain*, UINT, UINT);
static PresentFn realPresent;
static IDXGISwapChain* gameSc;
static std::atomic<int> gameChecked{0}, gameOk{0}, noiseChecked{0}, noiseClean{0};
static bool expectOverlay = false;   // set once frames are being published
static HWND gameHwnd;
static int gotKeyDown, gotKeyUp, gotChar, gotWheel, gotButton;   // messages that reached the game
static LRESULT CALLBACK gameProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    switch (m) {
    case WM_KEYDOWN: ++gotKeyDown; return 0;
    case WM_KEYUP: ++gotKeyUp; return 0;
    case WM_CHAR: ++gotChar; return 0;
    case WM_MOUSEWHEEL: ++gotWheel; return 0;
    case WM_LBUTTONDOWN: case WM_LBUTTONUP: ++gotButton; return 0;
    }
    return DefWindowProcA(h, m, w, l);
}

// Square in top-left image coordinates (rows counted from the top).
static const int SQ_X0 = 100, SQ_X1 = 200, SQ_Y0 = 50, SQ_Y1 = 150;

static unsigned pixel(ID3D11DeviceContext* ctx, ID3D11Texture2D* staging, int x, int y) {
    D3D11_MAPPED_SUBRESOURCE m;
    if (FAILED(ctx->Map(staging, 0, D3D11_MAP_READ, 0, &m))) return 0;
    unsigned v = *reinterpret_cast<unsigned*>(static_cast<char*>(m.pData) + y * m.RowPitch + x * 4);
    ctx->Unmap(staging, 0);
    return v;  // B8G8R8A8: 0xAARRGGBB
}
static int R(unsigned v) { return (v >> 16) & 0xFF; }
static int G(unsigned v) { return (v >> 8) & 0xFF; }
static int B(unsigned v) { return v & 0xFF; }

static HRESULT STDMETHODCALLTYPE checker(IDXGISwapChain* sc, UINT sync, UINT flags) {
    ID3D11Device* dev; ID3D11DeviceContext* ctx;
    sc->GetDevice(__uuidof(ID3D11Device), (void**)&dev); dev->GetImmediateContext(&ctx);
    ID3D11Texture2D* back = nullptr;
    sc->GetBuffer(0, __uuidof(ID3D11Texture2D), (void**)&back);
    D3D11_TEXTURE2D_DESC d; back->GetDesc(&d);
    d.Usage = D3D11_USAGE_STAGING; d.BindFlags = 0; d.CPUAccessFlags = D3D11_CPU_ACCESS_READ; d.MiscFlags = 0;
    ID3D11Texture2D* staging = nullptr;
    dev->CreateTexture2D(&d, nullptr, &staging);
    ctx->CopyResource(staging, back);
    unsigned sq = pixel(ctx, staging, 150, 100);                    // inside the square (upper half)
    unsigned mirror = pixel(ctx, staging, 150, d.Height - 100);     // where the square would be if flipped
    unsigned corner = pixel(ctx, staging, 50, 50);                  // old test pattern spot
    if (sc == gameSc) {
        // red 0.5 premultiplied over blue 0.5: R ~ 0x80, B ~ 0x3F; elsewhere plain blue 0x7F
        bool sqOk = R(sq) >= 0x78 && R(sq) <= 0x88 && G(sq) < 0x08 && B(sq) >= 0x38 && B(sq) <= 0x48;
        bool mirrorOk = R(mirror) < 0x08 && B(mirror) >= 0x78;
        bool cornerOk = R(corner) < 0x08 && B(corner) >= 0x78;
        bool ok = expectOverlay ? (sqOk && mirrorOk && cornerOk) : (mirrorOk && cornerOk);
        int n = ++gameChecked; if (ok) ++gameOk;
        if (n <= 3 || n == 40 || !ok)
            std::printf("game frame %d (%ux%u): square=%08X mirror=%08X corner=%08X -> %s\n", n, d.Width, d.Height, sq, mirror, corner, ok ? "ok" : "WRONG");
    } else {
        ++noiseChecked;
        if (R(sq) < 0x60 && R(corner) < 0x60) ++noiseClean;   // grey 0x4C stays grey
    }
    staging->Release(); back->Release(); ctx->Release(); dev->Release();
    return realPresent(sc, sync, flags);
}

static bool makeSwapChain(const char* cls, UINT w, UINT h, IDXGISwapChain** sc, ID3D11Device** dev, ID3D11DeviceContext** ctx) {
    WNDCLASSEXA wc{ sizeof(wc) }; wc.lpfnWndProc = std::strcmp(cls, "harness") == 0 ? gameProc : DefWindowProcA; wc.hInstance = GetModuleHandleA(nullptr); wc.lpszClassName = cls;
    RegisterClassExA(&wc);
    HWND hwnd = CreateWindowExA(0, cls, cls, WS_OVERLAPPEDWINDOW, 100, 100, w, h, nullptr, nullptr, wc.hInstance, nullptr);
    if (std::strcmp(cls, "harness") == 0) gameHwnd = hwnd;
    DXGI_SWAP_CHAIN_DESC sd{}; sd.BufferCount = 2; sd.BufferDesc.Width = w; sd.BufferDesc.Height = h;
    sd.BufferDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT; sd.OutputWindow = hwnd; sd.SampleDesc.Count = 1; sd.Windowed = TRUE;
    sd.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    return SUCCEEDED(D3D11CreateDeviceAndSwapChain(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0, nullptr, 0, D3D11_SDK_VERSION, &sd, sc, dev, nullptr, ctx));
}

static void clear(IDXGISwapChain* sc, ID3D11Device* dev, ID3D11DeviceContext* ctx, const float c[4]) {
    ID3D11Texture2D* back; ID3D11RenderTargetView* rtv;
    sc->GetBuffer(0, __uuidof(ID3D11Texture2D), (void**)&back); dev->CreateRenderTargetView(back, nullptr, &rtv); back->Release();
    ctx->ClearRenderTargetView(rtv, c); rtv->Release();
}

// Play Minecraft: publish one frame at the requested size, bottom-up, premultiplied.
static void publishFrame(hn::OverlayMapping& ov, uint64_t id) {
    hn::OverlayHeader* h = ov.header();
    uint32_t w = h->hostViewportW ? h->hostViewportW : 640, hgt = h->hostViewportH ? h->hostViewportH : 360;
    uint32_t s = hn::overlayPickWriteSlot(h);
    uint8_t* px = hn::overlayPixels(ov.base, s);
    std::memset(px, 0, size_t(w) * hgt * 4);
    for (int y = SQ_Y0; y < SQ_Y1; ++y) {
        uint8_t* row = px + size_t(hgt - 1 - y) * w * 4;   // bottom-up storage
        for (int x = SQ_X0; x < SQ_X1; ++x) { row[x * 4 + 0] = 128; row[x * 4 + 1] = 0; row[x * 4 + 2] = 0; row[x * 4 + 3] = 128; }
    }
    h->slots[s].width = w; h->slots[s].height = hgt; h->slots[s].flags = hn::kOverlayFlagBottomUp; h->slots[s].frameId = id;
    hn::overlayPublish(h, s);
}

int main(int argc, char** argv) {
    const char* dll = argc > 1 ? argv[1] : "hn_gfx.dll";
    char ovName[96];
    std::snprintf(ovName, sizeof(ovName), "Local\\HelloNeighborMC_overlay_harness_%lu", GetCurrentProcessId());
    SetEnvironmentVariableA("HN_GFX_WINDOW_CLASS", "harness");
    SetEnvironmentVariableA("HN_GFX_OVERLAY_NAME", ovName);
    hn::OverlayMapping ov;
    if (!ov.open(ovName)) { std::printf("FAIL: overlay mapping\n"); return 1; }

    ID3D11Device* dev; ID3D11DeviceContext* ctx;
    if (!makeSwapChain("harness", 640, 360, &gameSc, &dev, &ctx)) { std::printf("FAIL: game device\n"); return 1; }
    void** vt = *reinterpret_cast<void***>(gameSc);
    DWORD old; VirtualProtect(&vt[8], 8, PAGE_EXECUTE_READWRITE, &old);
    realPresent = (PresentFn)vt[8]; vt[8] = (void*)&checker;
    VirtualProtect(&vt[8], 8, old, &old);

    std::atomic<bool> stop{false};
    std::thread noise([&] {
        IDXGISwapChain* nsc; ID3D11Device* ndev; ID3D11DeviceContext* nctx;
        if (!makeSwapChain("noise", 320, 240, &nsc, &ndev, &nctx)) { std::printf("FAIL: noise device\n"); return; }
        const float grey[4] = { 0.3f, 0.3f, 0.3f, 1 };
        while (!stop) { clear(nsc, ndev, nctx, grey); nsc->Present(0, 0); }
        nsc->Release(); nctx->Release(); ndev->Release();
    });

    if (!LoadLibraryA(dll)) { std::printf("FAIL: LoadLibrary(%s) error %lu\n", dll, GetLastError()); stop = true; noise.join(); return 1; }
    Sleep(1500);  // hn_gfx installs its hooks on a background thread; the noise window keeps presenting meanwhile
    if (vt[8] == (void*)&checker) { std::printf("FAIL: hn_gfx did not hook Present\n"); stop = true; noise.join(); return 1; }

    const float blue[4] = { 0, 0, 0.5f, 1 };
    // Frame 1: nothing published yet -> no overlay. Then publish every frame.
    for (int i = 0; i < 40; ++i) {
        if (i >= 1) { publishFrame(ov, (uint64_t)i); expectOverlay = true; }
        clear(gameSc, dev, ctx, blue);
        if (i == 20) {
            HRESULT hr = gameSc->ResizeBuffers(0, 800, 450, DXGI_FORMAT_UNKNOWN, 0);
            std::printf("ResizeBuffers -> 0x%08lx\n", hr);
            if (FAILED(hr)) { std::printf("FAIL: resize (hook kept a back-buffer reference?)\n"); stop = true; noise.join(); return 1; }
            clear(gameSc, dev, ctx, blue);
        }
        gameSc->Present(0, 0);
        Sleep(5);
    }
    stop = true; noise.join();
    hn::OverlayHeader* h = ov.header();
    std::printf("host viewport written: %ux%u, frames shown by hn_gfx: %llu\n", h->hostViewportW, h->hostViewportH, (unsigned long long)h->framesShown);
    std::printf("game: %d/%d frames correct; noise: %d/%d frames untouched\n", gameOk.load(), gameChecked.load(), noiseClean.load(), noiseChecked.load());
    // Allowed: the frame right after the resize shows the old-size overlay stretched (Minecraft re-renders at
    // the new size a frame later), so at most one game frame may be off.
    bool pass = gameChecked == 40 && gameOk >= 39 && noiseChecked > 100 && noiseClean == noiseChecked &&
                h->hostViewportW == 800 && h->hostViewportH == 450 && h->framesShown >= 30;

    // ---------------- input routing ----------------
    int inputFailures = 0;
    uint64_t frameId = 1000;
    auto frame = [&](bool publish) { if (publish) publishFrame(ov, frameId++); clear(gameSc, dev, ctx, blue); gameSc->Present(0, 0); };
    auto drain = [&](hn::InputEvent* out, int max) { int n = 0; hn::InputEvent e; while (hn::inputPop(ov.base, e)) if (n < max) out[n++] = e; return n; };
    auto key = [&](int scancode, bool down) {
        LPARAM lp = 1 | (LPARAM(scancode) << 16) | (down ? 0 : (LPARAM(3) << 30));
        SendMessageW(gameHwnd, down ? WM_KEYDOWN : WM_KEYUP, 0, lp);
    };
    auto expect = [&](bool cond, const char* what) { std::printf("  input: %-58s %s\n", what, cond ? "ok" : "FAIL"); if (!cond) ++inputFailures; };
    hn::InputEvent ev[32];

    for (int i = 0; i < 3; ++i) frame(true);    // Minecraft alive
    drain(ev, 32);
    gotKeyDown = gotKeyUp = gotChar = gotWheel = gotButton = 0;

    key(0x02, true); key(0x02, false);          // '1' -> Minecraft hotbar
    int n = drain(ev, 32);
    expect(n == 2 && ev[0].type == hn::kInKey && ev[0].code == 30 && ev[0].a == 1 && ev[1].code == 30 && ev[1].a == 0 && gotKeyDown == 0 && gotKeyUp == 0,
           "'1' goes to Minecraft as SDL 30, game sees nothing");
    key(0x11, true); key(0x11, false);          // 'W' -> the game
    n = drain(ev, 32);
    expect(n == 0 && gotKeyDown == 1 && gotKeyUp == 1, "'W' goes to the game only");
    h->mcFlags = hn::kOverlayMcMoveKeys;        // Minecraft drives: movement keys are Minecraft's
    int savedDown = gotKeyDown, savedUp = gotKeyUp, savedButton = gotButton;
    gotKeyDown = gotKeyUp = 0;
    key(0x11, true); key(0x39, true); key(0x11, false); key(0x39, false);   // W, Space
    n = drain(ev, 32);
    expect(n == 4 && ev[0].code == 26 && ev[1].code == 44 && ev[2].code == 26 && ev[2].a == 0 && gotKeyDown == 1 && gotKeyUp == 1,
           "Minecraft drives: W to Minecraft, Space to both");
    gotKeyDown = gotKeyUp = 0;
    key(0x21, true); key(0x21, false);          // F -> both (Hello Neighbor mods use it)
    n = drain(ev, 32);
    expect(n == 2 && ev[0].code == 9 && ev[0].a == 1 && ev[1].code == 9 && ev[1].a == 0 && gotKeyDown == 1 && gotKeyUp == 1,
           "Minecraft drives: F goes to both");
    gotKeyDown = gotKeyUp = 0;
    key(0x14, true); key(0x14, false);          // T (chat) -> Minecraft
    n = drain(ev, 32);
    expect(n == 2 && ev[0].code == 23 && gotKeyDown == 0, "Minecraft drives: T goes to Minecraft");
    key(0x01, true); key(0x01, false);          // Esc -> the game's menu
    n = drain(ev, 32);
    expect(n == 0 && gotKeyDown == 1, "Minecraft drives: Esc stays with the game");
    h->mcFlags = hn::kOverlayHostMenu;          // Hello Neighbor's pause menu: everything is the game's
    gotKeyDown = gotButton = 0;
    n = drain(ev, 32);
    key(0x02, true); key(0x02, false);          // even '1' (normally Minecraft's hotbar)
    SendMessageW(gameHwnd, WM_LBUTTONDOWN, MK_LBUTTON, 0); SendMessageW(gameHwnd, WM_LBUTTONUP, 0, 0);
    n = drain(ev, 32);
    expect(n == 1 && ev[0].type == hn::kInReleaseAll && gotKeyDown >= 1 && gotButton >= 1,
           "Hello Neighbor menu: keys + clicks go to the game (one release-all to Minecraft)");
    h->mcFlags = hn::kOverlayMcMoveKeys;
    h->mcFlags = 0;
    gotKeyDown = savedDown; gotKeyUp = savedUp; gotButton = savedButton;
    SendMessageW(gameHwnd, WM_MOUSEWHEEL, MAKEWPARAM(0, 120), 0);
    n = drain(ev, 32);
    expect(n == 1 && ev[0].type == hn::kInScroll && ev[0].a == 120 && gotWheel == 0, "wheel goes to Minecraft (+120)");
    SendMessageW(gameHwnd, WM_LBUTTONDOWN, MK_LBUTTON, 0); SendMessageW(gameHwnd, WM_LBUTTONUP, 0, 0);
    n = drain(ev, 32);
    expect(n == 2 && ev[0].type == hn::kInMouseButton && ev[0].code == 1 && ev[0].a == 1 && ev[1].a == 0 && gotButton == 0, "left click goes to Minecraft (button 1)");

    // A Minecraft screen opens (inventory): everything goes to Minecraft.
    h->mcFlags = hn::kOverlayMcScreenOpen;
    key(0x11, true);
    SendMessageW(gameHwnd, WM_CHAR, 'a', 0);
    n = drain(ev, 32);
    expect(n == 3 && ev[0].type == hn::kInCursor && ev[0].a == 400 && ev[0].b == 225 && ev[1].type == hn::kInKey && ev[1].code == 26 && ev[1].a == 1 &&
           ev[2].type == hn::kInText && ev[2].a == 'a' && gotKeyDown == 1 && gotChar == 0,
           "screen open: cursor centred, 'W' + typed text go to Minecraft");
    h->mcFlags = 0;                             // screen closes while W is still held
    key(0x11, false);
    n = drain(ev, 32);
    expect(n == 1 && ev[0].type == hn::kInKey && ev[0].code == 26 && ev[0].a == 0 && gotKeyUp == 1,
           "W released after the screen closed: key-up follows its key-down");
    SendMessageW(gameHwnd, WM_KILLFOCUS, 0, 0);
    n = drain(ev, 32);
    expect(n == 1 && ev[0].type == hn::kInReleaseAll, "focus lost: release-all sent");

    // Minecraft stops publishing frames: after a second everything goes to the game.
    ULONGLONG t0 = GetTickCount64();
    while (GetTickCount64() - t0 < 1300) { frame(false); Sleep(20); }
    key(0x02, true); key(0x02, false);
    n = drain(ev, 32);
    expect(n == 0 && gotKeyDown == 2 && gotKeyUp == 2, "Minecraft silent: '1' goes to the game");

    std::printf("input routing: %d failure(s)\n", inputFailures);
    pass = pass && inputFailures == 0;
    std::printf(pass ? "HARNESS PASSED\n" : "HARNESS FAILED\n");
    ov.close();
    return pass ? 0 : 1;
}
