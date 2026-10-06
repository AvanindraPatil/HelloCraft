// layout_test.cpp - checks struct offsets, coordinate maths, the seqlock and the
// SPSC rings (two threads, two separate views of one mapping).
// Build/run: protocol\build_tests.bat
#include <cmath>
#include <cstdio>
#include <thread>

#include "../hn_coords.h"
#include "../hn_shm.h"

using namespace hn;

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++failures; std::printf("FAIL %s:%d  ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

static void testLayout() {
    CHECK(offsetof(Header, hostHeartbeatMs) == 16, "hb offset");
    CHECK(offsetof(HostState, posX) == 16, "posX offset");
    CHECK(offsetof(McState, x) == 8, "mc x offset");
    CHECK(offsetof(McState, hotbarItem) == 56, "hotbarItem offset");
    CHECK(offsetof(McState, targetX) == 76, "targetX offset");
    CHECK(offsetof(McState, tickCounter) == 96, "tickCounter offset");
    CHECK(kMappingBytes % 0x1000 == 0, "mapping page aligned");
    std::printf("layout: mapping=%zu bytes, McState.hotbarItem@%zu targetX@%zu tickCounter@%zu\n",
                kMappingBytes, offsetof(McState, hotbarItem), offsetof(McState, targetX), offsetof(McState, tickCounter));
}

static void testCoords() {
    // Round trip.
    Vec3d m = hostToMc(1234.0, -567.0, 890.0);
    Vec3d h = mcToHost(m.x, m.y, m.z);
    CHECK(std::fabs(h.x - 1234.0) < 1e-9 && std::fabs(h.y + 567.0) < 1e-9 && std::fabs(h.z - 890.0) < 1e-9, "pos roundtrip");
    // Axis check: moving +X host -> +X mc, +Y host -> +Z mc, +Z host -> +Y mc.
    Vec3d dx = hostToMc(100, 0, 0), dy = hostToMc(0, 100, 0), dz = hostToMc(0, 0, 100);
    CHECK(dx.x > 0 && dy.z > 0 && dz.y > 0, "axes");
    // Yaw: host forward (cos y, sin y) must equal MC forward (-sin y', cos y') after mapping.
    const double pi = 3.14159265358979323846;
    for (double hy = -180; hy <= 180; hy += 15) {
        double hx = std::cos(hy * pi / 180), hyv = std::sin(hy * pi / 180);
        Vec3d mf = hostToMc(hx, hyv, 0);                       // forward in MC space
        double my = hostYawToMc(hy) * pi / 180;
        double ex = -std::sin(my), ez = std::cos(my);          // MC forward from yaw
        CHECK(std::fabs(mf.x - ex / kUnitsPerBlock) < 1e-9 && std::fabs(mf.z - ez / kUnitsPerBlock) < 1e-9, "yaw %g", hy);
        CHECK(std::fabs(std::remainder(mcYawToHost(hostYawToMc(hy)) - hy, 360.0)) < 1e-9, "yaw inverse %g", hy);
        // Handedness: the player's RIGHT must be the same direction in both games (A/D not swapped).
        // Unreal (left-handed) right = (-sin y, cos y); Minecraft right = forward x up = (-cos y', -sin y').
        Vec3d hr = hostToMc(-std::sin(hy * pi / 180), std::cos(hy * pi / 180), 0);
        CHECK(std::fabs(hr.x - (-std::cos(my)) / kUnitsPerBlock) < 1e-9 && std::fabs(hr.z - (-std::sin(my)) / kUnitsPerBlock) < 1e-9,
              "right is right at yaw %g", hy);
    }
}

static void testSeqlockAndRings() {
    const char* name = "Local\\HelloNeighborMC_test";
    Mapping a, b;  // two views of the same mapping, like two processes
    CHECK(a.open(name) && a.created, "open a");
    CHECK(b.open(name) && !b.created, "open b");
    CHECK(b.ready(), "magic visible");

    // A writer stuck mid-write (odd seq): seqRead fails and leaves the caller's last good value untouched.
    {
        McState* slot = b.mcState();
        McState good{}; good.tickCounter = 777; good.flags = kMcInWorld | kMcDriving;
        std::atomic_ref<uint32_t>(slot->seq).store(1);   // odd = write in progress
        slot->tickCounter = 0; slot->flags = 0;           // half-written garbage
        CHECK(!seqRead(slot, good, 200), "seqRead fails while the writer is mid-write");
        CHECK(good.tickCounter == 777 && good.flags == (kMcInWorld | kMcDriving), "failed seqRead leaves the last good value");
        std::atomic_ref<uint32_t>(slot->seq).store(2);
    }

    constexpr int N = 200000;
    // Seqlock: writer keeps x == y == z == tick; reader must never see a torn mix.
    std::atomic<bool> stop{false};
    std::atomic<long> torn{0}, reads{0};
    std::thread writer([&] {
        for (int i = 1; i <= N; ++i) {
            McState s{}; s.x = s.y = s.z = i; s.tickCounter = (uint64_t)i;
            seqWrite(a.mcState(), s);
        }
        stop = true;
    });
    std::thread reader([&] {
        McState s;
        while (!stop) {
            if (!seqRead(b.mcState(), s)) continue;
            ++reads;
            if (s.x != s.y || s.y != s.z || (uint64_t)s.x != s.tickCounter) ++torn;
        }
    });
    writer.join(); reader.join();
    CHECK(torn == 0, "torn seqlock reads: %ld of %ld", torn.load(), reads.load());
    std::printf("seqlock: %ld stable reads, %ld torn\n", reads.load(), torn.load());

    // Ring: producer pushes 0..N-1 in order, consumer must receive them in order.
    Ring prod = cmdRing(a), cons = cmdRing(b);
    long got = 0, bad = 0, dropped = 0;
    std::thread p([&] {
        for (uint32_t i = 0; i < (uint32_t)N; ++i) {
            Message m{}; m.type = kCmdBreakBlock; m.a = (int32_t)i; m.seq = i;
            while (!prod.push(m)) { ++dropped; std::this_thread::yield(); }
        }
    });
    std::thread c([&] {
        Message m; int32_t expect = 0;
        while (got < N) {
            if (!cons.pop(m)) { std::this_thread::yield(); continue; }
            if (m.a != expect || m.type != kCmdBreakBlock) ++bad;
            ++expect; ++got;
        }
    });
    p.join(); c.join();
    CHECK(got == N && bad == 0, "ring got=%ld bad=%ld", got, bad);
    std::printf("ring: %ld messages in order, %ld bad, producer retried %ld times (ring full)\n", got, bad, dropped);

    // Full / empty edges.
    Ring r = eventRing(a);
    Message m{}, out;
    CHECK(!eventRing(b).pop(out), "empty pop");
    size_t pushed = 0; while (r.push(m)) ++pushed;
    CHECK(pushed == kEventRingSlots, "ring capacity %zu", pushed);
    a.close(); b.close();
}

int main() {
    testLayout();
    testCoords();
    testSeqlockAndRings();
    std::printf(failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", failures);
    return failures ? 1 : 0;
}
