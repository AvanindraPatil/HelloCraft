// pipe_sim.cpp - stands in for the Lua mod: speaks the bridge's text protocol over the two
// named pipes, walking a circle at ~30 Hz and sending commands. Checks that the MC position
// in the replies follows ours and that every command is answered with an event.
// Usage: pipe_sim.exe [pipePrefix] [seconds]      exit code 0 = passed
#include <windows.h>

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "../hn_coords.h"

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++failures; std::printf("FAIL: "); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

static HANDLE openPipe(const std::string& name, DWORD access) {
    for (int i = 0; i < 200; ++i) {
        HANDLE h = CreateFileA(name.c_str(), access, 0, nullptr, OPEN_EXISTING, 0, nullptr);
        if (h != INVALID_HANDLE_VALUE) return h;
        Sleep(50);
    }
    return INVALID_HANDLE_VALUE;
}

static bool readLine(HANDLE h, std::string& pending, std::string& line) {
    for (;;) {
        size_t nl = pending.find('\n');
        if (nl != std::string::npos) { line = pending.substr(0, nl); pending.erase(0, nl + 1); return true; }
        char c[1024]; DWORD n = 0;
        if (!ReadFile(h, c, sizeof(c), &n, nullptr) || n == 0) return false;
        pending.append(c, n);
    }
}

int main(int argc, char** argv) {
    std::string prefix = argc > 1 ? argv[1] : "\\\\.\\pipe\\hnmc";
    double seconds = argc > 2 ? atof(argv[2]) : 5.0;

    HANDLE w = openPipe(prefix + "_to_bridge", GENERIC_WRITE);
    HANDLE r = openPipe(prefix + "_from_bridge", GENERIC_READ);
    if (w == INVALID_HANDLE_VALUE || r == INVALID_HANDLE_VALUE) { std::printf("pipe_sim: bridge not reachable\n"); return 2; }
    std::printf("pipe_sim: connected\n");

    const double R = 500.0, W = 1.0, cx = -2316.7, cy = 762.8, cz = 195.0;
    LARGE_INTEGER freq, a; QueryPerformanceFrequency(&freq); QueryPerformanceCounter(&a);
    unsigned sentCmds = 0, events = 0, tp = 0, replies = 0;
    bool teleported = false;
    double maxErr = 0, sumErr = 0; long errN = 0;
    double nextCmd = 0.5, t = 0, lastX = 0, lastZ = 0; bool haveM = false;
    std::string pending;
    unsigned long long lastTick = 0;
    unsigned lastAck = 0;

    while (t < seconds) {
        LARGE_INTEGER b; QueryPerformanceCounter(&b);
        t = double(b.QuadPart - a.QuadPart) / double(freq.QuadPart);
        double ang = W * t;
        double x = cx + R * std::cos(ang), y = cy + R * std::sin(ang);
        double yaw = std::fmod(ang * 180.0 / 3.14159265358979 + 90.0, 360.0);
        if (!teleported && t > seconds * 0.5) { teleported = true; ++tp; x += 50000; y -= 20000; }

        std::string out;
        char tmp[256];
        if (t >= nextCmd) {
            unsigned type = (sentCmds % 3 == 2) ? 2 : 1;
            std::snprintf(tmp, sizeof(tmp), "C %u %u 64 %d 1\n", type, sentCmds, -(int)sentCmds);
            out += tmp; ++sentCmds; nextCmd += 0.5;
        }
        std::snprintf(tmp, sizeof(tmp), "S 1 %u 123 %.4f %.4f %.4f %.3f 0.0 1 %.3f\n", tp, x, y, cz, yaw, t);
        out += tmp;
        DWORD done;
        if (!WriteFile(w, out.data(), (DWORD)out.size(), &done, nullptr)) { std::printf("pipe_sim: write failed\n"); return 3; }

        // Read until the M line (events come first).
        std::string line;
        for (;;) {
            if (!readLine(r, pending, line)) { std::printf("pipe_sim: bridge closed\n"); return 3; }
            if (line[0] == 'E') { ++events; continue; }
            if (line[0] == 'M') break;
        }
        ++replies;
        unsigned fl, ack, slot; double mx, my, mz; float myaw, mpitch; unsigned long long tick;
        if (std::sscanf(line.c_str(), "M %u %lf %lf %lf %f %f %u %u %llu", &fl, &mx, &my, &mz, &myaw, &mpitch, &ack, &slot, &tick) == 9) {
            haveM = true; lastTick = tick; lastAck = ack; lastX = mx; lastZ = mz;
            if (!teleported && fl && t > 0.4) {
                double la = W * (t - 0.1);
                hn::Vec3d e = hn::hostToMc(cx + R * std::cos(la), cy + R * std::sin(la), cz);
                double err = std::hypot(mx - e.x, mz - e.z);
                maxErr = std::fmax(maxErr, err); sumErr += err; ++errN;
            }
        }
        Sleep(33);
    }
    // Drain: keep sending S lines briefly so remaining events come back.
    for (int i = 0; i < 20 && events < sentCmds; ++i) {
        char tmp[256]; std::snprintf(tmp, sizeof(tmp), "S 1 %u 123 0 0 0 0 0 1 0\n", tp);
        DWORD done; WriteFile(w, tmp, (DWORD)strlen(tmp), &done, nullptr);
        std::string line;
        for (;;) { if (!readLine(r, pending, line)) return 3; if (line[0] == 'E') { ++events; continue; } if (line[0] == 'M') break; }
        Sleep(50);
    }

    std::printf("pipe_sim: %u replies, sent %u commands, got %u events, teleport ack %u/%u, mc tick %llu\n", replies, sentCmds, events, lastAck, tp, lastTick);
    std::printf("pipe_sim: position error (blocks): avg %.3f, max %.3f over %ld samples\n", errN ? sumErr / errN : -1.0, maxErr, errN);
    CHECK(haveM, "never received an M line");
    CHECK(sentCmds >= 5, "too few commands");
    CHECK(events == sentCmds, "events %u != commands %u", events, sentCmds);
    CHECK(lastAck == tp, "teleport not acked");
    const double tol = (R * W / hn::kUnitsPerBlock) * 0.12 + 0.1;
    CHECK(errN > 20 && maxErr < tol, "position error too large (max %.3f, tol %.3f, n=%ld)", maxErr, tol, errN);
    std::printf(failures ? "pipe_sim: %d FAILURE(S)\n" : "pipe_sim: ALL PASSED\n", failures);
    (void)lastX; (void)lastZ;
    CloseHandle(w); CloseHandle(r);
    return failures ? 1 : 0;
}
