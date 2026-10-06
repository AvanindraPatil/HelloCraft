// overlay_test.cpp - the overlay triple buffer: no torn frames under a producer/consumer race,
// frames arrive in order, and a fresh mapping starts empty. Build/run: protocol\build_tests.bat
#include <cstdio>
#include <thread>

#include "../hn_overlay.h"

using namespace hn;

int main() {
    int failures = 0;
    const char* name = "Local\\HelloNeighborMC_overlay_test";
    OverlayMapping prod, cons;
    if (!prod.open(name) || !cons.open(name)) { std::printf("FAIL: open\n"); return 1; }
    if (!prod.created || cons.created || !cons.ready()) { std::printf("FAIL: create/open flags\n"); ++failures; }
    if (overlayAcquire(cons.header()) != kOverlayNone) { std::printf("FAIL: fresh mapping not empty\n"); ++failures; }

    // Small frames (64x64) so the race runs many iterations. Every pixel of frame N holds N.
    const uint32_t W = 64, H = 64, N = 20000;
    std::atomic<bool> done{false};
    long torn = 0, seen = 0, backwards = 0;
    std::thread producer([&] {
        for (uint32_t f = 1; f <= N; ++f) {
            uint32_t s = overlayPickWriteSlot(prod.header());
            uint32_t* px = (uint32_t*)overlayPixels(prod.base, s);
            for (uint32_t i = 0; i < W * H; ++i) px[i] = f;
            OverlaySlot& sl = prod.header()->slots[s];
            sl.width = W; sl.height = H; sl.flags = kOverlayFlagBottomUp; sl.frameId = f;
            overlayPublish(prod.header(), s);
        }
        done = true;
    });
    std::thread consumer([&] {
        uint64_t last = 0;
        while (!done) {
            uint32_t s = overlayAcquire(cons.header());
            if (s == kOverlayNone) continue;
            const OverlaySlot& sl = cons.header()->slots[s];
            uint64_t id = sl.frameId;
            const uint32_t* px = (const uint32_t*)overlayPixels(cons.base, s);
            for (uint32_t i = 0; i < W * H; ++i) if (px[i] != id) { ++torn; break; }
            if (id < last) ++backwards;
            last = id; ++seen;
            overlayRelease(cons.header());
        }
    });
    producer.join(); consumer.join();
    std::printf("overlay: %u frames produced, %ld acquired, %ld torn, %ld out of order, published=%llu\n",
                N, seen, torn, backwards, (unsigned long long)prod.header()->framesPublished);
    if (torn || backwards || seen == 0 || prod.header()->framesPublished != N) { std::printf("FAIL: overlay race\n"); ++failures; }
    prod.close(); cons.close();
    std::printf(failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", failures);
    return failures ? 1 : 0;
}
