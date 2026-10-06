// fake_host.cpp - stands in for the Hello Neighbor plugin. Walks the "player" in a
// circle at 60 Hz, teleports once, sends commands, and checks that the other
// process (fake_mc) mirrors position, acks the teleport and answers each command.
// Exit code 0 = all checks passed.
#include <cmath>
#include <cstdio>
#include <vector>

#include "../hn_coords.h"
#include "../hn_shm.h"

using namespace hn;

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++failures; std::printf("FAIL: "); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

int main(int argc, char** argv) {
    const char* name = argc > 1 ? argv[1] : kMappingName;
    const double seconds = argc > 2 ? atof(argv[2]) : 5.0;

    Mapping map;
    if (!map.open(name)) { std::printf("fake_host: cannot open mapping\n"); return 2; }
    map.header()->hostPid = GetCurrentProcessId();
    std::printf("fake_host: up (pid %lu, created mapping: %d)\n", GetCurrentProcessId(), map.created);

    // Wait for MC (it publishes its pid + heartbeat).
    ULONGLONG t0 = GetTickCount64();
    while (map.header()->mcHeartbeatMs == 0) {
        if (GetTickCount64() - t0 > 10000) { std::printf("fake_host: MC never showed up\n"); return 3; }
        Sleep(20);
    }
    std::printf("fake_host: MC is alive (pid %u)\n", map.header()->mcPid);

    Ring cmds = cmdRing(map), events = eventRing(map);
    const double R = 500.0, W = 1.0;                  // radius (uu), angular speed (rad/s)
    const double cx = -2316.7, cy = 762.8, cz = 195.0; // near the real Act1 spawn
    uint32_t teleportSeq = 0;
    bool teleported = false;
    uint32_t sent = 0, received = 0;
    std::vector<Message> blockEvents;
    double maxErrBlocks = 0, sumErr = 0; long errSamples = 0;
    uint32_t lastTick = 0, tickStalls = 0;
    HostState hs{};
    LARGE_INTEGER freq, a; QueryPerformanceFrequency(&freq); QueryPerformanceCounter(&a);

    double nextCmd = 0.5, tNow = 0;
    while (tNow < seconds) {
        LARGE_INTEGER b; QueryPerformanceCounter(&b);
        tNow = double(b.QuadPart - a.QuadPart) / double(freq.QuadPart);

        double ang = W * tNow;
        hs.flags = kHostInGame;
        hs.posX = cx + R * std::cos(ang); hs.posY = cy + R * std::sin(ang); hs.posZ = cz;
        hs.yaw = (float)(std::fmod(ang * 180.0 / 3.14159265358979 + 90.0, 360.0));  // tangent direction
        hs.onGround = 1;
        if (!teleported && tNow > seconds * 0.5) {  // teleport once, mid-run
            teleported = true; ++teleportSeq;
            hs.posX += 50000; hs.posY -= 20000;     // jump like the Act1 intro did
        }
        hs.teleportSeq = teleportSeq;
        seqWrite(map.hostState(), hs);
        map.header()->hostHeartbeatMs = GetTickCount64();

        if (tNow >= nextCmd) {
            Message m{}; m.type = (sent % 3 == 2) ? kCmdPlaceBlock : kCmdBreakBlock;
            m.a = (int32_t)sent; m.b = 64; m.c = -(int32_t)sent; m.d = 1; m.seq = sent;
            if (cmds.push(m)) ++sent;
            nextCmd += 0.5;
        }

        Message ev;
        while (events.pop(ev)) { ++received; if (ev.type == kEvBlockChange) blockEvents.push_back(ev); }

        McState ms;
        if (seqRead(map.mcState(), ms) && (ms.flags & kMcInWorld) && ms.teleportAck == hs.teleportSeq) {
            // MC runs at 20 Hz, so it lags the host by up to ~50-100 ms. Compare to what
            // the host had ~100 ms ago, not what it has now (skip right after a teleport).
            double lagT = tNow - 0.1; if (lagT < 0) lagT = 0;
            double la = W * lagT;
            double lx = cx + R * std::cos(la), ly = cy + R * std::sin(la);
            if (!teleported) {
                Vec3d exp = hostToMc(lx, ly, cz);
                double err = std::hypot(ms.x - exp.x, ms.z - exp.z);
                if (tNow > 0.3) { maxErrBlocks = std::fmax(maxErrBlocks, err); sumErr += err; ++errSamples; }
            }
            if (ms.tickCounter != lastTick) { lastTick = (uint32_t)ms.tickCounter; tickStalls = 0; } else ++tickStalls;
        }
        Sleep(16);
    }

    // Drain: give MC a moment to answer the last commands.
    for (int i = 0; i < 40 && received < sent; ++i) {
        Message ev; while (events.pop(ev)) { ++received; if (ev.type == kEvBlockChange) blockEvents.push_back(ev); }
        Sleep(25);
    }

    McState fin; seqRead(map.mcState(), fin);
    double avg = errSamples ? sumErr / errSamples : -1;
    std::printf("fake_host: sent %u commands, got %u events, teleport acked=%u/%u, mc ticks=%llu\n",
                sent, received, fin.teleportAck, teleportSeq, (unsigned long long)fin.tickCounter);
    std::printf("fake_host: position error vs MC (blocks): avg %.3f, max %.3f over %ld samples\n", avg, maxErrBlocks, errSamples);

    CHECK(sent >= 5, "too few commands sent (%u)", sent);
    CHECK(received == sent, "events %u != commands %u", received, sent);
    for (size_t i = 0; i < blockEvents.size(); ++i) {
        const Message& e = blockEvents[i];
        CHECK(e.a == (int32_t)i && e.b == 64 && e.c == -(int32_t)i, "event %zu out of order/wrong coords (%d,%d,%d)", i, e.a, e.b, e.c);
        CHECK(e.d == ((i % 3 == 2) ? 1 : 0), "event %zu wrong block id %d", i, e.d);
    }
    CHECK(fin.teleportAck == teleportSeq && teleportSeq == 1, "teleport not acked");
    CHECK(errSamples > 20, "too few position samples (%ld)", errSamples);
    const double tol = (R * W / kUnitsPerBlock) * 0.12 + 0.1;  // ~120 ms of lag at the circle speed
    CHECK(avg >= 0 && maxErrBlocks < tol, "position error too large: max %.3f blocks (tol %.3f)", maxErrBlocks, tol);
    CHECK(fin.tickCounter > (uint64_t)(seconds * 10), "MC ticking too slowly (%llu)", (unsigned long long)fin.tickCounter);

    std::printf(failures ? "fake_host: %d FAILURE(S)\n" : "fake_host: ALL PASSED\n", failures);
    map.close();
    return failures ? 1 : 0;
}
