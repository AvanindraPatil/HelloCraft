// hn_coords.h - Unreal (host) <-> Minecraft coordinate conversion.
// Unreal: Z-up, left-handed, 1 unit = 1 cm. Minecraft: Y-up, 1 unit = 1 block.
// mc.y = up, mc.z = +host.y (so +X stays +X). Unreal is left-handed and Minecraft right-handed, so this keeps
// left and right the same in both games (an earlier mc.z = -host.y mirrored them: A and D were swapped).
// kUnitsPerBlock = 84 is measured: the HN player is 151.2 uu tall (capsule half-height 75.6 after the 0.7 actor
// scale), and the MC player is 1.8 blocks, so 151.2 / 1.8 = 84. Tunable; see notes/skycraft-reference.md.
#pragma once

namespace hn {

constexpr double kUnitsPerBlock = 84.0;

struct Vec3d { double x, y, z; };

inline Vec3d hostToMc(double ux, double uy, double uz) {
    return { ux / kUnitsPerBlock, uz / kUnitsPerBlock, uy / kUnitsPerBlock };
}
inline Vec3d mcToHost(double bx, double by, double bz) {
    return { bx * kUnitsPerBlock, bz * kUnitsPerBlock, by * kUnitsPerBlock };
}

// Yaw: Unreal forward = (cos y, sin y) in (X, Y). Minecraft forward =
// (-sin y, cos y) in (X, Z). With mc.z = host.y: host +X (yaw 0) -> mc +X
// (MC yaw -90); host +Y (yaw 90) -> mc +Z (MC yaw 0). So mc = host - 90,
// host = mc + 90. Verified in tests/layout_test.cpp.
inline double hostYawToMc(double yaw) { return yaw - 90.0; }
inline double mcYawToHost(double yaw) { return yaw + 90.0; }

}  // namespace hn
