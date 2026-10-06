// hn_protocol.h - shared-memory layout between Hello Neighbor (host) and the
// hidden Minecraft Fabric mod. Protocol v0. Modelled on SkyCraft's protocol.
//
// Rules: little-endian, fixed-size structs, no pointers, no padding surprises
// (every struct has explicit padding and a static_assert on its size).
// The Java side mirrors these offsets by hand; tools/layout_dump prints them as JSON.
//
// Coordinates on the wire:
//   Host fields  (HostState.pos*)  : Unreal units (cm), Z-up, feet position.
//   MC fields    (McState.x/y/z)   : Minecraft blocks, Y-up, feet position.
// Conversion lives in hn_coords.h (one block = kUnitsPerBlock Unreal units).
#pragma once
#include <cstddef>
#include <cstdint>

namespace hn {

constexpr char     kMappingName[] = "Local\\HelloNeighborMC_v0";
constexpr uint32_t kMagic         = 0x434D4E48;  // "HNMC" little-endian
constexpr uint32_t kVersion       = 0;

// ---- Region offsets inside the mapping ---------------------------------
constexpr size_t kOffHeader     = 0x0000;
constexpr size_t kOffHostState  = 0x0100;
constexpr size_t kOffMcState    = 0x0200;
constexpr size_t kOffCmdRing    = 0x1000;   // host -> MC
constexpr size_t kCmdRingSlots  = 1024;     // power of two
constexpr size_t kOffEventRing  = 0xA000;   // MC -> host
constexpr size_t kEventRingSlots = 4096;    // power of two

// ---- Header (64 bytes) --------------------------------------------------
struct Header {
    uint32_t magic;
    uint32_t version;
    uint32_t hostPid;
    uint32_t mcPid;
    uint64_t hostHeartbeatMs;  // GetTickCount64() written by the host each frame
    uint64_t mcHeartbeatMs;    // written by MC each tick
    uint8_t  reserved[32];
};
static_assert(sizeof(Header) == 64, "Header size");

// ---- Latest-value slots (seqlock: seq odd while the writer is mid-write) --
enum HostFlags : uint32_t {
    kHostInGame   = 1u << 0,  // a gameplay level is loaded and a pawn exists
    kHostMenuOpen = 1u << 1,
    kHostLoading  = 1u << 2,
    kHostMcDrives = 1u << 3,  // step 3b: Minecraft's physics moves the player; the host follows McState
};

struct HostState {            // host -> MC, 64 bytes
    uint32_t seq;
    uint32_t flags;           // HostFlags
    uint32_t worldId;         // hash of the current map name
    uint32_t teleportSeq;     // bump when the host teleports the player; MC acks
    double   posX, posY, posZ;// player feet, Unreal units
    float    yaw, pitch;      // degrees, Unreal convention
    uint32_t onGround;        // 1 if the host's character is grounded
    uint32_t pad;
    float    gameSeconds;     // host time, for debugging
    uint32_t pad2;
};
static_assert(sizeof(HostState) == 64, "HostState size");

enum McFlags : uint32_t {
    kMcInWorld    = 1u << 0,
    kMcScreenOpen = 1u << 1,
    kMcOnGround   = 1u << 2,
    kMcHasTarget  = 1u << 3,  // targetX/Y/Z/face are valid
    kMcDriving    = 1u << 4,  // Minecraft is moving the player (follows kHostMcDrives once its world is ready)
    kMcCamDetached = 1u << 5, // Minecraft's camera is in third person (F5): camDX.. / camYaw.. say where
};

struct McState {              // MC -> host, 128 bytes
    uint32_t seq;
    uint32_t flags;           // McFlags
    double   x, y, z;         // player feet, blocks
    float    yaw, pitch;      // degrees, Minecraft convention
    float    eyeHeight;
    float    health;          // 0..20
    uint32_t teleportAck;     // last HostState.teleportSeq applied
    uint32_t hotbarSlot;      // selected slot 0..8
    uint16_t hotbarItem[9];   // item ids; 0 = empty
    uint16_t pad0;
    int32_t  targetX, targetY, targetZ;  // block under the crosshair
    uint32_t targetFace;      // 0..5 = down, up, north, south, west, east
    uint64_t tickCounter;     // MC ticks since start
    // Minecraft's camera this frame (F5 modes), so the host can put its own camera there:
    float    camDX, camDY, camDZ;   // camera position minus the feet (x, y, z), blocks
    float    camYaw, camPitch;      // degrees, Minecraft convention (front view = yaw + 180)
    float    camFov;                // vertical field of view, degrees (sprint, spyglass, ... included)
};
static_assert(sizeof(McState) == 128, "McState size");

// ---- SPSC rings -----------------------------------------------------------
// Layout: head (u64) at +0, tail (u64) at +64, slots at +128.
// Producer writes slot[head & mask] then publishes head+1; consumer reads
// slot[tail & mask] then publishes tail+1. Full when head - tail == slots.
constexpr size_t kRingHeadOff  = 0;
constexpr size_t kRingTailOff  = 64;
constexpr size_t kRingDataOff  = 128;

struct Message {              // 32 bytes, used in both rings
    uint32_t type;
    uint32_t flags;
    int32_t  a, b, c, d;      // meaning depends on type
    uint32_t seq;             // sender's running counter, for debugging
    uint32_t pad;
};
static_assert(sizeof(Message) == 32, "Message size");

// host -> MC
enum CmdType : uint32_t {
    kCmdNone        = 0,
    kCmdBreakBlock  = 1,  // a,b,c = block x,y,z
    kCmdPlaceBlock  = 2,  // a,b,c = block x,y,z of the cell to FILL (host picks it from its trace); d = BlockId
    kCmdSelectSlot  = 3,  // a = slot 0..8
    kCmdScrollSlot  = 4,  // a = +1 / -1
    kCmdResync      = 5,  // ask MC to resend all BlockChange events
    kCmdSurface     = 6,  // what the host CAMERA looks at (host-side trace), so Minecraft can click Hello Neighbor's own
                          // walls/floors: a,b,c = hit point in Minecraft blocks * 1000; d = face (Minecraft Direction
                          // ordinal + 1: 1 down, 2 up, 3 north, 4 south, 5 west, 6 east), 0 = the trace hit nothing
    kCmdProxyCell   = 7,  // Hello Neighbor geometry in one Minecraft cell, at quarter-block resolution:
                          // a = x; b = (y & 0xFFFF) | (z << 16); c, d = low, high 32 bits of a 64-bit occupancy mask,
                          // bit (sx + 4*sy + 16*sz) for sub-cell (sx, sy, sz) in 0..3; mask 0 = empty
    kCmdProxyClear  = 8,  // forget all Hello Neighbor geometry (map change, grid offset change)
    kCmdNeighbor    = 9,  // the neighbour (Hello Neighbor's AI character): a,b,c = feet in Minecraft blocks * 1000;
                          // d = Minecraft yaw * 100. Sent every sample while he exists.
    kCmdNeighborSize = 10, // a = radius, b = height (Minecraft blocks * 1000); c = 1 present, 0 gone (map change)
    kCmdHnItem      = 11, // an item in Hello Neighbor's own inventory, shown as a Minecraft item: a = item id (1..65535,
                          // stable per Hello Neighbor actor) | part << 16 | present << 24; b,c,d = 12 bytes of its name
                          // (ASCII, little-endian, zero padded): part 0 = characters 0-11, part 1 = 12-23. present 0 =
                          // the item left the inventory (thrown, used up); id 0 + present 0 = forget all (level change)
};

// MC -> host
enum EvType : uint32_t {
    kEvNone         = 0,
    kEvBlockChange  = 1,  // a,b,c = block x,y,z; d = BlockId now in that cell (0 = air)
    kEvPlayerHurt   = 2,  // a = damage*100
    kEvPlayerDied   = 3,
    kEvResyncDone   = 4,
    kEvNeighborHit  = 5,  // the player hit the neighbour: a = strength * 100 (Minecraft attack damage incl. cooldown and
                          // enchantments); b,c = push direction x,z in Minecraft space * 1000 (unit vector); d = 0
    kEvSurfaceHit   = 6,  // something hit Hello Neighbor's own geometry: a,b,c = point in Minecraft blocks * 1000;
                          // d = strength * 100 (low 24 bits) | kind << 24 (0 melee, 1 arrow)
    kEvExplosion    = 7,  // a,b,c = centre in Minecraft blocks * 1000; d = radius * 100 (blocks)
    kEvScanCell     = 8,  // please scan this Minecraft cell for Hello Neighbor geometry soon (an arrow will fly
                          // through it): a,b,c = cell x,y,z
    kEvHnSelect     = 9,  // the Minecraft hand holds this Hello Neighbor item (a = kCmdHnItem id), or a Minecraft item
                          // / nothing (a = 0: put Hello Neighbor's held item away). Sent on change and every 2 s
    kEvHeldFirst    = 10, // where Minecraft draws the held Hello Neighbor item this frame, first person: a,b,c = item
                          // centre minus the Minecraft camera position (world axes), blocks * 1000 (bob and swing included)
    kEvHeldThird    = 11, // the same in third person (Steve's hand, walk swing included): a,b,c = item centre minus
                          // the player's feet (render position), blocks * 1000; d = body yaw * 10 (0..3599, bits 0-15) | arm swing
                          // (radians * 1000 from the ITEM rest pose, signed, bits 16-31)
    kEvHeldRot      = 12, // first person, with kEvHeldFirst: the item's rotation minus the camera's, as Unreal
                          // rotator deltas (0 at rest): a = pitch, b = yaw, c = roll, degrees * 100
};

// Block palette shared by both sides (Minecraft maps these to real blocks; the host picks a look per id).
// Any Minecraft block not in the palette is reported as kBlockOther.
enum BlockId : int32_t {
    kBlockAir = 0, kBlockStone = 1, kBlockPlanks = 2, kBlockDirt = 3, kBlockCobble = 4, kBlockBricks = 5, kBlockGlass = 6,
    kBlockOther = 255,
};

constexpr size_t ringBytes(size_t slots) { return kRingDataOff + slots * sizeof(Message); }
constexpr size_t kMappingBytes = ((kOffEventRing + ringBytes(kEventRingSlots)) + 0xFFF) & ~size_t(0xFFF);

static_assert(kOffHostState + sizeof(HostState) <= kOffMcState, "HostState overlaps McState");
static_assert(kOffMcState + sizeof(McState) <= kOffCmdRing, "McState overlaps CmdRing");
static_assert(kOffCmdRing + ringBytes(kCmdRingSlots) <= kOffEventRing, "CmdRing overlaps EventRing");
static_assert((kCmdRingSlots & (kCmdRingSlots - 1)) == 0, "cmd slots power of two");
static_assert((kEventRingSlots & (kEventRingSlots - 1)) == 0, "event slots power of two");

}  // namespace hn
