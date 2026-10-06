// hn_gfx.cpp - native graphics plugin for Hello Neighbor.
//
// Loaded by UE4SS as a "C++ mod" (Mods/HnGfx/dlls/main.dll). We do not use UE4SS's C++ mod API (that needs
// UE4SS built from source); UE4SS just LoadLibrary()s us, DllMain runs, and we pin ourselves so a later
// FreeLibrary (UE4SS finds no start_mod export) cannot unload us.
//
// Hooks IDXGISwapChain::Present (vtable 8) and ResizeBuffers (13) by patching the swap chain vtable (found via a
// throwaway device + swap chain; all DXGI swap chains share it). Only the GAME's swap chain (window class
// UnrealWindow) is touched; UE4SS's own window etc. pass straight through.
//
// Step 2: draws Minecraft's overlay (hand + HUD + screens, premultiplied RGBA8, see protocol/hn_overlay.h) over
// the game's back buffer just before Present. All drawing happens inside our own ID3DDeviceContextState, swapped
// in and out, so Unreal's pipeline state (which its RHI caches) is untouched.
//
// Logs to hn_gfx.log next to the game exe. Kill switch: empty file "hn_gfx.disable" next to the exe.
// Test overrides (env): HN_GFX_WINDOW_CLASS, HN_GFX_OVERLAY_NAME.
#include <windows.h>
#include <d3d11_1.h>
#include <d3dcompiler.h>
#include <dxgi.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <intrin.h>

#include "../../protocol/hn_overlay.h"
#include "hn_input.h"
#include "hn_world.h"

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "d3dcompiler.lib")

namespace {

// ---------------- logging ----------------
FILE* g_log = nullptr;
CRITICAL_SECTION g_logLock;

void logf(const char* fmt, ...) {
    if (!g_log) return;
    EnterCriticalSection(&g_logLock);
    SYSTEMTIME t; GetLocalTime(&t);
    std::fprintf(g_log, "[%02d:%02d:%02d.%03d] ", t.wHour, t.wMinute, t.wSecond, t.wMilliseconds);
    va_list ap; va_start(ap, fmt); std::vfprintf(g_log, fmt, ap); va_end(ap);
    std::fputc('\n', g_log);
    std::fflush(g_log);
    LeaveCriticalSection(&g_logLock);
}

char g_exeDir[MAX_PATH];

void initPaths() {
    GetModuleFileNameA(nullptr, g_exeDir, MAX_PATH);
    char* slash = std::strrchr(g_exeDir, '\\');
    if (slash) slash[1] = 0;
}

bool killSwitch() {
    char p[MAX_PATH];
    std::snprintf(p, sizeof(p), "%shn_gfx.disable", g_exeDir);
    return GetFileAttributesA(p) != INVALID_FILE_ATTRIBUTES;
}

template <class T> void safeRelease(T*& p) { if (p) { p->Release(); p = nullptr; } }

// ---------------- hooks: which swap chain is the game's ----------------
using PresentFn = HRESULT(STDMETHODCALLTYPE*)(IDXGISwapChain*, UINT, UINT);
using ResizeFn = HRESULT(STDMETHODCALLTYPE*)(IDXGISwapChain*, UINT, UINT, UINT, DXGI_FORMAT, UINT);
PresentFn g_origPresent = nullptr;
ResizeFn g_origResize = nullptr;

// The vtable patch catches EVERY DXGI swap chain in the process (UE4SS's GUI window presents thousands of
// times a second on its own thread). We only ever touch the GAME's swap chain, recognised by its window class;
// everything else passes straight through. The game presents from one render thread, so all drawing state
// below is only ever used from that thread.
char g_gameWindowClass[64] = "UnrealWindow";
char g_overlayName[128] = "Local\\HelloNeighborMC_overlay_v0";

struct Known { IDXGISwapChain* sc; bool game; };
Known g_known[16];
int g_knownCount = 0;
SRWLOCK g_knownLock = SRWLOCK_INIT;

bool isGameSwapChain(IDXGISwapChain* sc) {
    AcquireSRWLockShared(&g_knownLock);
    for (int i = 0; i < g_knownCount; ++i) {
        if (g_known[i].sc == sc) { bool g = g_known[i].game; ReleaseSRWLockShared(&g_knownLock); return g; }
    }
    ReleaseSRWLockShared(&g_knownLock);

    DXGI_SWAP_CHAIN_DESC d{};
    char cls[128] = "?";
    if (SUCCEEDED(sc->GetDesc(&d)) && d.OutputWindow) GetClassNameA(d.OutputWindow, cls, sizeof(cls));
    bool game = std::strcmp(cls, g_gameWindowClass) == 0;
    logf("swap chain %p on window class '%s' (%ux%u fmt=%d swapEffect=%d): %s", (void*)sc, cls,
         d.BufferDesc.Width, d.BufferDesc.Height, (int)d.BufferDesc.Format, (int)d.SwapEffect, game ? "GAME, drawing" : "ignored");

    AcquireSRWLockExclusive(&g_knownLock);
    if (g_knownCount < 16) g_known[g_knownCount++] = { sc, game };
    else g_known[15] = { sc, game };
    ReleaseSRWLockExclusive(&g_knownLock);
    return game;
}

// ---------------- game swap chain state (render thread only) ----------------
IDXGISwapChain* g_sc = nullptr;
ID3D11Device* g_dev = nullptr;
ID3D11Device1* g_dev1 = nullptr;
ID3D11DeviceContext1* g_ctx1 = nullptr;
ID3D11RenderTargetView* g_rtv = nullptr;
UINT g_w = 0, g_h = 0;
HWND g_gameHwnd = nullptr;
uint64_t g_lastPublished = 0;
ULONGLONG g_lastPublishTick = 0;
unsigned long long g_gameFrames = 0;
bool g_disabled = false;

// Our private pipeline (created once per device).
ID3DDeviceContextState* g_ourState = nullptr;
ID3D11VertexShader* g_vs = nullptr;
ID3D11PixelShader* g_ps = nullptr;
ID3D11Buffer* g_cb = nullptr;            // { flipY, 0, 0, 0 }, { cursorX, cursorY, cursorOn, cursorSize }
ID3D11BlendState* g_blend = nullptr;
ID3D11RasterizerState* g_raster = nullptr;
ID3D11DepthStencilState* g_noDepth = nullptr;
ID3D11SamplerState* g_sampler = nullptr;
bool g_pipelineFailed = false;

// Overlay texture (recreated when Minecraft's frame size changes).
ID3D11Texture2D* g_ovTex = nullptr;
ID3D11ShaderResourceView* g_ovSrv = nullptr;
UINT g_ovW = 0, g_ovH = 0;
bool g_ovBottomUp = true;
uint64_t g_ovFrameId = 0;
hn::OverlayMapping g_overlay;
bool g_overlayOpenFailed = false;
unsigned long long g_ovUploads = 0, g_ovDraws = 0;

const char* kShader = R"(
cbuffer Params : register(b0) { float4 params; float4 cursor; };   // params.x = rows bottom-up; cursor = x, y, on, size (pixels)
Texture2D overlayTex : register(t0);
SamplerState samp : register(s0);
struct VSOut { float4 pos : SV_Position; float2 uv : TEXCOORD0; };
VSOut vs(uint id : SV_VertexID) {
    VSOut o;
    float2 uv = float2((id << 1) & 2, id & 2);          // fullscreen triangle
    o.pos = float4(uv * float2(2, -2) + float2(-1, 1), 0, 1);
    o.uv = uv;
    return o;
}
bool arrow(float2 p, float s) { return p.x >= 0 && p.y >= 0 && p.y < s && p.x <= p.y * 0.6 + 0.5; }
float4 ps(VSOut i) : SV_Target {
    float2 uv = i.uv;
    if (params.x > 0.5) uv.y = 1.0 - uv.y;
    float4 c = overlayTex.Sample(samp, uv);             // premultiplied alpha
    if (cursor.z > 0.5) {                               // mouse cursor while a Minecraft screen is open
        float2 p = i.pos.xy - cursor.xy;
        float s = cursor.w;
        if (arrow(p - float2(1, 2), s - 3)) return float4(1, 1, 1, 1);
        if (arrow(p, s)) return float4(0, 0, 0, 1);
    }
    return c;
}
)";

void releaseTargets() { safeRelease(g_rtv); }

void releaseOverlayTexture() { safeRelease(g_ovSrv); safeRelease(g_ovTex); g_ovW = g_ovH = 0; }

void releaseAll() {
    hnworld::releaseAll();
    releaseTargets();
    releaseOverlayTexture();
    safeRelease(g_sampler); safeRelease(g_noDepth); safeRelease(g_raster); safeRelease(g_blend);
    safeRelease(g_cb); safeRelease(g_ps); safeRelease(g_vs); safeRelease(g_ourState);
    safeRelease(g_ctx1); safeRelease(g_dev1); safeRelease(g_dev);
    g_sc = nullptr;
    g_pipelineFailed = false;
}

bool compile(const char* entry, const char* target, ID3DBlob** out) {
    ID3DBlob* err = nullptr;
    HRESULT hr = D3DCompile(kShader, std::strlen(kShader), "hn_gfx", nullptr, nullptr, entry, target, D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, out, &err);
    if (FAILED(hr)) { logf("shader %s failed: %s", entry, err ? (const char*)err->GetBufferPointer() : "?"); safeRelease(err); return false; }
    safeRelease(err);
    return true;
}

bool createPipeline() {
    D3D_FEATURE_LEVEL fl = g_dev->GetFeatureLevel(), chosen;
    HRESULT hr = g_dev1->CreateDeviceContextState(0, &fl, 1, D3D11_SDK_VERSION, __uuidof(ID3D11Device), &chosen, &g_ourState);
    if (FAILED(hr)) { logf("CreateDeviceContextState failed hr=0x%08lx", hr); return false; }
    ID3DBlob *vsb = nullptr, *psb = nullptr;
    if (!compile("vs", "vs_5_0", &vsb) || !compile("ps", "ps_5_0", &psb)) { safeRelease(vsb); safeRelease(psb); return false; }
    hr = g_dev->CreateVertexShader(vsb->GetBufferPointer(), vsb->GetBufferSize(), nullptr, &g_vs);
    if (SUCCEEDED(hr)) hr = g_dev->CreatePixelShader(psb->GetBufferPointer(), psb->GetBufferSize(), nullptr, &g_ps);
    safeRelease(vsb); safeRelease(psb);
    if (FAILED(hr)) { logf("CreateShader failed hr=0x%08lx", hr); return false; }

    D3D11_BUFFER_DESC cbd{ 32, D3D11_USAGE_DYNAMIC, D3D11_BIND_CONSTANT_BUFFER, D3D11_CPU_ACCESS_WRITE, 0, 0 };
    D3D11_BLEND_DESC bd{};
    bd.RenderTarget[0].BlendEnable = TRUE;
    bd.RenderTarget[0].SrcBlend = D3D11_BLEND_ONE;           // premultiplied alpha
    bd.RenderTarget[0].DestBlend = D3D11_BLEND_INV_SRC_ALPHA;
    bd.RenderTarget[0].BlendOp = D3D11_BLEND_OP_ADD;
    bd.RenderTarget[0].SrcBlendAlpha = D3D11_BLEND_ONE;
    bd.RenderTarget[0].DestBlendAlpha = D3D11_BLEND_INV_SRC_ALPHA;
    bd.RenderTarget[0].BlendOpAlpha = D3D11_BLEND_OP_ADD;
    bd.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
    D3D11_RASTERIZER_DESC rd{}; rd.FillMode = D3D11_FILL_SOLID; rd.CullMode = D3D11_CULL_NONE; rd.DepthClipEnable = TRUE;
    D3D11_DEPTH_STENCIL_DESC dd{}; dd.DepthEnable = FALSE; dd.StencilEnable = FALSE;
    D3D11_SAMPLER_DESC sd{}; sd.Filter = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
    sd.AddressU = sd.AddressV = sd.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP; sd.MaxLOD = D3D11_FLOAT32_MAX;
    if (FAILED(g_dev->CreateBuffer(&cbd, nullptr, &g_cb)) || FAILED(g_dev->CreateBlendState(&bd, &g_blend)) ||
        FAILED(g_dev->CreateRasterizerState(&rd, &g_raster)) || FAILED(g_dev->CreateDepthStencilState(&dd, &g_noDepth)) ||
        FAILED(g_dev->CreateSamplerState(&sd, &g_sampler))) {
        logf("pipeline object creation failed");
        return false;
    }
    logf("overlay pipeline ready (feature level 0x%x)", (unsigned)chosen);
    return true;
}

bool ensureDevice(IDXGISwapChain* sc) {
    if (sc != g_sc) {                     // first frame, or the game recreated its swap chain
        releaseAll();
        g_sc = sc;
        if (FAILED(sc->GetDevice(__uuidof(ID3D11Device), (void**)&g_dev))) { logf("game swap chain is not D3D11"); return false; }
        if (FAILED(g_dev->QueryInterface(__uuidof(ID3D11Device1), (void**)&g_dev1))) { logf("no ID3D11Device1"); return false; }
        ID3D11DeviceContext* ctx = nullptr;
        g_dev->GetImmediateContext(&ctx);
        HRESULT hr = ctx->QueryInterface(__uuidof(ID3D11DeviceContext1), (void**)&g_ctx1);
        ctx->Release();
        if (FAILED(hr)) { logf("no ID3D11DeviceContext1 (hr=0x%08lx)", hr); return false; }
        logf("drawing on game swap chain %p (thread %lu)", (void*)sc, GetCurrentThreadId());
        DXGI_SWAP_CHAIN_DESC scd{};
        if (SUCCEEDED(sc->GetDesc(&scd))) g_gameHwnd = scd.OutputWindow;
        if (!createPipeline()) { g_pipelineFailed = true; logf("overlay disabled: pipeline unavailable"); }
        else hnworld::install(g_dev, g_ctx1, g_exeDir, &logf);
    }
    if (!g_ctx1 || g_pipelineFailed) return false;
    if (!g_rtv) {
        ID3D11Texture2D* back = nullptr;
        if (FAILED(g_sc->GetBuffer(0, __uuidof(ID3D11Texture2D), (void**)&back))) return false;
        D3D11_TEXTURE2D_DESC td{};
        back->GetDesc(&td);
        HRESULT hr = g_dev->CreateRenderTargetView(back, nullptr, &g_rtv);
        back->Release();
        if (FAILED(hr)) { logf("CreateRenderTargetView failed hr=0x%08lx (fmt %d)", hr, (int)td.Format); return false; }
        g_w = td.Width; g_h = td.Height;
        logf("back buffer RTV ready: %ux%u fmt=%d", g_w, g_h, (int)td.Format);
    }
    return true;
}

// Pull the newest Minecraft frame (if any) into g_ovTex. Returns true if there is something to draw.
bool updateOverlay() {
    if (!g_overlay.base) {
        if (g_overlayOpenFailed) return false;
        if (!g_overlay.open(g_overlayName)) { g_overlayOpenFailed = true; logf("cannot open overlay mapping %s", g_overlayName); return false; }
        if (!g_overlay.created && g_overlay.ready() && g_overlay.header()->version != hn::kOverlayVersion) {
            logf("overlay mapping %s has protocol v%u, we speak v%u: restart Minecraft; overlay + input disabled",
                 g_overlayName, g_overlay.header()->version, hn::kOverlayVersion);
            g_overlay.close();
            g_overlayOpenFailed = true;
            return false;
        }
        logf("overlay mapping %s open (created: %d)", g_overlayName, g_overlay.created);
        hninput::install(g_gameHwnd, g_overlay.base, &logf);
    }
    hn::OverlayHeader* h = g_overlay.header();
    // Tell Minecraft what size to render at.
    std::atomic_ref<uint32_t>(h->hostViewportW).store(g_w, std::memory_order_relaxed);
    std::atomic_ref<uint32_t>(h->hostViewportH).store(g_h, std::memory_order_release);
    hninput::setViewport((int)g_w, (int)g_h);
    // Minecraft counts as alive while it keeps publishing frames; otherwise all input goes to the game.
    uint64_t pub = std::atomic_ref<uint64_t>(h->framesPublished).load(std::memory_order_relaxed);
    ULONGLONG now = GetTickCount64();
    if (pub != g_lastPublished) { g_lastPublished = pub; g_lastPublishTick = now; }
    hninput::setMinecraftAlive(g_lastPublishTick != 0 && now - g_lastPublishTick < 1000);
    hninput::pumpTaps();

    uint32_t slot = hn::overlayAcquire(h);
    if (slot == hn::kOverlayNone) return g_ovSrv != nullptr;
    const hn::OverlaySlot& s = h->slots[slot];
    if (s.frameId != g_ovFrameId && s.width > 0 && s.height > 0 && s.width <= hn::kOverlayMaxW && s.height <= hn::kOverlayMaxH) {
        if (s.width != g_ovW || s.height != g_ovH) {
            releaseOverlayTexture();
            D3D11_TEXTURE2D_DESC td{ s.width, s.height, 1, 1, DXGI_FORMAT_R8G8B8A8_UNORM, { 1, 0 }, D3D11_USAGE_DYNAMIC,
                                     D3D11_BIND_SHADER_RESOURCE, D3D11_CPU_ACCESS_WRITE, 0 };
            if (FAILED(g_dev->CreateTexture2D(&td, nullptr, &g_ovTex)) || FAILED(g_dev->CreateShaderResourceView(g_ovTex, nullptr, &g_ovSrv))) {
                logf("overlay texture %ux%u creation failed", s.width, s.height);
                releaseOverlayTexture();
                hn::overlayRelease(h);
                return false;
            }
            g_ovW = s.width; g_ovH = s.height;
            logf("overlay texture %ux%u (bottomUp=%d)", g_ovW, g_ovH, (s.flags & hn::kOverlayFlagBottomUp) != 0);
        }
        D3D11_MAPPED_SUBRESOURCE m;
        if (SUCCEEDED(g_ctx1->Map(g_ovTex, 0, D3D11_MAP_WRITE_DISCARD, 0, &m))) {
            const uint8_t* src = hn::overlayPixels(g_overlay.base, slot);
            const size_t row = size_t(s.width) * 4;
            if (m.RowPitch == row) std::memcpy(m.pData, src, row * s.height);
            else for (uint32_t y = 0; y < s.height; ++y) std::memcpy((uint8_t*)m.pData + y * m.RowPitch, src + y * row, row);
            g_ctx1->Unmap(g_ovTex, 0);
            g_ovFrameId = s.frameId;
            g_ovBottomUp = (s.flags & hn::kOverlayFlagBottomUp) != 0;
            if (++g_ovUploads == 1) logf("first Minecraft frame uploaded (id %llu)", (unsigned long long)s.frameId);
            std::atomic_ref<uint64_t>(h->framesShown).fetch_add(1, std::memory_order_relaxed);
        }
    }
    hn::overlayRelease(h);
    return g_ovSrv != nullptr;
}

void drawFrame(bool overlay) {
    // Our own complete pipeline state: Unreal's (cached by its RHI) is swapped out, then restored exactly.
    ID3DDeviceContextState* gameState = nullptr;
    g_ctx1->SwapDeviceContextState(g_ourState, &gameState);

    D3D11_MAPPED_SUBRESOURCE m;
    if (SUCCEEDED(g_ctx1->Map(g_cb, 0, D3D11_MAP_WRITE_DISCARD, 0, &m))) {
        float cursorSize = g_h >= 1400 ? 28.0f : 20.0f;
        float p[8] = { g_ovBottomUp ? 1.0f : 0.0f, 0, 0, 0,
                       (float)hninput::cursorX(), (float)hninput::cursorY(), hninput::cursorVisible() ? 1.0f : 0.0f, cursorSize };
        std::memcpy(m.pData, p, sizeof(p));
        g_ctx1->Unmap(g_cb, 0);
    }
    D3D11_VIEWPORT vp{ 0, 0, (float)g_w, (float)g_h, 0, 1 };
    const float blendFactor[4] = { 0, 0, 0, 0 };
    g_ctx1->OMSetRenderTargets(1, &g_rtv, nullptr);
    g_ctx1->RSSetViewports(1, &vp);
    hnworld::draw(g_ctx1, g_rtv, g_w, g_h);      // step 4: things in the 3D scene, under the HUD
    if (overlay) {
    g_ctx1->OMSetBlendState(g_blend, blendFactor, 0xFFFFFFFF);
    g_ctx1->OMSetDepthStencilState(g_noDepth, 0);
    g_ctx1->RSSetState(g_raster);
    g_ctx1->RSSetViewports(1, &vp);
    g_ctx1->IASetInputLayout(nullptr);
    g_ctx1->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    g_ctx1->VSSetShader(g_vs, nullptr, 0);
    g_ctx1->PSSetShader(g_ps, nullptr, 0);
    g_ctx1->PSSetConstantBuffers(0, 1, &g_cb);
    g_ctx1->PSSetShaderResources(0, 1, &g_ovSrv);
    g_ctx1->PSSetSamplers(0, 1, &g_sampler);
    g_ctx1->Draw(3, 0);
    }

    // Our state object must not keep the back buffer alive (ResizeBuffers would fail) or the texture bound.
    ID3D11RenderTargetView* nullRtv = nullptr;
    ID3D11ShaderResourceView* nullSrv = nullptr;
    g_ctx1->OMSetRenderTargets(1, &nullRtv, nullptr);
    g_ctx1->PSSetShaderResources(0, 1, &nullSrv);

    g_ctx1->SwapDeviceContextState(gameState, nullptr);
    safeRelease(gameState);
    if (overlay && ++g_ovDraws == 1) logf("first overlay draw");
}

// "module+offset" for a code address (log only).
void describeAddr(const void* p, char* out, size_t cap) {
    HMODULE m = nullptr;
    char path[MAX_PATH] = "?";
    if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT, (LPCSTR)p, &m) && m)
        GetModuleFileNameA(m, path, MAX_PATH);
    const char* name = std::strrchr(path, '\\');
    std::snprintf(out, cap, "%s+0x%llx", name ? name + 1 : path, (unsigned long long)((const char*)p - (const char*)m));
}

// Re-entry guard. Steam's overlay also hooks Present, and depending on who hooks first the two can end up
// calling each other (our "original" leads back into us). That recursed until the stack ran out and crashed the
// game at startup. Now an inner call never draws, and a runaway loop is cut off instead of crashing.
thread_local int t_presentDepth = 0;
unsigned long long g_reentries = 0, g_loopCuts = 0;
constexpr int kMaxPresentDepth = 8;

// Per-frame costs of our work inside Present (ms), summed and worst, reset when logged.
double g_costUp = 0, g_costDraw = 0, g_costEnd = 0, g_costPresent = 0;
double g_maxUp = 0, g_maxDraw = 0, g_maxEnd = 0, g_maxPresent = 0;
unsigned long long g_costN = 0;
LARGE_INTEGER s_freq{};      // QueryPerformanceFrequency, set on the first game frame

HRESULT STDMETHODCALLTYPE hookPresent(IDXGISwapChain* sc, UINT sync, UINT flags) {
    if (t_presentDepth > 0) {
        ++g_reentries;
        if (g_reentries == 1) {
            char from[200], orig[200], self[200];
            describeAddr(_ReturnAddress(), from, sizeof(from));
            describeAddr((const void*)g_origPresent, orig, sizeof(orig));
            describeAddr((const void*)&hookPresent, self, sizeof(self));
            const unsigned char* b = (const unsigned char*)g_origPresent;
            logf("Present re-entered (depth %d) from %s; our original is %s (first bytes %02x %02x %02x %02x %02x), "
                 "our hook %s. Another hook (Steam overlay?) calls back into us.", t_presentDepth, from, orig,
                 b[0], b[1], b[2], b[3], b[4], self);
        }
        if (t_presentDepth >= kMaxPresentDepth) {
            if (++g_loopCuts == 1 || g_loopCuts % 600 == 0) logf("Present hook loop cut at depth %d (%llu times)", t_presentDepth, g_loopCuts);
            return S_OK;
        }
        ++t_presentDepth;
        HRESULT hr = g_origPresent(sc, sync, flags);
        --t_presentDepth;
        return hr;
    }
    struct Depth { Depth() { ++t_presentDepth; } ~Depth() { --t_presentDepth; } } depth;

    if (!isGameSwapChain(sc)) return g_origPresent(sc, sync, flags);

    unsigned long long n = ++g_gameFrames;
    // Hello Neighbor's frame gaps (Present to Present), for the log below: the longest, and how many over 25 ms.
    static LARGE_INTEGER s_last{};
    static double s_maxGapMs = 0.0;
    static unsigned s_slow = 0;
    LARGE_INTEGER nowQpc;
    QueryPerformanceCounter(&nowQpc);
    if (!s_freq.QuadPart) QueryPerformanceFrequency(&s_freq);
    if (s_last.QuadPart) {
        double gap = double(nowQpc.QuadPart - s_last.QuadPart) * 1000.0 / double(s_freq.QuadPart);
        if (gap > s_maxGapMs) s_maxGapMs = gap;
        if (gap > 25.0) ++s_slow;
    }
    s_last = nowQpc;
    if (n == 1 || n % 1200 == 0) {        // first frame, then every ~20 s at 60 fps
        g_disabled = killSwitch();
        logf("game Present: %llu frames, drawing=%d, overlay uploads=%llu draws=%llu; longest frame gap %.1f ms, gaps over 25 ms: %u",
             n, !g_disabled, g_ovUploads, g_ovDraws, s_maxGapMs, s_slow);
        if (g_costN > 0) {
            logf("  our cost per frame ms (avg/max): overlay upload %.2f/%.1f, draw %.2f/%.1f, world end %.2f/%.1f; real Present %.2f/%.1f",
                 g_costUp / g_costN, g_maxUp, g_costDraw / g_costN, g_maxDraw, g_costEnd / g_costN, g_maxEnd, g_costPresent / g_costN, g_maxPresent);
        }
        g_costUp = g_costDraw = g_costEnd = g_costPresent = 0.0;
        g_maxUp = g_maxDraw = g_maxEnd = g_maxPresent = 0.0;
        g_costN = 0;
        s_maxGapMs = 0.0;
        s_slow = 0;
    }
    if (!g_disabled && !(flags & DXGI_PRESENT_TEST) && ensureDevice(sc)) {
        LARGE_INTEGER a, b, c, d;
        QueryPerformanceCounter(&a);
        bool ov = updateOverlay();
        QueryPerformanceCounter(&b);
        drawFrame(ov);
        QueryPerformanceCounter(&c);
        hnworld::endFrame();
        QueryPerformanceCounter(&d);
        const double toMs = 1000.0 / double(s_freq.QuadPart);
        const double tUp = double(b.QuadPart - a.QuadPart) * toMs, tDraw = double(c.QuadPart - b.QuadPart) * toMs, tEnd = double(d.QuadPart - c.QuadPart) * toMs;
        g_costUp += tUp; g_costDraw += tDraw; g_costEnd += tEnd; ++g_costN;
        if (tUp > g_maxUp) g_maxUp = tUp;
        if (tDraw > g_maxDraw) g_maxDraw = tDraw;
        if (tEnd > g_maxEnd) g_maxEnd = tEnd;
    }
    // Time inside the real Present (waits for the GPU / the display): a long one here is the game or the GPU, not us.
    LARGE_INTEGER p0, p1;
    QueryPerformanceCounter(&p0);
    HRESULT presentHr = g_origPresent(sc, sync, flags);
    QueryPerformanceCounter(&p1);
    double tPresent = double(p1.QuadPart - p0.QuadPart) * 1000.0 / double(s_freq.QuadPart);
    g_costPresent += tPresent;
    if (tPresent > g_maxPresent) g_maxPresent = tPresent;
    return presentHr;
}

HRESULT STDMETHODCALLTYPE hookResize(IDXGISwapChain* sc, UINT count, UINT w, UINT h, DXGI_FORMAT fmt, UINT flags) {
    bool game = isGameSwapChain(sc);
    if (game && sc == g_sc) releaseTargets();   // our back-buffer reference would make the game's resize fail
    HRESULT hr = g_origResize(sc, count, w, h, fmt, flags);
    if (game) logf("game ResizeBuffers(%u, %ux%u, fmt=%d) -> 0x%08lx", count, w, h, (int)fmt, hr);
    return hr;
}

bool patchVtable(void** vtbl, int index, void* hook, void** orig) {
    DWORD old;
    if (!VirtualProtect(&vtbl[index], sizeof(void*), PAGE_EXECUTE_READWRITE, &old)) return false;
    *orig = vtbl[index];
    vtbl[index] = hook;
    VirtualProtect(&vtbl[index], sizeof(void*), old, &old);
    return true;
}

// Find the DXGI swap chain vtable with a throwaway device/swap chain on a hidden window, then patch it.
DWORD WINAPI installHooks(LPVOID) {
    logf("installing hooks (pid %lu)", GetCurrentProcessId());
    if (killSwitch()) { logf("hn_gfx.disable present: not hooking"); return 0; }

    WNDCLASSEXA wc{ sizeof(wc) };
    wc.lpfnWndProc = DefWindowProcA;
    wc.hInstance = GetModuleHandleA(nullptr);
    wc.lpszClassName = "hn_gfx_dummy";
    RegisterClassExA(&wc);
    HWND hwnd = CreateWindowExA(0, wc.lpszClassName, "", WS_OVERLAPPEDWINDOW, 0, 0, 64, 64, nullptr, nullptr, wc.hInstance, nullptr);
    if (!hwnd) { logf("dummy window failed (%lu)", GetLastError()); return 0; }

    DXGI_SWAP_CHAIN_DESC sd{};
    sd.BufferCount = 1;
    sd.BufferDesc.Width = 64; sd.BufferDesc.Height = 64;
    sd.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    sd.OutputWindow = hwnd;
    sd.SampleDesc.Count = 1;
    sd.Windowed = TRUE;
    sd.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;

    IDXGISwapChain* sc = nullptr; ID3D11Device* dev = nullptr; ID3D11DeviceContext* ctx = nullptr;
    D3D_FEATURE_LEVEL fl;
    // Logged before and after: if a launch hangs on a white window and the log ends here, the throwaway device
    // raced the game's own D3D start-up.
    logf("creating throwaway D3D device (game window %s)", FindWindowA(g_gameWindowClass, nullptr) ? "exists" : "not yet created");
    HRESULT hr = D3D11CreateDeviceAndSwapChain(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0, nullptr, 0,
                                               D3D11_SDK_VERSION, &sd, &sc, &dev, &fl, &ctx);
    if (FAILED(hr)) { logf("dummy D3D11CreateDeviceAndSwapChain failed hr=0x%08lx", hr); DestroyWindow(hwnd); return 0; }

    // The vtable lives in dxgi.dll's image, so it outlives these throwaway objects.
    void** vtbl = *reinterpret_cast<void***>(sc);
    sc->Release(); ctx->Release(); dev->Release();
    DestroyWindow(hwnd);
    UnregisterClassA(wc.lpszClassName, wc.hInstance);

    // Patch only after the Steam overlay has hooked Present. Patched before it (we are loaded before the game even
    // has a window), Steam takes OUR hook as its "original" and puts its own jump into dxgi's Present, which is our
    // original: each calls the other, the real Present is never reached and the game stays on a white window. After
    // it, the calls run in a line: us -> Steam -> dxgi. So wait for the game window and for Steam's hook to appear
    // (dxgi's Present starting with a jump, or the vtable entry changed), at most 10 s after the window shows up.
    void* present = vtbl[8];
    const unsigned char first = *static_cast<const unsigned char*>(present);
    ULONGLONG start = GetTickCount64(), windowAt = 0;
    const char* why = "timeout";
    for (;;) {
        bool window = FindWindowA(g_gameWindowClass, nullptr) != nullptr;
        if (window && !windowAt) windowAt = GetTickCount64();
        bool steam = GetModuleHandleA("gameoverlayrenderer64.dll") != nullptr;
        // (a jump there from the start: Steam hooked before we looked, which is just as safe)
        bool steamHooked = vtbl[8] != present || *static_cast<const unsigned char*>(present) != first || first == 0xE9;
        if (window && steamHooked) { why = "after the Steam overlay's hook"; break; }
        if (window && !steam && GetTickCount64() - windowAt > 1000) { why = "no Steam overlay"; break; }
        if ((windowAt && GetTickCount64() - windowAt > 10000) || GetTickCount64() - start > 60000) break;
        Sleep(50);
    }
    bool ok = patchVtable(vtbl, 8, (void*)&hookPresent, (void**)&g_origPresent) &&
              patchVtable(vtbl, 13, (void*)&hookResize, (void**)&g_origResize);
    logf("vtable %p patched (%s): Present=%d (orig %p), ResizeBuffers (orig %p)", (void*)vtbl, why, ok,
         (void*)g_origPresent, (void*)g_origResize);
    return 0;
}

}  // namespace

BOOL APIENTRY DllMain(HMODULE self, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        // Pin: UE4SS will FreeLibrary us when it finds no start_mod export; our hooks must stay loaded.
        HMODULE pinned;
        GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_PIN, (LPCSTR)&DllMain, &pinned);
        DisableThreadLibraryCalls(self);
        InitializeCriticalSection(&g_logLock);
        initPaths();
        char v[128];
        if (GetEnvironmentVariableA("HN_GFX_WINDOW_CLASS", v, sizeof(v)) > 0) std::snprintf(g_gameWindowClass, sizeof(g_gameWindowClass), "%s", v);
        if (GetEnvironmentVariableA("HN_GFX_OVERLAY_NAME", v, sizeof(v)) > 0) std::snprintf(g_overlayName, sizeof(g_overlayName), "%s", v);
        char p[MAX_PATH];
        std::snprintf(p, sizeof(p), "%shn_gfx.log", g_exeDir);
        g_log = std::fopen(p, "w");
        logf("hn_gfx loaded (step 2 overlay + step 4 world layer); game window class '%s', overlay '%s'", g_gameWindowClass, g_overlayName);
        // No D3D work inside DllMain (loader lock): do it on a thread.
        HANDLE t = CreateThread(nullptr, 0, installHooks, nullptr, 0, nullptr);
        if (t) CloseHandle(t);
    }
    return TRUE;
}
