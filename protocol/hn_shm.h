// hn_shm.h - helpers for the shared mapping: create/open, seqlock slots, SPSC rings.
// Header-only, Windows + C++20. Used by the host plugin, tests and the fake clients.
#pragma once
#include <windows.h>

#include <atomic>
#include <cstring>
#include <thread>

#include "hn_protocol.h"

namespace hn {

struct Mapping {
    HANDLE hMap = nullptr;
    uint8_t* base = nullptr;
    bool created = false;  // true if this call created the mapping

    // Opens the mapping, creating it (zeroed, header initialised) if absent.
    // `name` lets tests use a private mapping instead of the real one.
    bool open(const char* name = kMappingName) {
        hMap = CreateFileMappingA(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0,
                                  (DWORD)kMappingBytes, name);
        if (!hMap) return false;
        created = (GetLastError() != ERROR_ALREADY_EXISTS);
        base = (uint8_t*)MapViewOfFile(hMap, FILE_MAP_ALL_ACCESS, 0, 0, kMappingBytes);
        if (!base) { CloseHandle(hMap); hMap = nullptr; return false; }
        if (created) {  // fresh pages are zero already
            auto* h = header();
            h->version = kVersion;
            std::atomic_ref<uint32_t>(h->magic).store(kMagic, std::memory_order_release);
        }
        return true;
    }
    void close() {
        if (base) UnmapViewOfFile(base);
        if (hMap) CloseHandle(hMap);
        base = nullptr; hMap = nullptr;
    }
    bool ready() const { return base && std::atomic_ref<uint32_t>(header()->magic).load(std::memory_order_acquire) == kMagic; }

    Header*    header()    const { return (Header*)(base + kOffHeader); }
    HostState* hostState() const { return (HostState*)(base + kOffHostState); }
    McState*   mcState()   const { return (McState*)(base + kOffMcState); }
    uint8_t*   cmdRing()   const { return base + kOffCmdRing; }
    uint8_t*   eventRing() const { return base + kOffEventRing; }
};

// ---- Seqlock -----------------------------------------------------------
// Single writer per slot. Both functions copy the whole struct (seq included
// in the layout, but the helper manages it).
template <class T>
void seqWrite(T* slot, const T& value) {
    std::atomic_ref<uint32_t> seq(slot->seq);
    uint32_t s = seq.load(std::memory_order_relaxed);
    seq.store(s + 1, std::memory_order_relaxed);          // odd: writing
    std::atomic_thread_fence(std::memory_order_release);
    std::memcpy((char*)slot + 4, (const char*)&value + 4, sizeof(T) - 4);
    seq.store(s + 2, std::memory_order_release);          // even: stable
}

// Returns false if it could not get a stable copy after `tries` attempts.
template <class T>
// `out` is only written with a clean copy: on failure (the writer kept the slot busy) it is left untouched, so
// callers can keep using their last good value. (It used to be left half-copied, and the bridge sent that; with
// Minecraft writing McState every frame that happened, read as "Minecraft restarted" / "not driving".)
bool seqRead(const T* slot, T& out, int tries = 4096) {
    std::atomic_ref<const uint32_t> seq(slot->seq);
    T tmp;
    for (int i = 0; i < tries; ++i) {
        uint32_t s1 = seq.load(std::memory_order_acquire);
        if (s1 & 1) { if (i > 64) std::this_thread::yield(); continue; }
        std::memcpy(&tmp, slot, sizeof(T));
        std::atomic_thread_fence(std::memory_order_acquire);
        if (seq.load(std::memory_order_relaxed) == s1) { tmp.seq = s1; out = tmp; return true; }
    }
    return false;
}

// ---- SPSC ring -----------------------------------------------------------
struct Ring {
    uint8_t* base;
    size_t slots;  // power of two

    std::atomic_ref<uint64_t> head() const { return std::atomic_ref<uint64_t>(*(uint64_t*)(base + kRingHeadOff)); }
    std::atomic_ref<uint64_t> tail() const { return std::atomic_ref<uint64_t>(*(uint64_t*)(base + kRingTailOff)); }
    Message* slot(uint64_t i) const { return (Message*)(base + kRingDataOff) + (i & (slots - 1)); }

    // Producer side. Returns false if the ring is full (message dropped).
    bool push(const Message& m) const {
        uint64_t h = head().load(std::memory_order_relaxed);
        uint64_t t = tail().load(std::memory_order_acquire);
        if (h - t >= slots) return false;
        *slot(h) = m;
        head().store(h + 1, std::memory_order_release);
        return true;
    }
    // Consumer side. Returns false if empty.
    bool pop(Message& m) const {
        uint64_t t = tail().load(std::memory_order_relaxed);
        uint64_t h = head().load(std::memory_order_acquire);
        if (t == h) return false;
        m = *slot(t);
        tail().store(t + 1, std::memory_order_release);
        return true;
    }
};

inline Ring cmdRing(const Mapping& m)   { return { m.cmdRing(),   kCmdRingSlots }; }
inline Ring eventRing(const Mapping& m) { return { m.eventRing(), kEventRingSlots }; }

}  // namespace hn
