// hn_overlay.h - the overlay image channel: Minecraft's hand + HUD + screens, rendered on a transparent
// background, travelling to the Hello Neighbor graphics plugin (native/hn_gfx) which draws it over the game.
//
// A separate mapping from hn_protocol.h because it is large (three full-resolution RGBA8 frames).
// Triple buffer, one producer (Minecraft), one consumer (hn_gfx):
//   ctl bits 0-1 = LATEST slot (3 = none yet), bits 8-9 = slot the consumer is READING (3 = none).
//   Producer: pick a slot that is neither LATEST nor READING, write pixels + slot header, then CAS LATEST = slot.
//   Consumer: CAS READING = LATEST, copy that slot, CAS READING = none.
//   The consumer only ever marks the current LATEST, which the producer never writes, so no torn frames.
// Pixels: RGBA8, premultiplied alpha (Minecraft blends GUI onto a (0,0,0,0) clear), rows bottom-up when
// slot flags bit 0 is set (OpenGL readback order).
#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace hn {

constexpr char     kOverlayMappingName[] = "Local\\HelloNeighborMC_overlay_v0";
constexpr uint32_t kOverlayMagic   = 0x564F4E48;  // "HNOV"
constexpr uint32_t kOverlayVersion = 1;   // v1: input ring + mcFlags
constexpr uint32_t kOverlayMaxW = 3840, kOverlayMaxH = 2160;
constexpr size_t   kOverlaySlots = 3;
constexpr size_t   kOverlaySlotBytes = size_t(kOverlayMaxW) * kOverlayMaxH * 4;
constexpr size_t   kOverlayPixelsOff = 65536;   // header at 0, input ring at 4096, pixels from 64 KiB
constexpr size_t   kOverlayMappingBytes = kOverlayPixelsOff + kOverlaySlots * kOverlaySlotBytes;
constexpr uint32_t kOverlayNone = 3;
constexpr uint32_t kOverlayFlagBottomUp = 1u << 0;
constexpr uint32_t kOverlayMcScreenOpen = 1u << 0;   // a Minecraft screen (inventory, chat...) wants keyboard + cursor
constexpr uint32_t kOverlayMcMoveKeys = 1u << 1;     // Minecraft moves the player: W A S D, Space, Shift, Ctrl go to it
constexpr uint32_t kOverlayHostMenu = 1u << 2;       // Hello Neighbor's own menu is open (paused): ALL input goes to it

// ---- input ring: host (hn_gfx window procedure) -> Minecraft. Same SPSC layout as hn_protocol.h rings. ----
constexpr size_t kInputRingOff = 4096;        // head u64 at +0, tail u64 at +64, events at +128
constexpr size_t kInputRingSlots = 2048;      // power of two
struct InputEvent {                           // 16 bytes
    uint16_t type;                            // kIn*
    uint16_t code;                            // SDL scancode (keys) or SDL button (1 left, 2 middle, 3 right)
    int32_t a, b, c;
};
enum InputType : uint16_t {
    kInKey = 1,          // code = SDL scancode, a = 1 down / 0 up
    kInMouseButton = 2,  // code = SDL button, a = 1 down / 0 up
    kInScroll = 3,       // a = wheel delta (120 per notch, positive = away from the user)
    kInCursor = 4,       // a, b = cursor position in Minecraft window pixels (only while a screen is open)
    kInText = 5,         // a = Unicode code point
    kInReleaseAll = 6,   // focus lost: lift every key and button
};
static_assert(sizeof(InputEvent) == 16, "InputEvent size");
static_assert(kInputRingOff + 128 + kInputRingSlots * 16 <= 65536, "input ring fits before pixels");

struct OverlaySlot {          // 32 bytes
    uint32_t width, height;
    uint32_t flags;           // kOverlayFlag*
    uint32_t pad;
    uint64_t frameId;         // producer's running frame number
    uint64_t pad2;
};
static_assert(sizeof(OverlaySlot) == 32, "OverlaySlot size");

struct OverlayHeader {        // 192 bytes at offset 0
    uint32_t magic;
    uint32_t version;
    uint32_t hostViewportW;   // written by the host every frame: the size Minecraft should render at
    uint32_t hostViewportH;
    uint32_t ctl;             // see above; atomic
    uint32_t pad;
    uint64_t framesPublished; // producer
    uint64_t framesShown;     // consumer
    uint32_t mcFlags;         // written by Minecraft every frame: kOverlayMc*
    uint32_t guiScale;        // Minecraft GUI scale (for cursor drawing)
    // Minecraft -> host: Hello Neighbor inputs to press, so its own code does the action (open, pick up, use, throw).
    // kTapUse/Apply/Throw: bit 0 = Minecraft's key is HELD now, bits 1-31 = running count of presses. hn_gfx presses
    // Hello Neighbor's key on every new press and keeps it down while held (pick-up needs a hold), at least a few
    // frames. kTapSlot: bits 0-23 counter, bits 24-31 = slot 1..4 (a short tap).
    uint32_t hostTaps[4];
    OverlaySlot slots[kOverlaySlots];   // at offset 64
    uint8_t  reserved2[32];
};
static_assert(sizeof(OverlayHeader) == 192, "OverlayHeader size");
static_assert(offsetof(OverlayHeader, slots) == 64, "slots offset");
static_assert(offsetof(OverlayHeader, hostTaps) == 48, "hostTaps offset");
enum HostTap : uint32_t {
    kTapUse = 0,    // E: pick up / InputAction
    kTapApply = 1,  // left mouse button: InputApply (use the held item, open, press)
    kTapThrow = 2,  // right mouse button: InputThrow / InputPutDown
    kTapSlot = 3,   // 1-4: select an inventory slot (slot in bits 24-31)
};
static_assert(sizeof(OverlayHeader) <= kOverlayPixelsOff, "header fits before pixels");

inline uint8_t* overlayPixels(uint8_t* base, uint32_t slot) { return base + kOverlayPixelsOff + slot * kOverlaySlotBytes; }

// ---- producer ----
inline uint32_t overlayPickWriteSlot(OverlayHeader* h) {
    uint32_t ctl = std::atomic_ref<uint32_t>(h->ctl).load(std::memory_order_acquire);
    uint32_t latest = ctl & 3, reading = (ctl >> 8) & 3;
    for (uint32_t s = 0; s < kOverlaySlots; ++s) if (s != latest && s != reading) return s;
    return kOverlayNone;  // cannot happen with 3 slots
}

inline void overlayPublish(OverlayHeader* h, uint32_t slot) {
    std::atomic_ref<uint32_t> ctl(h->ctl);
    uint32_t cur = ctl.load(std::memory_order_relaxed);
    while (!ctl.compare_exchange_weak(cur, (cur & ~3u) | slot, std::memory_order_release, std::memory_order_relaxed)) {}
    std::atomic_ref<uint64_t>(h->framesPublished).fetch_add(1, std::memory_order_relaxed);
}

// ---- consumer ----
// Returns the slot now reserved for reading, or kOverlayNone if nothing has been published.
inline uint32_t overlayAcquire(OverlayHeader* h) {
    std::atomic_ref<uint32_t> ctl(h->ctl);
    uint32_t cur = ctl.load(std::memory_order_acquire);
    for (;;) {
        uint32_t latest = cur & 3;
        if (latest == kOverlayNone) return kOverlayNone;
        uint32_t want = (cur & ~(3u << 8)) | (latest << 8);
        if (ctl.compare_exchange_weak(cur, want, std::memory_order_acq_rel, std::memory_order_acquire)) return latest;
    }
}

inline void overlayRelease(OverlayHeader* h) {
    std::atomic_ref<uint32_t> ctl(h->ctl);
    uint32_t cur = ctl.load(std::memory_order_relaxed);
    while (!ctl.compare_exchange_weak(cur, cur | (3u << 8), std::memory_order_release, std::memory_order_relaxed)) {}
}

// A fresh (zeroed) mapping must start with LATEST = READING = none.
inline void overlayInitIfNew(OverlayHeader* h, bool created) {
    if (!created) return;
    h->version = kOverlayVersion;
    std::atomic_ref<uint32_t>(h->ctl).store(kOverlayNone | (kOverlayNone << 8), std::memory_order_relaxed);
    std::atomic_ref<uint32_t>(h->magic).store(kOverlayMagic, std::memory_order_release);
}

}  // namespace hn

#ifdef _WIN32
#include <windows.h>
namespace hn {
// Create-or-open the overlay mapping (whichever side starts first creates it).
struct OverlayMapping {
    HANDLE hMap = nullptr;
    uint8_t* base = nullptr;
    bool created = false;
    bool open(const char* name = kOverlayMappingName) {
        hMap = CreateFileMappingA(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, (DWORD)(uint64_t(kOverlayMappingBytes) >> 32),
                                  (DWORD)(kOverlayMappingBytes & 0xFFFFFFFFu), name);
        if (!hMap) return false;
        created = GetLastError() != ERROR_ALREADY_EXISTS;
        base = (uint8_t*)MapViewOfFile(hMap, FILE_MAP_ALL_ACCESS, 0, 0, kOverlayMappingBytes);
        if (!base) { CloseHandle(hMap); hMap = nullptr; return false; }
        overlayInitIfNew(header(), created);
        return true;
    }
    void close() {
        if (base) UnmapViewOfFile(base);
        if (hMap) CloseHandle(hMap);
        base = nullptr; hMap = nullptr;
    }
    OverlayHeader* header() const { return (OverlayHeader*)base; }
    bool ready() const { return base && std::atomic_ref<uint32_t>(header()->magic).load(std::memory_order_acquire) == kOverlayMagic; }
};
}  // namespace hn
#endif

namespace hn {
// Input ring producer (host). Returns false if full (event dropped).
inline bool inputPush(uint8_t* base, const InputEvent& e) {
    std::atomic_ref<uint64_t> head(*(uint64_t*)(base + kInputRingOff)), tail(*(uint64_t*)(base + kInputRingOff + 64));
    uint64_t h = head.load(std::memory_order_relaxed), t = tail.load(std::memory_order_acquire);
    if (h - t >= kInputRingSlots) return false;
    ((InputEvent*)(base + kInputRingOff + 128))[h & (kInputRingSlots - 1)] = e;
    head.store(h + 1, std::memory_order_release);
    return true;
}
// Input ring consumer (Minecraft; C++ side only used by tests).
inline bool inputPop(uint8_t* base, InputEvent& e) {
    std::atomic_ref<uint64_t> head(*(uint64_t*)(base + kInputRingOff)), tail(*(uint64_t*)(base + kInputRingOff + 64));
    uint64_t t = tail.load(std::memory_order_relaxed), h = head.load(std::memory_order_acquire);
    if (t == h) return false;
    e = ((InputEvent*)(base + kInputRingOff + 128))[t & (kInputRingSlots - 1)];
    tail.store(t + 1, std::memory_order_release);
    return true;
}
}  // namespace hn
