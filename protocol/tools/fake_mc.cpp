// fake_mc.cpp - stands in for the Fabric mod. Runs at 20 Hz like Minecraft ticks:
// mirrors HostState into McState (converted to MC coordinates), acks teleports,
// answers commands with events, keeps a heartbeat. Exits when the host goes quiet.
#include <cstdio>

#include "../hn_coords.h"
#include "../hn_shm.h"

using namespace hn;

int main(int argc, char** argv) {
    const char* name = argc > 1 ? argv[1] : kMappingName;
    Mapping map;
    if (!map.open(name)) { std::printf("fake_mc: cannot open mapping\n"); return 2; }
    map.header()->mcPid = GetCurrentProcessId();
    std::printf("fake_mc: up (pid %lu, created mapping: %d)\n", GetCurrentProcessId(), map.created);

    Ring cmds = cmdRing(map), events = eventRing(map);
    McState mc{};
    uint64_t tick = 0, handled = 0;
    ULONGLONG start = GetTickCount64();
    bool sawHost = false;

    for (;;) {
        ULONGLONG now = GetTickCount64();
        map.header()->mcHeartbeatMs = now;

        HostState hs;
        if (seqRead(map.hostState(), hs) && (hs.flags & kHostInGame)) {
            Vec3d p = hostToMc(hs.posX, hs.posY, hs.posZ);
            mc.x = p.x; mc.y = p.y; mc.z = p.z;
            mc.yaw = (float)hostYawToMc(hs.yaw);
            mc.pitch = hs.pitch;
            mc.teleportAck = hs.teleportSeq;
            mc.flags = kMcInWorld | (hs.onGround ? kMcOnGround : 0);
            mc.eyeHeight = 1.62f;
            mc.health = 20.f;
        }

        Message m;
        while (cmds.pop(m)) {
            ++handled;
            Message ev{}; ev.seq = (uint32_t)handled;
            switch (m.type) {
                case kCmdBreakBlock: ev.type = kEvBlockChange; ev.a = m.a; ev.b = m.b; ev.c = m.c; ev.d = 0; events.push(ev); break;
                case kCmdPlaceBlock: ev.type = kEvBlockChange; ev.a = m.a; ev.b = m.b; ev.c = m.c; ev.d = 1; events.push(ev); break;
                case kCmdSelectSlot: mc.hotbarSlot = (uint32_t)m.a % 9; break;
                case kCmdScrollSlot: mc.hotbarSlot = (mc.hotbarSlot + 9 + m.a) % 9; break;
                case kCmdResync:     ev.type = kEvResyncDone; events.push(ev); break;
                default: break;
            }
        }

        mc.tickCounter = ++tick;
        seqWrite(map.mcState(), mc);

        ULONGLONG hb = map.header()->hostHeartbeatMs;
        if (hb) sawHost = true;
        if (sawHost && now - hb > 2000) { std::printf("fake_mc: host heartbeat lost, exiting (%llu ticks, %llu commands)\n", tick, handled); break; }
        if (!sawHost && now - start > 20000) { std::printf("fake_mc: no host after 20s, exiting\n"); break; }
        Sleep(50);
    }
    map.close();
    return 0;
}
