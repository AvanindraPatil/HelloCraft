// hn_bridge.cpp - the host side of the link. UE4SS Lua cannot map shared memory, so
// the Lua mod talks to this process over two named pipes and this process owns the
// shared mapping.
//
//   Lua --writes--> \\.\pipe\hnmc_to_bridge   (text lines)
//   Lua <--reads--- \\.\pipe\hnmc_from_bridge (text lines)
//
// Lines from Lua:
//   C <type> <a> <b> <c> <d>                       a host->MC command
//   S <flags> <tp> <world> <x> <y> <z> <yaw> <pitch> <onGround> <secs>   host state; triggers a reply
// Reply to every S (events first, then exactly one M line):
//   E <type> <a> <b> <c> <d>                       an MC->host event
//   M <flags> <x> <y> <z> <yaw> <pitch> <tpAck> <slot> <tick>            MC state
//
// Usage: hn_bridge.exe [mappingName] [pipePrefix] [--sessions N]
#include <cstdio>
#include <cstring>
#include <string>

#include "../hn_shm.h"

using namespace hn;

static bool writeAll(HANDLE h, const std::string& s) {
    DWORD done = 0;
    return WriteFile(h, s.data(), (DWORD)s.size(), &done, nullptr) && done == s.size();
}

int main(int argc, char** argv) {
    const char* mapName = argc > 1 ? argv[1] : kMappingName;
    std::string prefix = argc > 2 ? argv[2] : "\\\\.\\pipe\\hnmc";
    std::string pipeIn = prefix + "_to_bridge", pipeOut = prefix + "_from_bridge";
    // Test hook: exit after N sessions (0 = run forever).
    int maxSessions = (argc > 4 && std::strcmp(argv[3], "--sessions") == 0) ? std::atoi(argv[4]) : 0, sessions = 0;
    if (maxSessions <= 0) maxSessions = 1 << 30;

    Mapping map;
    if (!map.open(mapName)) { std::printf("bridge: cannot open mapping\n"); return 2; }
    map.header()->hostPid = GetCurrentProcessId();
    Ring cmds = cmdRing(map), events = eventRing(map);

    std::printf("bridge: up (mapping %s, pipes %s*), waiting for Lua...\n", mapName, prefix.c_str());
    std::fflush(stdout);

    for (;;) {
        // Fresh pipe instances for every session. A DISCONNECTED instance is not listening until ConnectNamedPipe
        // is called on it, and the Lua side opens both pipes back to back, so reusing instances deadlocks.
        HANDLE hIn = CreateNamedPipeA(pipeIn.c_str(), PIPE_ACCESS_INBOUND, PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS, 1, 65536, 65536, 0, nullptr);
        HANDLE hOut = CreateNamedPipeA(pipeOut.c_str(), PIPE_ACCESS_OUTBOUND, PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS, 1, 65536, 65536, 0, nullptr);
        if (hIn == INVALID_HANDLE_VALUE || hOut == INVALID_HANDLE_VALUE) { std::printf("bridge: cannot create pipes (%lu)\n", GetLastError()); return 3; }

        // Lua opens to_bridge first, then from_bridge; both instances are already listening.
        if (!ConnectNamedPipe(hIn, nullptr) && GetLastError() != ERROR_PIPE_CONNECTED) { CloseHandle(hIn); CloseHandle(hOut); Sleep(100); continue; }
        if (!ConnectNamedPipe(hOut, nullptr) && GetLastError() != ERROR_PIPE_CONNECTED) { CloseHandle(hIn); CloseHandle(hOut); Sleep(100); continue; }
        std::printf("bridge: Lua connected\n"); std::fflush(stdout);

        std::string buf;
        unsigned long long samples = 0, cmdCount = 0, evCount = 0;
        bool alive = true;
        while (alive) {
            char chunk[4096];
            DWORD n = 0;
            if (!ReadFile(hIn, chunk, sizeof(chunk), &n, nullptr) || n == 0) break;  // Lua closed the pipe
            buf.append(chunk, n);

            size_t nl;
            while ((nl = buf.find('\n')) != std::string::npos) {
                std::string line = buf.substr(0, nl);
                buf.erase(0, nl + 1);
                if (line.empty()) continue;

                if (line[0] == 'C') {
                    unsigned type; int a, b, c, d;
                    if (std::sscanf(line.c_str(), "C %u %d %d %d %d", &type, &a, &b, &c, &d) == 5) {
                        Message m{}; m.type = type; m.a = a; m.b = b; m.c = c; m.d = d; m.seq = (uint32_t)++cmdCount;
                        cmds.push(m);
                    }
                } else if (line[0] == 'S') {
                    HostState hs{};
                    unsigned flags, tp, world; int ground; double secs;
                    float yaw, pitch;
                    if (std::sscanf(line.c_str(), "S %u %u %u %lf %lf %lf %f %f %d %lf", &flags, &tp, &world,
                                    &hs.posX, &hs.posY, &hs.posZ, &yaw, &pitch, &ground, &secs) != 10) continue;
                    hs.flags = flags; hs.teleportSeq = tp; hs.worldId = world;
                    hs.yaw = yaw; hs.pitch = pitch; hs.onGround = ground != 0; hs.gameSeconds = (float)secs;
                    seqWrite(map.hostState(), hs);
                    map.header()->hostHeartbeatMs = GetTickCount64();
                    ++samples;

                    std::string reply;
                    char tmp[256];
                    Message ev;
                    while (events.pop(ev)) {
                        std::snprintf(tmp, sizeof(tmp), "E %u %d %d %d %d\n", ev.type, ev.a, ev.b, ev.c, ev.d);
                        reply += tmp; ++evCount;
                    }
                    // The last CLEAN McState: if Minecraft is mid-write for too long, resend that rather than garbage.
                    static McState lastMs{};
                    seqRead(map.mcState(), lastMs);
                    const McState& ms = lastMs;
                    std::snprintf(tmp, sizeof(tmp), "M %u %.6f %.6f %.6f %.3f %.3f %u %u %llu %.4f %.4f %.4f %.4f %.3f %.3f %.3f\n", ms.flags, ms.x, ms.y, ms.z,
                                  ms.yaw, ms.pitch, ms.teleportAck, ms.hotbarSlot, (unsigned long long)ms.tickCounter, ms.eyeHeight,
                                  ms.camDX, ms.camDY, ms.camDZ, ms.camYaw, ms.camPitch, ms.camFov);
                    reply += tmp;
                    if (!writeAll(hOut, reply)) { alive = false; break; }
                }
            }
        }
        std::printf("bridge: Lua disconnected (%llu samples, %llu commands, %llu events)\n", samples, cmdCount, evCount);
        std::fflush(stdout);
        CloseHandle(hIn);
        CloseHandle(hOut);
        // No more heartbeats now, so the Minecraft side notices the host is gone.
        if (++sessions >= maxSessions) break;
    }
    map.close();
    return 0;
}
