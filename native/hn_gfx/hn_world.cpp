// hn_world.cpp - step 4: Minecraft's blocks in Hello Neighbor's 3D scene (scene depth + camera + block mesh).
//
// Depth: the immediate context's OMSetRenderTargets / OMSetRenderTargetsAndUnorderedAccessViews are hooked (vtable
// 33/34) only to COUNT which depth buffers Unreal binds each frame. At Present the most-bound depth texture of the
// back buffer's size is taken as the scene depth (Unreal keeps it intact until the next frame's clear) and read
// through our own SRV. Unreal 4.20 uses reversed Z: 1 = near plane, 0 = infinitely far.
//
// Camera: HnLink (Lua) finds the PlayerCameraManager's CameraCache.POV by reflection and writes its address and
// field offsets to hn_camera.txt; we read Location / Rotation / FOV from game memory every frame (SEH-guarded).
//
// Test cube: HnLink's F9 writes hn_testcube.txt ("1 x y z size" in Unreal units, or "0"). While it is on we draw the
// cube (depth-tested by hand against the scene depth) and a small depth preview in the top-right corner.
//
// Blocks: Minecraft publishes a textured triangle mesh of its blocks + the block atlas (protocol/hn_world.h); we draw
// it with our own depth buffer (blocks hide each other) and test each pixel against the scene depth.
#include "hn_world.h"

#include <windows.h>
#include <d3d11_3.h>
#include <d3dcompiler.h>

#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>

#include "../../protocol/hn_world.h"
#include "../third_party/minhook/include/MinHook.h"

namespace hnworld {
namespace {

LogFn logf = nullptr;
char g_camFile[MAX_PATH], g_cubeFile[MAX_PATH];
ID3D11Device* g_dev = nullptr;   // not owned (hn_gfx holds it)

template <class T> void safeRelease(T*& p) { if (p) { p->Release(); p = nullptr; } }

// ---------------- depth buffer tracking ----------------
using OMSetRTFn = void(STDMETHODCALLTYPE*)(ID3D11DeviceContext*, UINT, ID3D11RenderTargetView* const*, ID3D11DepthStencilView*);
using OMSetRTUAVFn = void(STDMETHODCALLTYPE*)(ID3D11DeviceContext*, UINT, ID3D11RenderTargetView* const*, ID3D11DepthStencilView*,
                                              UINT, UINT, ID3D11UnorderedAccessView* const*, const UINT*);
// Inline (code) hooks via MinHook. Patching the context vtable does NOT work here: this runtime keeps the table in
// the context object, rewrites it on every SwapDeviceContextState, and Unreal's calls never went through the
// patched entries (0 calls seen, 2026-10-02 18:40). Hooking the functions' code catches every caller.
// The table entries are re-read every frame; any new implementation address gets hooked too (up to kMaxTargets).
constexpr int kMaxTargets = 4;
OMSetRTFn g_rtTramp[kMaxTargets];
void* g_rtTarget[kMaxTargets];
int g_rtCount = 0;
OMSetRTUAVFn g_uavTramp[kMaxTargets];
void* g_uavTarget[kMaxTargets];
int g_uavCount = 0;
void** g_watchTables[8];
int g_watchCount = 0;
bool g_hooked = false, g_mhReady = false;
unsigned long long g_hookCalls = 0, g_hookDsv = 0;

struct DepthTex {
    ID3D11Texture2D* tex;        // one reference held while in the table
    D3D11_TEXTURE2D_DESC desc;
    unsigned binds;              // this frame
    unsigned long long total;
};
constexpr int kMaxDepth = 32;
DepthTex g_depth[kMaxDepth];
int g_depthCount = 0;
ID3D11Texture2D* g_ignoreDepth = nullptr;    // our own depth buffer: never a scene depth candidate

void noteDsv(ID3D11DepthStencilView* dsv) {
    ID3D11Resource* r = nullptr;
    dsv->GetResource(&r);
    if (!r) return;
    if (r == g_ignoreDepth) { r->Release(); return; }
    for (int i = 0; i < g_depthCount; ++i) {
        if (g_depth[i].tex == r) { g_depth[i].binds++; g_depth[i].total++; r->Release(); return; }
    }
    D3D11_RESOURCE_DIMENSION dim;
    r->GetType(&dim);
    if (dim != D3D11_RESOURCE_DIMENSION_TEXTURE2D) { r->Release(); return; }
    int slot = g_depthCount;
    if (slot == kMaxDepth) {                       // evict the least used
        slot = 0;
        for (int i = 1; i < kMaxDepth; ++i) if (g_depth[i].total < g_depth[slot].total) slot = i;
        g_depth[slot].tex->Release();
    } else {
        ++g_depthCount;
    }
    DepthTex& d = g_depth[slot];
    d.tex = static_cast<ID3D11Texture2D*>(r);      // keep this reference
    d.tex->GetDesc(&d.desc);
    d.binds = 1;
    d.total = 1;
}

template <int N>
void STDMETHODCALLTYPE hkSetRT(ID3D11DeviceContext* c, UINT n, ID3D11RenderTargetView* const* rtv, ID3D11DepthStencilView* dsv) {
    ++g_hookCalls;
    if (dsv) { ++g_hookDsv; noteDsv(dsv); }
    g_rtTramp[N](c, n, rtv, dsv);
}

template <int N>
void STDMETHODCALLTYPE hkSetRTUAV(ID3D11DeviceContext* c, UINT n, ID3D11RenderTargetView* const* rtv, ID3D11DepthStencilView* dsv,
                                  UINT uavStart, UINT uavN, ID3D11UnorderedAccessView* const* uav, const UINT* counts) {
    ++g_hookCalls;
    if (dsv && n != D3D11_KEEP_RENDER_TARGETS_AND_DEPTH_STENCIL) { ++g_hookDsv; noteDsv(dsv); }
    g_uavTramp[N](c, n, rtv, dsv, uavStart, uavN, uav, counts);
}

void* const kRtDetours[kMaxTargets] = { (void*)&hkSetRT<0>, (void*)&hkSetRT<1>, (void*)&hkSetRT<2>, (void*)&hkSetRT<3> };
void* const kUavDetours[kMaxTargets] = { (void*)&hkSetRTUAV<0>, (void*)&hkSetRTUAV<1>, (void*)&hkSetRTUAV<2>, (void*)&hkSetRTUAV<3> };

bool isOurs(void* p, void* const* detours) {
    for (int i = 0; i < kMaxTargets; ++i) if (p == detours[i]) return true;
    return false;
}

// Hook one implementation address (once). kind 0 = OMSetRenderTargets, 1 = ...AndUnorderedAccessViews.
void hookTarget(void* target, int kind) {
    void** targets = kind == 0 ? g_rtTarget : g_uavTarget;
    int& count = kind == 0 ? g_rtCount : g_uavCount;
    void* const* detours = kind == 0 ? kRtDetours : kUavDetours;
    if (!target || isOurs(target, detours)) return;
    for (int i = 0; i < count; ++i) if (targets[i] == target) return;
    if (count == kMaxTargets) return;
    void** tramp = kind == 0 ? (void**)&g_rtTramp[count] : (void**)&g_uavTramp[count];
    MH_STATUS st = MH_CreateHook(target, detours[count], tramp);
    if (st == MH_OK) st = MH_EnableHook(target);
    logf("code hook %s #%d at %p: %s", kind == 0 ? "OMSetRenderTargets" : "OMSetRenderTargetsAndUAVs", count, target, MH_StatusToString(st));
    if (st == MH_OK) targets[count++] = target;
}

// Re-read the watched tables; hook any implementation address not seen yet.
void watchTables() {
    for (int i = 0; i < g_watchCount; ++i) {
        hookTarget(g_watchTables[i][33], 0);
        hookTarget(g_watchTables[i][34], 1);
    }
}

// The chosen scene depth and our view of it.
ID3D11Texture2D* g_sceneDepth = nullptr;     // borrowed from the table
ID3D11ShaderResourceView* g_depthSrv = nullptr;
ID3D11Texture2D* g_srvFor = nullptr;
bool g_srvFailed = false;

DXGI_FORMAT srvFormat(DXGI_FORMAT f) {
    switch (f) {
    case DXGI_FORMAT_R24G8_TYPELESS: case DXGI_FORMAT_D24_UNORM_S8_UINT: return DXGI_FORMAT_R24_UNORM_X8_TYPELESS;
    case DXGI_FORMAT_R32G8X24_TYPELESS: case DXGI_FORMAT_D32_FLOAT_S8X24_UINT: return DXGI_FORMAT_R32_FLOAT_X8X24_TYPELESS;
    case DXGI_FORMAT_R32_TYPELESS: case DXGI_FORMAT_D32_FLOAT: return DXGI_FORMAT_R32_FLOAT;
    case DXGI_FORMAT_R16_TYPELESS: case DXGI_FORMAT_D16_UNORM: return DXGI_FORMAT_R16_UNORM;
    default: return DXGI_FORMAT_UNKNOWN;
    }
}

void dumpDepthTable(const char* why) {
    logf("depth table (%s): %d texture(s), hook calls %llu (with depth %llu)", why, g_depthCount, g_hookCalls, g_hookDsv);
    for (int i = 0; i < g_depthCount; ++i) {
        const DepthTex& d = g_depth[i];
        logf("  depth tex %p %ux%u fmt=%d samples=%u bind=0x%x binds this frame=%u total=%llu%s", (void*)d.tex, d.desc.Width,
             d.desc.Height, (int)d.desc.Format, d.desc.SampleDesc.Count, d.desc.BindFlags, d.binds, d.total,
             d.tex == g_sceneDepth ? "  <== scene" : "");
    }
}

void selectDepth(UINT w, UINT h) {
    // Prefer the back buffer's size; otherwise (render scale) the biggest depth texture of the same aspect.
    int best = -1;
    for (int i = 0; i < g_depthCount; ++i) {
        const DepthTex& d = g_depth[i];
        if (d.binds == 0 || d.desc.Width < w / 4) continue;
        if (std::abs((float)d.desc.Width / d.desc.Height - (float)w / h) > 0.02f) continue;
        if (best < 0) { best = i; continue; }
        const DepthTex& b = g_depth[best];
        bool exact = d.desc.Width == w && d.desc.Height == h, bExact = b.desc.Width == w && b.desc.Height == h;
        if (exact != bExact) { if (exact) best = i; continue; }
        unsigned long long area = (unsigned long long)d.desc.Width * d.desc.Height, bArea = (unsigned long long)b.desc.Width * b.desc.Height;
        if (area != bArea) { if (area > bArea) best = i; continue; }
        if (d.binds > b.binds) best = i;
    }
    ID3D11Texture2D* pick = best >= 0 ? g_depth[best].tex : nullptr;
    if (pick == g_sceneDepth) return;
    g_sceneDepth = pick;
    safeRelease(g_depthSrv);
    g_srvFor = nullptr;
    g_srvFailed = false;
    dumpDepthTable(pick ? "scene depth selected" : "scene depth lost");
}

bool ensureDepthSrv() {
    if (!g_sceneDepth) return false;
    if (g_srvFor == g_sceneDepth) return g_depthSrv != nullptr;
    if (g_srvFailed) return false;
    g_srvFor = g_sceneDepth;
    D3D11_TEXTURE2D_DESC d;
    g_sceneDepth->GetDesc(&d);
    DXGI_FORMAT f = srvFormat(d.Format);
    if (!(d.BindFlags & D3D11_BIND_SHADER_RESOURCE) || f == DXGI_FORMAT_UNKNOWN || d.SampleDesc.Count != 1) {
        logf("scene depth not readable (fmt=%d bind=0x%x samples=%u): drawing without depth test", (int)d.Format, d.BindFlags, d.SampleDesc.Count);
        g_srvFailed = true;
        return false;
    }
    D3D11_SHADER_RESOURCE_VIEW_DESC sd{};
    sd.Format = f;
    sd.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2D;
    sd.Texture2D.MipLevels = 1;
    HRESULT hr = g_dev->CreateShaderResourceView(g_sceneDepth, &sd, &g_depthSrv);
    if (FAILED(hr)) { logf("depth SRV failed hr=0x%08lx", hr); g_srvFailed = true; return false; }
    logf("scene depth SRV ready (srv fmt %d)", (int)f);
    return true;
}

// ---------------- camera ----------------
struct CamOffsets { unsigned long long base; long long loc, rot, fov; };
CamOffsets g_camOff{};
struct Cam { float loc[3]; float rot[3]; float fov; };
bool g_camOk = false;
unsigned long long g_camReads = 0, g_camFaults = 0;

bool readCam(const CamOffsets& o, Cam& c) {
    __try {
        const char* b = reinterpret_cast<const char*>(o.base);
        std::memcpy(c.loc, b + o.loc, 12);
        std::memcpy(c.rot, b + o.rot, 12);
        std::memcpy(&c.fov, b + o.fov, 4);
        return true;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

// ---------------- control files (polled) ----------------
struct TestCube { bool on; float x, y, z, size; };
TestCube g_cube{};
float g_yOff = 0.0f;            // HnLink's grid alignment: Unreal z = Minecraft y * 84 - yOff
unsigned long long g_frame = 0;
char g_statusFile[MAX_PATH];

void pollFiles() {
    char buf[256];
    if (FILE* f = std::fopen(g_camFile, "r")) {
        CamOffsets o{};
        float yOff = 0.0f;
        size_t n = std::fread(buf, 1, sizeof(buf) - 1, f); buf[n] = 0; std::fclose(f);
        int got = std::sscanf(buf, "%llu %lld %lld %lld %f", &o.base, &o.loc, &o.rot, &o.fov, &yOff);
        if (got >= 4) {
            if (std::memcmp(&o, &g_camOff, sizeof(o)) != 0) {
                g_camOff = o;
                logf("camera source: POV at 0x%llx + loc %lld rot %lld fov %lld", o.base, o.loc, o.rot, o.fov);
            }
            if (got == 5 && yOff != g_yOff) { g_yOff = yOff; logf("grid offset %.1f uu", yOff); }
        }
    }
    if (FILE* f = std::fopen(g_cubeFile, "r")) {
        TestCube c{};
        int on = 0;
        size_t n = std::fread(buf, 1, sizeof(buf) - 1, f); buf[n] = 0; std::fclose(f);
        if (std::sscanf(buf, "%d %f %f %f %f", &on, &c.x, &c.y, &c.z, &c.size) >= 1) {
            c.on = on != 0;
            if (c.on != g_cube.on || c.x != g_cube.x || c.y != g_cube.y || c.z != g_cube.z)
                logf("test cube %s at (%.1f, %.1f, %.1f) size %.1f", c.on ? "ON" : "off", c.x, c.y, c.z, c.size);
            g_cube = c;
        }
    }
}

// ---------------- world channel (Minecraft's block mesh + atlas) ----------------
HANDLE g_worldMap = nullptr;
void* g_worldBase = nullptr;
uint64_t g_meshSeq = ~0ull, g_atlasSeq = 0;
ID3D11Buffer* g_vb = nullptr;
UINT g_vbCapacity = 0, g_vertexCount = 0;
ID3D11Texture2D* g_atlas = nullptr;
ID3D11ShaderResourceView* g_atlasSrv = nullptr;
UINT g_atlasW = 0, g_atlasH = 0;
unsigned long long g_meshUploads = 0;

bool openWorld() {
    if (g_worldBase) return true;
    g_worldMap = OpenFileMappingA(FILE_MAP_ALL_ACCESS, FALSE, hn::kWorldMappingName);
    if (!g_worldMap) return false;                 // Minecraft creates it; try again later
    g_worldBase = MapViewOfFile(g_worldMap, FILE_MAP_ALL_ACCESS, 0, 0, hn::kWorldMappingBytes);
    if (!g_worldBase) { CloseHandle(g_worldMap); g_worldMap = nullptr; return false; }
    auto* h = static_cast<hn::WorldHeader*>(g_worldBase);
    logf("world channel open (magic %08x version %u)", h->magic, h->version);
    return true;
}

void syncWorld(ID3D11DeviceContext1* ctx) {
    if (!openWorld()) return;
    auto* h = static_cast<hn::WorldHeader*>(g_worldBase);
    if (h->magic != hn::kWorldMagic || h->version != hn::kWorldVersion) return;

    uint64_t atlasSeq = std::atomic_ref<uint64_t>(h->atlasSeq).load(std::memory_order_acquire);
    if (atlasSeq != g_atlasSeq && h->atlasW > 0 && h->atlasH > 0 && h->atlasW <= hn::kWorldAtlasMax && h->atlasH <= hn::kWorldAtlasMax) {
        g_atlasSeq = atlasSeq;
        if (h->atlasW != g_atlasW || h->atlasH != g_atlasH) {
            safeRelease(g_atlasSrv); safeRelease(g_atlas);
            D3D11_TEXTURE2D_DESC td{ h->atlasW, h->atlasH, 1, 1, DXGI_FORMAT_R8G8B8A8_UNORM, { 1, 0 }, D3D11_USAGE_DEFAULT,
                                     D3D11_BIND_SHADER_RESOURCE, 0, 0 };
            if (FAILED(g_dev->CreateTexture2D(&td, nullptr, &g_atlas)) || FAILED(g_dev->CreateShaderResourceView(g_atlas, nullptr, &g_atlasSrv))) {
                logf("atlas texture %ux%u failed", h->atlasW, h->atlasH);
                safeRelease(g_atlasSrv); safeRelease(g_atlas);
                return;
            }
            g_atlasW = h->atlasW; g_atlasH = h->atlasH;
        }
        ctx->UpdateSubresource(g_atlas, 0, nullptr, hn::worldAtlas(g_worldBase), g_atlasW * 4, 0);
        logf("block atlas %ux%u uploaded (seq %llu)", g_atlasW, g_atlasH, (unsigned long long)atlasSeq);
    }

    uint64_t meshSeq = std::atomic_ref<uint64_t>(h->meshSeq).load(std::memory_order_acquire);
    if (meshSeq == g_meshSeq) return;
    uint32_t slot = hn::worldAcquire(h);
    if (slot == hn::kWorldNone) return;
    uint32_t count = h->vertexCount[slot];
    if (count > hn::kWorldMaxVerts) count = 0;
    bool ok = true;
    if (count > g_vbCapacity) {
        safeRelease(g_vb);
        UINT cap = 6 * 1024;
        while (cap < count) cap *= 2;
        D3D11_BUFFER_DESC bd{ cap * (UINT)sizeof(hn::WorldVertex), D3D11_USAGE_DYNAMIC, D3D11_BIND_VERTEX_BUFFER, D3D11_CPU_ACCESS_WRITE, 0, 0 };
        if (FAILED(g_dev->CreateBuffer(&bd, nullptr, &g_vb))) { logf("vertex buffer (%u verts) failed", cap); g_vbCapacity = 0; ok = false; }
        else g_vbCapacity = cap;
    }
    if (ok && count > 0) {
        D3D11_MAPPED_SUBRESOURCE m;
        if (SUCCEEDED(ctx->Map(g_vb, 0, D3D11_MAP_WRITE_DISCARD, 0, &m))) {
            std::memcpy(m.pData, hn::worldSlot(g_worldBase, slot), size_t(count) * sizeof(hn::WorldVertex));
            ctx->Unmap(g_vb, 0);
        } else {
            ok = false;
        }
    }
    hn::worldRelease(h);
    if (!ok) return;
    g_meshSeq = meshSeq;
    g_vertexCount = count;
    if (++g_meshUploads <= 3 || g_meshUploads % 50 == 0) logf("world mesh uploaded: %u triangles (seq %llu)", count / 3, (unsigned long long)meshSeq);
}

// ---------------- entities + their textures (world channel v2) ----------------
constexpr uint32_t kMaxTextureId = 2048;
ID3D11Texture2D* g_tex[kMaxTextureId] = {};
ID3D11ShaderResourceView* g_texSrv[kMaxTextureId] = {};
uint32_t g_texSeen = 0;
ID3D11Buffer* g_entVb = nullptr;
UINT g_entVbCapacity = 0, g_entVertexCount = 0;
hn::WorldBatch g_entBatches[hn::kWorldMaxBatches];
uint32_t g_entBatchCount = 0;
double g_entOrigin[3] = {};
uint64_t g_entSeq = ~0ull;
unsigned long long g_entUploads = 0;

void syncTextures(ID3D11DeviceContext1* ctx) {
    auto* h = static_cast<hn::WorldHeader*>(g_worldBase);
    uint32_t n = std::atomic_ref<uint32_t>(h->texCount).load(std::memory_order_acquire);
    if (n > hn::kWorldMaxTextures) n = hn::kWorldMaxTextures;
    const hn::WorldTexture* table = hn::worldTextures(g_worldBase);
    for (; g_texSeen < n; ++g_texSeen) {
        hn::WorldTexture t = table[g_texSeen];
        if (t.id == 0 || t.id >= kMaxTextureId || t.width == 0 || t.height == 0 || t.width > 8192 || t.height > 8192 ||
            size_t(t.offset) + size_t(t.width) * t.height * 4 > hn::kWorldTexHeapBytes) {
            logf("texture entry %u invalid (id %u %ux%u)", g_texSeen, t.id, t.width, t.height);
            continue;
        }
        safeRelease(g_texSrv[t.id]); safeRelease(g_tex[t.id]);
        D3D11_TEXTURE2D_DESC td{ t.width, t.height, 1, 1, DXGI_FORMAT_R8G8B8A8_UNORM, { 1, 0 }, D3D11_USAGE_DEFAULT, D3D11_BIND_SHADER_RESOURCE, 0, 0 };
        D3D11_SUBRESOURCE_DATA init{ hn::worldTexHeap(g_worldBase) + t.offset, t.width * 4, 0 };
        if (FAILED(g_dev->CreateTexture2D(&td, &init, &g_tex[t.id])) || FAILED(g_dev->CreateShaderResourceView(g_tex[t.id], nullptr, &g_texSrv[t.id]))) {
            logf("texture id %u (%ux%u) upload failed", t.id, t.width, t.height);
            safeRelease(g_texSrv[t.id]); safeRelease(g_tex[t.id]);
            continue;
        }
        logf("texture id %u uploaded (%ux%u)", t.id, t.width, t.height);
    }
    (void)ctx;
}

void syncEntities(ID3D11DeviceContext1* ctx) {
    auto* h = static_cast<hn::WorldHeader*>(g_worldBase);
    uint64_t seq = std::atomic_ref<uint64_t>(h->entSeq).load(std::memory_order_acquire);
    if (seq == g_entSeq) return;
    uint32_t slot = hn::worldAcquireCtl(h->entCtl);
    if (slot == hn::kWorldNone) return;
    uint32_t nb = h->entBatchCount[slot], nv = h->entVertexCount[slot];
    if (nb > hn::kWorldMaxBatches || nv > hn::kWorldEntMaxVerts) nb = nv = 0;
    bool ok = true;
    if (nv > g_entVbCapacity) {
        safeRelease(g_entVb);
        UINT cap = 6 * 1024;
        while (cap < nv) cap *= 2;
        D3D11_BUFFER_DESC bd{ cap * (UINT)sizeof(hn::WorldVertex), D3D11_USAGE_DYNAMIC, D3D11_BIND_VERTEX_BUFFER, D3D11_CPU_ACCESS_WRITE, 0, 0 };
        if (FAILED(g_dev->CreateBuffer(&bd, nullptr, &g_entVb))) { g_entVbCapacity = 0; ok = false; logf("entity vertex buffer (%u) failed", cap); }
        else g_entVbCapacity = cap;
    }
    if (ok && nv > 0) {
        D3D11_MAPPED_SUBRESOURCE m;
        if (SUCCEEDED(ctx->Map(g_entVb, 0, D3D11_MAP_WRITE_DISCARD, 0, &m))) {
            std::memcpy(m.pData, hn::worldEntVerts(g_worldBase, slot), size_t(nv) * sizeof(hn::WorldVertex));
            ctx->Unmap(g_entVb, 0);
        } else {
            ok = false;
        }
    }
    if (ok) {
        std::memcpy(g_entBatches, hn::worldEntBatches(g_worldBase, slot), size_t(nb) * sizeof(hn::WorldBatch));
        for (int i = 0; i < 3; ++i) g_entOrigin[i] = h->entOrigin[slot][i];
    }
    hn::worldReleaseCtl(h->entCtl);
    if (!ok) return;
    g_entSeq = seq;
    g_entBatchCount = nb;
    g_entVertexCount = nv;
    if (++g_entUploads == 1 || g_entUploads % 3600 == 0) logf("entities: %u batch(es), %u triangles (update %llu)", nb, nv / 3, g_entUploads);
}

void releaseEntities() {
    for (uint32_t i = 0; i < kMaxTextureId; ++i) { safeRelease(g_texSrv[i]); safeRelease(g_tex[i]); }
    g_texSeen = 0;
    safeRelease(g_entVb); g_entVbCapacity = 0; g_entVertexCount = 0; g_entBatchCount = 0; g_entSeq = ~0ull;
}

// ---------------- drawing ----------------
const char* kShader = R"(
cbuffer World : register(b0) {
    float4 camPos;                 // Unreal units
    float4 camF, camR, camU;       // camera axes: forward, right, up
    float4 proj;                   // x = 1/tan(hfov/2), y = x * w/h, z = near plane
    float4 cube;                   // test cube: xyz = min corner, w = size
    float4 depthInfo;              // x = scene depth available, yz = depth px per back-buffer px
    float4 mc;                     // x = Unreal units per block, y = grid offset (uu)
    float4 entOrigin;              // entities: vertex positions are relative to this (Minecraft blocks)
};
Texture2D<float> sceneDepth : register(t0);
Texture2D atlas : register(t1);
SamplerState pointSamp : register(s0);

float4 project(float3 w) {
    float3 d = w - camPos.xyz;
    float f = dot(d, camF.xyz), r = dot(d, camR.xyz), u = dot(d, camU.xyz);
    return float4(r * proj.x, u * proj.y, proj.z, f);    // reversed Z, infinite far: depth = near / distance
}

void sceneDepthTest(float4 pos) {
    if (depthInfo.x > 0.5) {
        float s = sceneDepth.Load(int3(pos.xy * depthInfo.yz, 0));
        if (s > pos.z * 1.0005) discard;                 // Hello Neighbor's scene is closer here
    }
}

// --- test cube (F9) ---
static const float3 C[8] = { float3(0,0,0), float3(1,0,0), float3(1,1,0), float3(0,1,0),
                             float3(0,0,1), float3(1,0,1), float3(1,1,1), float3(0,1,1) };
static const uint I[36] = { 0,2,1, 0,3,2,   4,5,6, 4,6,7,   0,1,5, 0,5,4,   2,3,7, 2,7,6,   1,2,6, 1,6,5,   3,0,4, 3,4,7 };
static const float3 N[6] = { float3(0,0,-1), float3(0,0,1), float3(0,-1,0), float3(0,1,0), float3(1,0,0), float3(-1,0,0) };
struct V { float4 pos : SV_Position; float3 n : NORMAL; float2 uv : TEXCOORD0; };
V vsCube(uint id : SV_VertexID) {
    V o;
    float3 p = C[I[id]];
    o.pos = project(cube.xyz + p * cube.w);
    o.n = N[id / 6];
    o.uv = (abs(o.n.x) > 0.5) ? p.yz : ((abs(o.n.y) > 0.5) ? p.xz : p.xy);
    return o;
}
float4 psCube(V i) : SV_Target {
    sceneDepthTest(i.pos);
    float shade = 0.55 + 0.45 * saturate(dot(i.n, normalize(float3(0.4, 0.3, 0.85))) * 0.5 + 0.5);
    int2 t = int2(floor(i.uv * 4));
    float3 col = ((t.x + t.y) & 1) ? float3(1.0, 0.25, 0.85) : float3(0.15, 0.95, 0.6);
    float edge = min(min(i.uv.x, 1 - i.uv.x), min(i.uv.y, 1 - i.uv.y));
    if (edge < 0.03) col = float3(0.05, 0.05, 0.05);
    return float4(col * shade, 1);
}

// --- depth preview ---
struct P { float4 pos : SV_Position; float2 uv : TEXCOORD0; };
P vsQuad(uint id : SV_VertexID) {
    P o;
    float2 uv = float2((id << 1) & 2, id & 2);
    o.pos = float4(uv * float2(2, -2) + float2(-1, 1), 0, 1);
    o.uv = uv;
    return o;
}
float4 psDepth(P i) : SV_Target {
    uint w, h;
    sceneDepth.GetDimensions(w, h);
    float d = sceneDepth.Load(int3(i.uv * float2(w, h), 0));
    float g = saturate(sqrt(d * 20.0));
    return float4(g, g, g, 1);
}

// --- Minecraft's blocks ---
struct MIn { float3 pos : POSITION; float2 uv : TEXCOORD0; float4 color : COLOR0; };
struct M { float4 pos : SV_Position; float2 uv : TEXCOORD0; float4 color : COLOR0; };
M vsMesh(MIn v) {
    M o;
    // Minecraft (x, y, z) blocks -> Unreal (x, z, y) * 84, minus the grid offset in height (not mirrored).
    float3 w = float3(v.pos.x * mc.x, v.pos.z * mc.x, v.pos.y * mc.x - mc.y);
    o.pos = project(w);
    o.uv = v.uv;
    o.color = v.color;
    return o;
}
// --- Minecraft's entities (player model, mobs, items, chests, particles): own texture per batch ---
struct E { float4 pos : SV_Position; float2 uv : TEXCOORD0; float4 color : COLOR0; float3 w : TEXCOORD1; };
E vsEnt(MIn v) {
    E o;
    float3 m = v.pos + entOrigin.xyz;
    float3 w = float3(m.x * mc.x, m.z * mc.x, m.y * mc.x - mc.y);
    o.pos = project(w);
    o.uv = v.uv;
    o.color = v.color;
    o.w = w;
    return o;
}
float4 psEnt(E i) : SV_Target {
    float4 t = atlas.Sample(pointSamp, i.uv) * i.color;
    if (t.a < 0.1) discard;
    sceneDepthTest(i.pos);
    // Minecraft-like face shading from the triangle's own normal (the capture carries no normals).
    float3 n = normalize(cross(ddx(i.w), ddy(i.w)));
    float shade = abs(n.z) * (n.z > 0 ? 1.0 : 0.5) + abs(n.x) * 0.6 + abs(n.y) * 0.8;
    return float4(t.rgb * saturate(shade), t.a);
}

float4 psMesh(M i) : SV_Target {
    float4 t = atlas.Sample(pointSamp, i.uv);
    if (t.a < 0.1) discard;                              // cutout (glass, torches, leaves)
    sceneDepthTest(i.pos);
    return float4(t.rgb * i.color.rgb, 1);
}
)";

ID3D11VertexShader *g_vsCube = nullptr, *g_vsQuad = nullptr, *g_vsMesh = nullptr, *g_vsEnt = nullptr;
ID3D11PixelShader *g_psCube = nullptr, *g_psDepth = nullptr, *g_psMesh = nullptr, *g_psEnt = nullptr;
ID3D11InputLayout* g_layout = nullptr;
ID3D11Buffer* g_cb = nullptr;
ID3D11BlendState *g_opaque = nullptr, *g_alpha = nullptr;
ID3D11RasterizerState* g_raster = nullptr;
ID3D11DepthStencilState *g_noDepth = nullptr, *g_ourDepthTest = nullptr;
ID3D11SamplerState* g_point = nullptr;
bool g_pipeFailed = false, g_pipeReady = false;

// Our own depth buffer: Minecraft's blocks must hide each other (the scene depth only hides them behind Unreal's).
ID3D11Texture2D* g_ourDepth = nullptr;
ID3D11DepthStencilView* g_ourDsv = nullptr;
UINT g_ourDepthW = 0, g_ourDepthH = 0;

bool compile(const char* entry, const char* target, ID3DBlob** out) {
    ID3DBlob* err = nullptr;
    HRESULT hr = D3DCompile(kShader, std::strlen(kShader), "hn_world", nullptr, nullptr, entry, target, D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, out, &err);
    if (FAILED(hr)) { logf("world shader %s failed: %s", entry, err ? (const char*)err->GetBufferPointer() : "?"); safeRelease(err); return false; }
    safeRelease(err);
    return true;
}

bool ensurePipeline() {
    if (g_pipeReady) return true;
    if (g_pipeFailed) return false;
    g_pipeFailed = true;
    ID3DBlob *a = nullptr, *b = nullptr, *c = nullptr, *d = nullptr, *e = nullptr, *f = nullptr;
    bool ok = compile("vsCube", "vs_5_0", &a) && compile("psCube", "ps_5_0", &b) && compile("vsQuad", "vs_5_0", &c) &&
              compile("psDepth", "ps_5_0", &d) && compile("vsMesh", "vs_5_0", &e) && compile("psMesh", "ps_5_0", &f);
    if (ok) {
        const D3D11_INPUT_ELEMENT_DESC il[3] = {
            { "POSITION", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 0, D3D11_INPUT_PER_VERTEX_DATA, 0 },
            { "TEXCOORD", 0, DXGI_FORMAT_R32G32_FLOAT, 0, 12, D3D11_INPUT_PER_VERTEX_DATA, 0 },
            { "COLOR", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, 20, D3D11_INPUT_PER_VERTEX_DATA, 0 },
        };
        ok = SUCCEEDED(g_dev->CreateVertexShader(a->GetBufferPointer(), a->GetBufferSize(), nullptr, &g_vsCube)) &&
             SUCCEEDED(g_dev->CreatePixelShader(b->GetBufferPointer(), b->GetBufferSize(), nullptr, &g_psCube)) &&
             SUCCEEDED(g_dev->CreateVertexShader(c->GetBufferPointer(), c->GetBufferSize(), nullptr, &g_vsQuad)) &&
             SUCCEEDED(g_dev->CreatePixelShader(d->GetBufferPointer(), d->GetBufferSize(), nullptr, &g_psDepth)) &&
             SUCCEEDED(g_dev->CreateVertexShader(e->GetBufferPointer(), e->GetBufferSize(), nullptr, &g_vsMesh)) &&
             SUCCEEDED(g_dev->CreatePixelShader(f->GetBufferPointer(), f->GetBufferSize(), nullptr, &g_psMesh)) &&
             SUCCEEDED(g_dev->CreateInputLayout(il, 3, e->GetBufferPointer(), e->GetBufferSize(), &g_layout));
    }
    safeRelease(a); safeRelease(b); safeRelease(c); safeRelease(d); safeRelease(e); safeRelease(f);
    if (ok) {
        ID3DBlob *ve = nullptr, *pe = nullptr;
        ok = compile("vsEnt", "vs_5_0", &ve) && compile("psEnt", "ps_5_0", &pe) &&
             SUCCEEDED(g_dev->CreateVertexShader(ve->GetBufferPointer(), ve->GetBufferSize(), nullptr, &g_vsEnt)) &&
             SUCCEEDED(g_dev->CreatePixelShader(pe->GetBufferPointer(), pe->GetBufferSize(), nullptr, &g_psEnt));
        safeRelease(ve); safeRelease(pe);
    }
    if (!ok) { logf("world pipeline: shaders unavailable"); return false; }
    D3D11_BUFFER_DESC cbd{ 9 * 16, D3D11_USAGE_DYNAMIC, D3D11_BIND_CONSTANT_BUFFER, D3D11_CPU_ACCESS_WRITE, 0, 0 };
    D3D11_BLEND_DESC bd{};
    bd.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
    D3D11_RASTERIZER_DESC rd{}; rd.FillMode = D3D11_FILL_SOLID; rd.CullMode = D3D11_CULL_NONE; rd.DepthClipEnable = TRUE;
    D3D11_DEPTH_STENCIL_DESC dd{};
    D3D11_DEPTH_STENCIL_DESC dt{};
    dt.DepthEnable = TRUE; dt.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ALL; dt.DepthFunc = D3D11_COMPARISON_GREATER;   // reversed Z
    D3D11_BLEND_DESC ab{};
    ab.RenderTarget[0].BlendEnable = TRUE;
    ab.RenderTarget[0].SrcBlend = D3D11_BLEND_SRC_ALPHA; ab.RenderTarget[0].DestBlend = D3D11_BLEND_INV_SRC_ALPHA; ab.RenderTarget[0].BlendOp = D3D11_BLEND_OP_ADD;
    ab.RenderTarget[0].SrcBlendAlpha = D3D11_BLEND_ONE; ab.RenderTarget[0].DestBlendAlpha = D3D11_BLEND_INV_SRC_ALPHA; ab.RenderTarget[0].BlendOpAlpha = D3D11_BLEND_OP_ADD;
    ab.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
    D3D11_SAMPLER_DESC sd{}; sd.Filter = D3D11_FILTER_MIN_MAG_MIP_POINT;
    sd.AddressU = sd.AddressV = sd.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP; sd.MaxLOD = D3D11_FLOAT32_MAX;
    if (FAILED(g_dev->CreateBuffer(&cbd, nullptr, &g_cb)) || FAILED(g_dev->CreateBlendState(&bd, &g_opaque)) ||
        FAILED(g_dev->CreateRasterizerState(&rd, &g_raster)) || FAILED(g_dev->CreateDepthStencilState(&dd, &g_noDepth)) ||
        FAILED(g_dev->CreateDepthStencilState(&dt, &g_ourDepthTest)) || FAILED(g_dev->CreateSamplerState(&sd, &g_point)) ||
        FAILED(g_dev->CreateBlendState(&ab, &g_alpha))) {
        logf("world pipeline: state objects failed");
        return false;
    }
    g_pipeFailed = false;
    g_pipeReady = true;
    logf("world pipeline ready");
    return true;
}

bool ensureOurDepth(UINT w, UINT h) {
    if (g_ourDsv && g_ourDepthW == w && g_ourDepthH == h) return true;
    safeRelease(g_ourDsv); safeRelease(g_ourDepth);
    D3D11_TEXTURE2D_DESC td{ w, h, 1, 1, DXGI_FORMAT_D32_FLOAT, { 1, 0 }, D3D11_USAGE_DEFAULT, D3D11_BIND_DEPTH_STENCIL, 0, 0 };
    if (FAILED(g_dev->CreateTexture2D(&td, nullptr, &g_ourDepth)) || FAILED(g_dev->CreateDepthStencilView(g_ourDepth, nullptr, &g_ourDsv))) {
        logf("our depth buffer %ux%u failed", w, h);
        safeRelease(g_ourDsv); safeRelease(g_ourDepth);
        return false;
    }
    g_ourDepthW = w; g_ourDepthH = h;
    g_ignoreDepth = g_ourDepth;
    return true;
}

void releasePipeline() {
    safeRelease(g_vsCube); safeRelease(g_vsQuad); safeRelease(g_vsMesh); safeRelease(g_vsEnt); safeRelease(g_psEnt);
    safeRelease(g_alpha);
    releaseEntities();
    safeRelease(g_psCube); safeRelease(g_psDepth); safeRelease(g_psMesh); safeRelease(g_layout);
    safeRelease(g_cb); safeRelease(g_opaque); safeRelease(g_raster); safeRelease(g_noDepth); safeRelease(g_ourDepthTest);
    safeRelease(g_point);
    safeRelease(g_ourDsv); safeRelease(g_ourDepth); g_ourDepthW = g_ourDepthH = 0; g_ignoreDepth = nullptr;
    safeRelease(g_vb); g_vbCapacity = 0; g_vertexCount = 0; g_meshSeq = ~0ull;
    safeRelease(g_atlasSrv); safeRelease(g_atlas); g_atlasW = g_atlasH = 0; g_atlasSeq = 0;
    g_pipeReady = g_pipeFailed = false;
}

void writeStatus(bool depthOk) {
    if (FILE* f = std::fopen(g_statusFile, "w")) {
        std::fprintf(f, "%llu %u %d\n", g_frame, g_vertexCount, depthOk ? 1 : 0);
        std::fclose(f);
    }
}

}  // namespace

void install(ID3D11Device* dev, ID3D11DeviceContext* ctx, const char* exeDir, LogFn log) {
    logf = log;
    g_dev = dev;
    std::snprintf(g_camFile, sizeof(g_camFile), "%shn_camera.txt", exeDir);
    std::snprintf(g_cubeFile, sizeof(g_cubeFile), "%shn_testcube.txt", exeDir);
    std::snprintf(g_statusFile, sizeof(g_statusFile), "%shn_world_status.txt", exeDir);
    if (g_hooked) return;
    g_hooked = true;
    IUnknown* views[5] = {};
    ID3D11DeviceContext* base = nullptr;
    dev->GetImmediateContext(&base);
    views[0] = base;
    views[1] = ctx;
    const IID ids[3] = { __uuidof(ID3D11DeviceContext1), __uuidof(ID3D11DeviceContext2), __uuidof(ID3D11DeviceContext3) };
    for (int i = 0; i < 3; ++i) { void* p = nullptr; if (SUCCEEDED(base->QueryInterface(ids[i], &p))) views[2 + i] = (IUnknown*)p; }
    MH_STATUS init = MH_Initialize();
    g_mhReady = init == MH_OK || init == MH_ERROR_ALREADY_INITIALIZED;
    if (!g_mhReady) logf("MinHook init failed: %s; no scene depth", MH_StatusToString(init));
    for (int i = 0; i < 5; ++i) {
        if (!views[i]) continue;
        void** vt = *reinterpret_cast<void***>(views[i]);
        bool seen = false;
        for (int j = 0; j < g_watchCount; ++j) if (g_watchTables[j] == vt) seen = true;
        if (!seen && g_watchCount < 8) {
            g_watchTables[g_watchCount++] = vt;
            logf("context interface %d (object %p): table %p, OMSetRenderTargets %p, ...AndUAVs %p", i, (void*)views[i], (void*)vt, vt[33], vt[34]);
        }
    }
    if (g_mhReady) watchTables();
    for (int i = 0; i < 5; ++i) if (views[i] && i != 1) views[i]->Release();
}

void releaseAll() {
    safeRelease(g_depthSrv);
    g_srvFor = nullptr;
    g_sceneDepth = nullptr;
    for (int i = 0; i < g_depthCount; ++i) g_depth[i].tex->Release();
    g_depthCount = 0;
    releasePipeline();
}

void draw(ID3D11DeviceContext1* ctx, ID3D11RenderTargetView* rtv, UINT w, UINT h) {
    ++g_frame;
    if (g_frame == 1 || g_frame % 30 == 0) pollFiles();
    selectDepth(w, h);
    if (!g_sceneDepth && g_frame % 600 == 0) dumpDepthTable("no scene depth yet");
    syncWorld(ctx);
    if (g_worldBase) {
        auto* wh = static_cast<hn::WorldHeader*>(g_worldBase);
        if (wh->magic == hn::kWorldMagic && wh->version == hn::kWorldVersion) {
            syncTextures(ctx);
            syncEntities(ctx);
        }
    }
    bool wantMesh = g_vertexCount > 0 && g_atlasSrv;
    bool wantEnt = g_entBatchCount > 0 && g_entVertexCount > 0 && g_entVb;
    if (!(g_cube.on || wantMesh || wantEnt) || !g_camOff.base) { if (g_frame % 60 == 0) writeStatus(false); return; }

    Cam cam;
    if (!readCam(g_camOff, cam)) {
        if (++g_camFaults == 1 || g_camFaults % 600 == 0) logf("camera read faulted (%llu times); waiting for a new hn_camera.txt", g_camFaults);
        return;
    }
    if (!(cam.fov > 5.0f && cam.fov < 170.0f) || !std::isfinite(cam.loc[0]) || !std::isfinite(cam.rot[1])) return;
    if (++g_camReads == 1) logf("camera: (%.1f, %.1f, %.1f) pitch %.1f yaw %.1f roll %.1f fov %.1f", cam.loc[0], cam.loc[1], cam.loc[2],
                                cam.rot[0], cam.rot[1], cam.rot[2], cam.fov);
    if (!ensurePipeline()) return;
    bool depthOk = ensureDepthSrv();
    float depthW = (float)w, depthH = (float)h;
    if (depthOk) { D3D11_TEXTURE2D_DESC dd; g_sceneDepth->GetDesc(&dd); depthW = (float)dd.Width; depthH = (float)dd.Height; }

    // Unreal FRotationMatrix axes (degrees: pitch, yaw, roll).
    const float d2r = 3.14159265f / 180.0f;
    float sp = std::sin(cam.rot[0] * d2r), cp = std::cos(cam.rot[0] * d2r);
    float sy = std::sin(cam.rot[1] * d2r), cy = std::cos(cam.rot[1] * d2r);
    float sr = std::sin(cam.rot[2] * d2r), cr = std::cos(cam.rot[2] * d2r);
    float F[3] = { cp * cy, cp * sy, sp };
    float R[3] = { sr * sp * cy - cr * sy, sr * sp * sy + cr * cy, -sr * cp };
    float U[3] = { -(cr * sp * cy + sr * sy), cy * sr - cr * sp * sy, cr * cp };
    float t = 1.0f / std::tan(cam.fov * 0.5f * d2r);
    const float nearPlane = 10.0f;   // Unreal default GNearClippingPlane

    float cb[36] = {
        cam.loc[0], cam.loc[1], cam.loc[2], 0,
        F[0], F[1], F[2], 0,
        R[0], R[1], R[2], 0,
        U[0], U[1], U[2], 0,
        t, t * (float)w / (float)h, nearPlane, 0,
        g_cube.x, g_cube.y, g_cube.z, g_cube.size,
        depthOk ? 1.0f : 0.0f, depthW / (float)w, depthH / (float)h, 0,
        84.0f, g_yOff, 0, 0,
        (float)g_entOrigin[0], (float)g_entOrigin[1], (float)g_entOrigin[2], 0,
    };
    D3D11_MAPPED_SUBRESOURCE m;
    if (FAILED(ctx->Map(g_cb, 0, D3D11_MAP_WRITE_DISCARD, 0, &m))) return;
    std::memcpy(m.pData, cb, sizeof(cb));
    ctx->Unmap(g_cb, 0);

    const float bf[4] = { 0, 0, 0, 0 };
    D3D11_VIEWPORT full{ 0, 0, (float)w, (float)h, 0, 1 };
    ctx->OMSetBlendState(g_opaque, bf, 0xFFFFFFFF);
    ctx->RSSetState(g_raster);
    ctx->RSSetViewports(1, &full);
    ctx->VSSetConstantBuffers(0, 1, &g_cb);
    ctx->PSSetConstantBuffers(0, 1, &g_cb);
    ctx->PSSetSamplers(0, 1, &g_point);
    ID3D11ShaderResourceView* srvs[2] = { depthOk ? g_depthSrv : nullptr, g_atlasSrv };
    ctx->PSSetShaderResources(0, 2, srvs);

    // Minecraft's blocks, with our own depth buffer so they hide each other.
    if ((wantMesh || wantEnt) && ensureOurDepth(w, h)) {
        ctx->ClearDepthStencilView(g_ourDsv, D3D11_CLEAR_DEPTH, 0.0f, 0);    // reversed Z: 0 = far
        ctx->OMSetRenderTargets(1, &rtv, g_ourDsv);
        ctx->OMSetDepthStencilState(g_ourDepthTest, 0);
        UINT stride = sizeof(hn::WorldVertex), offset = 0;
        ctx->IASetInputLayout(g_layout);
        ctx->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        if (wantMesh) {
            ctx->IASetVertexBuffers(0, 1, &g_vb, &stride, &offset);
            ctx->VSSetShader(g_vsMesh, nullptr, 0);
            ctx->PSSetShader(g_psMesh, nullptr, 0);
            ctx->Draw(g_vertexCount, 0);
        }
        if (wantEnt) {
            ctx->IASetVertexBuffers(0, 1, &g_entVb, &stride, &offset);
            ctx->VSSetShader(g_vsEnt, nullptr, 0);
            ctx->PSSetShader(g_psEnt, nullptr, 0);
            // Cutout first, then translucent: blended, but still WRITING depth, like Minecraft's entity_translucent.
            // Player skins are drawn as translucent; without depth writes the back of each body part was painted
            // over its front (unsorted triangles), so the model looked inside out. Fully transparent texels (the
            // unused parts of the hat/jacket layer) are discarded in psEnt, so they write no depth.
            for (int pass = 0; pass < 2; ++pass) {
                if (pass == 1) ctx->OMSetBlendState(g_alpha, bf, 0xFFFFFFFF);
                for (uint32_t i = 0; i < g_entBatchCount; ++i) {
                    const hn::WorldBatch& b = g_entBatches[i];
                    if ((int)(b.flags & 1) != pass || b.count == 0 || size_t(b.first) + b.count > g_entVertexCount) continue;
                    ID3D11ShaderResourceView* tex = b.texture == 0 ? g_atlasSrv : (b.texture < kMaxTextureId ? g_texSrv[b.texture] : nullptr);
                    if (!tex) continue;
                    ctx->PSSetShaderResources(1, 1, &tex);
                    ctx->Draw(b.count, b.first);
                }
            }
            ctx->OMSetBlendState(g_opaque, bf, 0xFFFFFFFF);
            ctx->OMSetDepthStencilState(g_ourDepthTest, 0);
            ctx->PSSetShaderResources(1, 1, &g_atlasSrv);
        }
        ctx->OMSetRenderTargets(1, &rtv, nullptr);
        ID3D11Buffer* noVb = nullptr;
        ctx->IASetVertexBuffers(0, 1, &noVb, &stride, &offset);
    }

    ctx->OMSetDepthStencilState(g_noDepth, 0);
    ctx->IASetInputLayout(nullptr);
    ctx->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    if (g_cube.on) {
        ctx->VSSetShader(g_vsCube, nullptr, 0);
        ctx->PSSetShader(g_psCube, nullptr, 0);
        ctx->Draw(36, 0);
        if (depthOk) {                                   // depth preview, top-right
            D3D11_VIEWPORT pip{ (float)w * 0.75f - 16.0f, 16.0f, (float)w * 0.25f, (float)h * 0.25f, 0, 1 };
            ctx->RSSetViewports(1, &pip);
            ctx->VSSetShader(g_vsQuad, nullptr, 0);
            ctx->PSSetShader(g_psDepth, nullptr, 0);
            ctx->Draw(3, 0);
            ctx->RSSetViewports(1, &full);
        }
    }
    ID3D11ShaderResourceView* none[2] = {};
    ctx->PSSetShaderResources(0, 2, none);

    if (g_worldBase) {
        auto* hdr = static_cast<hn::WorldHeader*>(g_worldBase);
        std::atomic_ref<uint64_t>(hdr->hostFrames).fetch_add(1, std::memory_order_relaxed);
        std::atomic_ref<uint32_t>(hdr->hostVertsDrawn).store(wantMesh ? g_vertexCount : 0, std::memory_order_relaxed);
    }
    if (g_frame % 60 == 0) writeStatus(depthOk);
}

void endFrame() {
    for (int i = 0; i < g_depthCount; ++i) g_depth[i].binds = 0;
    if (g_mhReady) watchTables();
}

}  // namespace hnworld
