// layout_dump.cpp - prints every protocol offset/size as key=value lines.
// The Java tests compare these against dev.hnmc.link.Layout so the two sides cannot drift.
#include <cstdio>

#include "../hn_protocol.h"
#include "../hn_world.h"
#include <cstddef>

using namespace hn;

#define F(S, M) std::printf("%s.%s=%zu\n", #S, #M, offsetof(S, M))
#define SZ(S) std::printf("%s.size=%zu\n", #S, sizeof(S))

int main() {
    std::printf("mappingBytes=%zu\n", kMappingBytes);
    std::printf("magic=%u\nversion=%u\n", kMagic, kVersion);
    std::printf("offHostState=%zu\noffMcState=%zu\noffCmdRing=%zu\noffEventRing=%zu\n", kOffHostState, kOffMcState, kOffCmdRing, kOffEventRing);
    std::printf("cmdRingSlots=%zu\neventRingSlots=%zu\nringHeadOff=%zu\nringTailOff=%zu\nringDataOff=%zu\n", kCmdRingSlots, kEventRingSlots, kRingHeadOff, kRingTailOff, kRingDataOff);
    SZ(Header); F(Header, magic); F(Header, version); F(Header, hostPid); F(Header, mcPid); F(Header, hostHeartbeatMs); F(Header, mcHeartbeatMs);
    SZ(HostState); F(HostState, seq); F(HostState, flags); F(HostState, worldId); F(HostState, teleportSeq);
    F(HostState, posX); F(HostState, posY); F(HostState, posZ); F(HostState, yaw); F(HostState, pitch); F(HostState, onGround); F(HostState, gameSeconds);
    SZ(McState); F(McState, seq); F(McState, flags); F(McState, x); F(McState, y); F(McState, z); F(McState, yaw); F(McState, pitch);
    F(McState, eyeHeight); F(McState, health); F(McState, teleportAck); F(McState, hotbarSlot); F(McState, hotbarItem);
    F(McState, targetX); F(McState, targetY); F(McState, targetZ); F(McState, targetFace); F(McState, tickCounter);
    F(McState, camDX); F(McState, camDY); F(McState, camDZ); F(McState, camYaw); F(McState, camPitch); F(McState, camFov);
    SZ(Message); F(Message, type); F(Message, flags); F(Message, a); F(Message, b); F(Message, c); F(Message, d); F(Message, seq);
    std::printf("kHostInGame=%u\nkHostMcDrives=%u\nkMcInWorld=%u\nkMcOnGround=%u\nkMcDriving=%u\n", kHostInGame, kHostMcDrives, kMcInWorld, kMcOnGround, kMcDriving);
    std::printf("kCmdBreakBlock=%u\nkCmdPlaceBlock=%u\nkCmdSelectSlot=%u\nkCmdScrollSlot=%u\nkCmdResync=%u\nkCmdSurface=%u\nkCmdProxyCell=%u\nkCmdProxyClear=%u\nkCmdNeighbor=%u\nkCmdNeighborSize=%u\nkCmdHnItem=%u\n", kCmdBreakBlock, kCmdPlaceBlock, kCmdSelectSlot, kCmdScrollSlot, kCmdResync, kCmdSurface, kCmdProxyCell, kCmdProxyClear, kCmdNeighbor, kCmdNeighborSize, kCmdHnItem);
    std::printf("kEvBlockChange=%u\nkEvPlayerHurt=%u\nkEvPlayerDied=%u\nkEvResyncDone=%u\nkEvNeighborHit=%u\nkEvSurfaceHit=%u\nkEvExplosion=%u\nkEvScanCell=%u\nkEvHnSelect=%u\nkEvHeldFirst=%u\nkEvHeldThird=%u\nkEvHeldRot=%u\n", kEvBlockChange, kEvPlayerHurt, kEvPlayerDied, kEvResyncDone, kEvNeighborHit, kEvSurfaceHit, kEvExplosion, kEvScanCell, kEvHnSelect, kEvHeldFirst, kEvHeldThird, kEvHeldRot);
    std::printf("kBlockAir=%d\nkBlockStone=%d\nkBlockPlanks=%d\nkBlockDirt=%d\nkBlockCobble=%d\nkBlockBricks=%d\nkBlockGlass=%d\nkBlockOther=%d\n",
                kBlockAir, kBlockStone, kBlockPlanks, kBlockDirt, kBlockCobble, kBlockBricks, kBlockGlass, kBlockOther);
    // World channel (hn_world.h) <-> WorldLink.java
    std::printf("world.version=%u\nworld.vertexBytes=%zu\nworld.maxVerts=%zu\nworld.meshOff=%zu\nworld.atlasOff=%zu\nworld.atlasMax=%u\n",
                kWorldVersion, sizeof(WorldVertex), kWorldMaxVerts, kWorldMeshOff, kWorldAtlasOff, kWorldAtlasMax);
    std::printf("world.texTableOff=%zu\nworld.maxTextures=%u\nworld.entOff=%zu\nworld.maxBatches=%u\nworld.entMaxVerts=%zu\nworld.entSlotBytes=%zu\n",
                kWorldTexTableOff, kWorldMaxTextures, kWorldEntOff, kWorldMaxBatches, kWorldEntMaxVerts, kWorldEntSlotBytes);
    std::printf("world.texHeapOff=%zu\nworld.mappingBytes=%zu\n", kWorldTexHeapOff, kWorldMappingBytes);
    std::printf("world.H.texCount=%zu\nworld.H.entCtl=%zu\nworld.H.entSeq=%zu\nworld.H.entBatchCount=%zu\nworld.H.entVertexCount=%zu\nworld.H.entOrigin=%zu\n",
                offsetof(WorldHeader, texCount), offsetof(WorldHeader, entCtl), offsetof(WorldHeader, entSeq),
                offsetof(WorldHeader, entBatchCount), offsetof(WorldHeader, entVertexCount), offsetof(WorldHeader, entOrigin));
    return 0;
}
