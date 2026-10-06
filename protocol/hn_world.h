// hn_world.h - the world channel (step 4): Minecraft's blocks as a textured triangle mesh, for hn_gfx to draw in
// Hello Neighbor's 3D scene. Mirrored in fabric-mod/src/main/java/dev/hnmc/link/WorldLink.java.
//
// Mapping "Local\HelloNeighborMC_world_v2" (created by whoever opens it first, zero-filled):
//   [0, 256)            WorldHeader
//   [kMeshOff, ...)     2 mesh slots of kMaxVerts WorldVertex each (triangle list, 3 vertices per triangle)
//   [kAtlasOff, ...)    the block atlas, RGBA8, atlasW x atlasH, rows top-down (v = 0 is row 0)
//   [kWorldTexTableOff) v2: texture table (WorldTexture[kWorldMaxTextures]); pixels live in the texture heap
//   [kWorldEntOff, ...) v2: 2 entity slots: WorldBatch[kWorldMaxBatches] then WorldVertex[kWorldEntMaxVerts]
//   [kWorldTexHeapOff)  v2: texture heap, RGBA8 rows top-down, appended by Minecraft
//
// Entities (v2): the player model in third person, mobs, dropped items, chests/beds/signs, particles, posed by
// Minecraft's own renderers every frame. Vertices are relative to entOrigin (Minecraft blocks). Each batch uses
// one texture: id 0 = the block atlas above, other ids = the texture table. Same ACTIVE/READING rule in entCtl.
//
// Mesh slots: Minecraft writes into a slot that is neither ACTIVE nor being READ, then makes it ACTIVE and bumps
// meshSeq. The host marks the slot it copies as READING while it copies. Both live in `ctl`
// (bits 0-1 ACTIVE, bits 8-9 READING, 3 = none), always changed with compare-and-swap.
// Atlas: written once (and again after a resource reload), then atlasSeq is bumped.
#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace hn {

constexpr const char* kWorldMappingName = "Local\\HelloNeighborMC_world_v2";
constexpr uint32_t kWorldMagic = 0x44574E48;   // "HNWD"
constexpr uint32_t kWorldVersion = 2;
constexpr uint32_t kWorldNone = 3;

struct WorldVertex {        // 24 bytes
    float x, y, z;          // Minecraft world coordinates (blocks)
    float u, v;             // block atlas UV
    uint32_t color;         // RGBA8 (r in the low byte): tint * face shade; a = 255
};
static_assert(sizeof(WorldVertex) == 24, "WorldVertex size");

struct WorldHeader {
    uint32_t magic;
    uint32_t version;
    uint32_t ctl;           // ACTIVE bits 0-1, READING bits 8-9
    uint32_t pad0;
    uint64_t meshSeq;       // bumps on every mesh publish
    uint32_t vertexCount[2];
    uint32_t atlasW, atlasH;
    uint64_t atlasSeq;      // bumps after atlas pixels are in place
    uint64_t hostFrames;    // host: frames drawn with this channel (debug / liveness)
    uint32_t hostVertsDrawn;
    uint32_t pad1;
    // v2
    uint32_t texCount;       // texture table entries published (append-only)
    uint32_t entCtl;         // entity slots: ACTIVE bits 0-1, READING bits 8-9
    uint64_t entSeq;         // bumps on every entity publish
    uint32_t entBatchCount[2];
    uint32_t entVertexCount[2];
    double   entOrigin[2][3];
};
static_assert(offsetof(WorldHeader, meshSeq) == 16, "meshSeq");
static_assert(offsetof(WorldHeader, vertexCount) == 24, "vertexCount");
static_assert(offsetof(WorldHeader, atlasW) == 32, "atlasW");
static_assert(offsetof(WorldHeader, atlasSeq) == 40, "atlasSeq");
static_assert(offsetof(WorldHeader, hostFrames) == 48, "hostFrames");
static_assert(offsetof(WorldHeader, hostVertsDrawn) == 56, "hostVertsDrawn");
static_assert(offsetof(WorldHeader, texCount) == 64, "texCount");
static_assert(offsetof(WorldHeader, entCtl) == 68, "entCtl");
static_assert(offsetof(WorldHeader, entSeq) == 72, "entSeq");
static_assert(offsetof(WorldHeader, entBatchCount) == 80, "entBatchCount");
static_assert(offsetof(WorldHeader, entVertexCount) == 88, "entVertexCount");
static_assert(offsetof(WorldHeader, entOrigin) == 96, "entOrigin");
static_assert(sizeof(WorldHeader) <= 4096, "header");

struct WorldTexture {       // 16 bytes
    uint32_t id, width, height;
    uint32_t offset;        // bytes into the texture heap
};
struct WorldBatch {         // 16 bytes
    uint32_t texture;       // 0 = block atlas, else a WorldTexture id
    uint32_t first, count;  // vertices
    uint32_t flags;         // bit 0: translucent
};

constexpr size_t kWorldMaxVerts = 6 * 65536;                     // 65536 quads
constexpr size_t kWorldSlotBytes = kWorldMaxVerts * sizeof(WorldVertex);
constexpr size_t kWorldMeshOff = 65536;
constexpr size_t kWorldAtlasOff = kWorldMeshOff + 2 * kWorldSlotBytes;
constexpr uint32_t kWorldAtlasMax = 4096;
static_assert(kWorldAtlasOff % 65536 == 0, "atlas aligned");
constexpr size_t kWorldTexTableOff = 4096;
constexpr uint32_t kWorldMaxTextures = 512;
constexpr size_t kWorldEntOff = kWorldAtlasOff + size_t(kWorldAtlasMax) * kWorldAtlasMax * 4;
constexpr uint32_t kWorldMaxBatches = 1024;
constexpr size_t kWorldEntMaxVerts = 196608;
constexpr size_t kWorldEntSlotBytes = 73 * 65536;
static_assert(kWorldMaxBatches * sizeof(WorldBatch) + kWorldEntMaxVerts * sizeof(WorldVertex) <= kWorldEntSlotBytes, "entity slot");
constexpr size_t kWorldTexHeapOff = kWorldEntOff + 2 * kWorldEntSlotBytes;
constexpr size_t kWorldTexHeapBytes = 64u << 20;
constexpr size_t kWorldMappingBytes = kWorldTexHeapOff + kWorldTexHeapBytes;
static_assert(kWorldTexTableOff + kWorldMaxTextures * sizeof(WorldTexture) <= kWorldMeshOff, "texture table");

inline WorldVertex* worldSlot(void* base, uint32_t slot) {
    return reinterpret_cast<WorldVertex*>(static_cast<uint8_t*>(base) + kWorldMeshOff + slot * kWorldSlotBytes);
}
inline const uint8_t* worldAtlas(const void* base) { return static_cast<const uint8_t*>(base) + kWorldAtlasOff; }
inline const WorldTexture* worldTextures(const void* base) { return reinterpret_cast<const WorldTexture*>(static_cast<const uint8_t*>(base) + kWorldTexTableOff); }
inline const uint8_t* worldTexHeap(const void* base) { return static_cast<const uint8_t*>(base) + kWorldTexHeapOff; }
inline const WorldBatch* worldEntBatches(const void* base, uint32_t slot) {
    return reinterpret_cast<const WorldBatch*>(static_cast<const uint8_t*>(base) + kWorldEntOff + slot * kWorldEntSlotBytes);
}
inline const WorldVertex* worldEntVerts(const void* base, uint32_t slot) {
    return reinterpret_cast<const WorldVertex*>(static_cast<const uint8_t*>(base) + kWorldEntOff + slot * kWorldEntSlotBytes + kWorldMaxBatches * sizeof(WorldBatch));
}

// Host: mark the ACTIVE slot as READING. Returns the slot, or kWorldNone.
inline uint32_t worldAcquireCtl(uint32_t& word) {
    std::atomic_ref<uint32_t> ctl(word);
    uint32_t cur = ctl.load(std::memory_order_acquire);
    for (;;) {
        uint32_t active = cur & 3;
        if (active == kWorldNone) return kWorldNone;
        uint32_t next = (cur & ~0x300u) | (active << 8);
        if (ctl.compare_exchange_weak(cur, next, std::memory_order_acq_rel)) return active;
    }
}
inline void worldReleaseCtl(uint32_t& word) {
    std::atomic_ref<uint32_t> ctl(word);
    uint32_t cur = ctl.load(std::memory_order_relaxed);
    while (!ctl.compare_exchange_weak(cur, cur | 0x300u, std::memory_order_acq_rel)) {}
}
inline uint32_t worldAcquire(WorldHeader* h) { return worldAcquireCtl(h->ctl); }
inline void worldRelease(WorldHeader* h) { worldReleaseCtl(h->ctl); }

}  // namespace hn
