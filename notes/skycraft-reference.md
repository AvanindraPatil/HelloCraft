# SkyCraft reference notes (chasmlol/SkyCraft, MIT)

Read: docs/DESIGN.md and protocol/skycraft_protocol.h (2026-10-02). What to copy / what to change for Hello Neighbor (HN).

## Transport (copy this)
- One named shared-memory mapping `Local\SkyCraft_v1` (~128 MB, mostly render/collision rings). Little-endian, fixed-size structs, no serialization library.
- Header (32 B): magic `SKYC`, version, both PIDs, both heartbeats (GetTickCount64). Heartbeats are how each side detects the other crashing. No named events needed.
- **Latest-value slots under a seqlock** for per-frame state: SkyState (host -> MC: player pos/yaw/pitch, flags, teleportSeq) and McState (MC -> host: interpolated pos, look, onGround, camera, frame counter).
- **SPSC ring buffers** for events: Input (host -> MC, 4096 x 16 B), Collision (host -> MC, 32 MB), Event (MC -> host, 512 x 32 B), Render (MC -> host, 64 MB).
- Layout is validated against both C++ and Java in CI. We should do the same with a small layout test.

## Authority and movement
- Minecraft owns the player position and physics. Host sends world collision into MC; MC sends the resulting position back every render frame.
- Collision injection is a Mixin on `Entity.collide` that appends extra shapes. Host geometry is sent as sparse AABB sections (1/8-block resolution per 16^3 chunk). Slopes become 1/8-block microsteps; steep slopes become walls.
- MVP collision (their stage A): grid of raycasts around the player. Cheap, misses thin geometry. Good enough to start for HN.
- Placed/broken blocks: MC sends `BlockChange` events; host makes invisible collision boxes so NPCs collide with them.

## Rendering (the part we will NOT copy directly)
- SkyCraft renders MC offscreen and composites mid-frame, depth-tested against the host depth buffer, using DX11/GL interop. That needs a native renderer hook inside the host.
- Our plan renders blocks as meshes in HN's scene instead. Open question for phase 3: can UE4SS (Lua or C++) spawn procedural-mesh actors reliably in HN's UE 4.20 build?
- GUI/hotbar was a separate composited layer. We could use UMG widgets from UE4SS or a simple overlay.

## Coordinates
- 1 MC block = 70 Skyrim units. Axis swap Y-up (MC) vs Z-up (Skyrim): `mc.y = sky.z/70; mc.z = -sky.y/70`.
- HN/UE4 is also Z-up, left-handed, units = cm. A block = 100 uu would be natural, but check the player capsule height (MC player is 1.8 blocks) before choosing the scale.

## Lifecycle
- Start MC first (standby), then the host game; host connects via the mapping and handshake. If either side dies, the other drops to a safe state.

## Consequences for our build
1. **Lua cannot open shared memory.** UE4SS Lua has no native mmap API. The host side must be native: a UE4SS C++ mod (preferred, it still has the UObject access we just proved) or our own DLL loaded by it.
2. Phase 1 can be tested without HN at all using a fake client, as the plan says.
3. Phase 2 needs: HN player location (done, PosLogger) + a way to move the pawn from outside (`K2_SetActorLocation` / `AddMovementInput`) + a collision source (line traces via UE4SS).
4. Proposed minimal protocol v0: header + HostState + McState (seqlock, as above), one Event ring MC -> host (BlockChange, etc.), one Command ring host -> MC (BlockBreak/BlockPlace requests, hotbar slot). Skip the Render/Collision rings and overlay buffers until phase 3.

## Status: protocol v0 written (2026-10-02)
- `protocol/hn_protocol.h` (layout), `hn_coords.h` (Unreal<->MC maths), `hn_shm.h` (mapping, seqlock, SPSC rings).
- Mapping `Local\HelloNeighborMC_v0`, 176,128 bytes. Header @0, HostState @0x100 (64 B), McState @0x200 (128 B), command ring @0x1000 (1024 x 32 B, host->MC), event ring @0xA000 (4096 x 32 B, MC->host).
- `protocol\build_tests.bat` builds with MSVC (found by tools\vcvars.bat) and runs `tests/layout_test.cpp`: offsets, coordinate round trip, yaw mapping (`mc = -90 - host`, checked with direction vectors), seqlock under a writer/reader race (0 torn reads), ring ordering over 200k messages, capacity edges. All pass.
- The Java side must mirror these offsets by hand. TODO: a `layout_dump` tool that prints them as JSON so the Fabric mod's tests can compare.
- Scale `kUnitsPerBlock = 100` is a placeholder until we measure HN's capsule height.

## Two-process test (2026-10-02): PASS
- `protocol\run_two_process.bat` builds `tools/fake_mc.cpp` (20 Hz, stands in for the Fabric mod) and `tools/fake_host.cpp` (60 Hz, stands in for the HN plugin) and runs them as separate processes on a private mapping. Host exit code = test result.
- Result: 12/12 commands answered with events in order and with correct block ids; teleport acked; MC ticked 99 times in 6 s; position error vs MC avg 0.26 / max 0.42 blocks while the player circled at ~5 blocks/s. That error is just the 20 Hz tick lag (~50 ms x 5 blocks/s). The real host should interpolate between MC ticks (SkyCraft sends partial-tick positions) or accept the lag.
- Shutdown path works: MC exits ~2 s after the host heartbeat stops.
- Not yet covered: MC starting after the host, mapping reuse after a crash, heartbeat-based reconnect.

## Fabric mod scaffold (2026-10-02): runs
- `fabric-mod/` = official FabricMC/fabric-example-mod branch `26.3` (downloaded as zip, no git), renamed: mod id `hnmc`, package `dev.hnmc`, entrypoints `dev.hnmc.HnMc` (main) and `dev.hnmc.client.HnMcClient` (client). Example mixins removed.
- Versions (gradle.properties): Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, Loom 1.18-SNAPSHOT, Gradle 9.7.1 (wrapper), Java 25.
- Build: `fabric-mod\gradlew build` with JAVA_HOME = JDK 25 (`C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot`). Takes ~1.5 min first time. Dev client: `gradlew runClient`; log shows `(hnmc) Hello Neighbor Link loaded (protocol v0)`.
- Prism instance `26.3` (AppData\Roaming\PrismLauncher\instances\26.3): Fabric Loader 0.19.5 installed, Fabric API not yet.

## Phase 1: DONE (2026-10-02)
- Java side: `fabric-mod/src/main/java/dev/hnmc/link/` = `Layout` (hand-mirrored offsets), `Win32` (kernel32 via Java 25 FFM, no JNI), `SharedMemory` (mapping + seqlock + SPSC ring via VarHandles), `Coords`, `McLink` (per-tick: heartbeat, read HostState, publish McState, answer commands with events). `HnMcClient` calls `McLink.tick()` from `ClientTickEvents.END_CLIENT_TICK` (20 Hz) and logs the host position once a second.
- Tests (`gradlew test`, needs `protocol\build_java_deps.bat` first): LayoutTest (69 values identical to C++ layout_dump), CoordsTest, SharedMemoryTest (seqlock 0 torn reads, ring 200k in order), CrossProcessTest (real C++ `fake_host.exe` vs Java McLink: 12/12 commands, teleport acked). 7/7 pass.
- Real game check: `gradlew runClient` + `protocol\build\fake_host.exe Local\HelloNeighborMC_v0 8` -> fake_host ALL PASSED (16/16 events, 250 MC ticks) and the Minecraft log printed `host pos (-1892.0, 1026.8, 195.0) -> mc (-18.92, 1.95, -10.27)` each second, then `Hello Neighbor disconnected (heartbeat lost)`.
- Run flags: dev client uses `--enable-native-access=ALL-UNNAMED` (set in build.gradle). A packaged mod in Prism will print an FFM warning unless that flag is added to the instance JVM args.
- Caveat: McLink currently ECHOES the host position (no Minecraft physics yet). That is phase 2.

## Phase 2 step 1: real Hello Neighbor -> link -> real Minecraft WORKS (2026-10-02, ~15:10)
- Architecture change: UE4SS C++ mods need UE4SS built from source (private Epic-linked submodule; the zDEV release has no headers/libs). So the host side is: Lua mod `ue4ss-mods/HnLink` (in HN) <-> two named pipes (`\.\pipe\hnmc_to_bridge`, `\.\pipe\hnmc_from_bridge`) <-> `protocol/tools/hn_bridge.exe` (owns the shared mapping) <-> Java `McLink` in Minecraft. Text protocol documented at the top of `hn_bridge.cpp`.
- Proven: UE4SS Lua `io.open` on a Windows named pipe works inside HN, request/reply every ~33 ms on the game thread (ExecuteInGameThread inside LoopAsync). No link errors in ~2 minutes of play.
- Chain test without the game: `protocol\run_bridge_test.bat` (pipe_sim -> bridge -> fake_mc): 12/12 commands, teleport acked, max error 0.27 blocks.
- Real run: walked Act1 for ~1 min. Minecraft log shows host pos converted exactly (host/100, y/z swapped), yaw changes tracked, z drops 178 -> 160 when crouching. Intro is a camera flythrough ((-7396,-9849,1270) down to spawn (-7303,1347,178)). Teleport counter fired 3 times in the intro/map change and not during normal walking.
- Open items: (1) K2_GetActorLocation is the CAPSULE CENTRE (z 178 standing, 160 crouched); MC needs FEET, so measure the capsule half-height and subtract it. (2) "In game" = any map other than /Game/Maps/Start (heuristic). (3) McLink still echoes position; no MC physics yet. (4) Lua logs every 2 s only; for continuity/lag analysis add a high-rate CSV or counters.
- To run the real thing: `protocol\run_bridge.bat` (bridge), `fabric-mod\gradlew runClient` (Minecraft), launch HN with HnLink enabled in ue4ss\Mods\mods.txt (PosLogger disabled).

## Player measurements (HnProbe, 2026-10-02, BP_Human_C in Act1) and scale decision
- The player actor is scaled 0.7: capsule half-height 75.6 (unscaled 108), radius 35.98 (51.4). Crouched: half-height 59.5 (unscaled 85). Feet z = centre z - half-height, constant (102.2 at the Act1 spawn) while standing, crouching and jumping. Standing height 151.2 uu, crouched 119 uu, width 72 uu.
- Eye height above feet: 114.3 standing (camera z 216.5), 80.1 crouched (182.3). FOV 90. BaseEyeHeight 64 / 32 (unscaled).
- Movement: MaxWalkSpeed 550 (1000 during the intro flythrough), crouched 300, JumpZVelocity 420, gravity -980, MaxStepHeight 45, WalkableFloorAngle 50.
- Candidate scales (uu per block): height 151.2/1.8 = 84; width 72/0.6 = 120; eye 114.3/1.62 = 71; step 45/0.6 = 75; jump 90/1.25 = 72. **Chose 84** (player the right size relative to blocks); `kUnitsPerBlock` / `Coords.UNITS_PER_BLOCK` = 84. Revisit once blocks are visible in the house (a block may look too big/small next to furniture; 70-75 is the other sensible choice).
- HnLink now sends FEET (centre - scaled half-height), not centre. Needs an HN restart to take effect.
- Tests made scale-independent (yaw test uses the constant; position tolerance = 120 ms of lag at the circle speed).

## Phase 2 stage A: the real Minecraft player follows the host (2026-10-02)
- `fabric-mod/.../client/HnWorld.java`: on the title screen (after 40 ticks) the mod creates and enters a void Superflat world by itself (`WorldOpenFlows.createFreshLevel`, same call the Demo World uses; levels are named `HN_Void_<millis>` in `run/saves`, not cleaned up yet). Each client tick it teleports the integrated-server `ServerPlayer` to the host's target (no gravity, zero velocity) and publishes the real client player's position in McState. Teleport ack is sent only after the player is within 1.5 blocks of the target.
- Lessons: (1) creative mode does NOT protect from void damage: the first run, the player fell out of the world before the host connected and a dead player ignores teleports. Fix: hold at (8.5, 64, 8.5) with no gravity while the host is not in game, and respawn if dead. (2) 26.3 API moves: current screen is `mc.gui.screen()` (not `mc.screen`); `Entity.snapTo` replaces `moveTo`; the first-run accessibility screen blocks the title screen in a fresh run dir (`onboardAccessibility:false` set in `fabric-mod/run/options.txt`; a fresh Prism instance will show it once).
- Test (C++ fake host vs the real running Minecraft): 16/16 commands answered, teleport 600+ blocks acked, position error vs the real MC player avg 0.109 / max 0.313 blocks (tolerance 0.81), no deaths, no errors.
- Still echo: block commands (phase 3 applies them to the world). Still no collision/physics: Minecraft does not push back on the host yet.

## Real HN -> bridge -> real Minecraft, measured (2026-10-02 15:42-15:46)
- Bridge bug found and fixed: it handled the first Lua session only (reused disconnected pipe instances are not listening, and Lua opens its two pipes back to back -> deadlock). Now creates fresh pipes per session; `run_bridge_test.bat` runs two sessions in a row as a regression test.
- 269 one-per-second samples, all inGame: tracking error between the host target and the REAL Minecraft player avg 0.023 blocks (horizontal 0.022, vertical 0.002), max 0.46 (during fast movement; the client lags the server by a tick or two). ~113 blocks walked, jumps and stairs: feet z 101.4..237.5 uu = MC y 1.21..2.83, vertical follow correct. 0 link errors, 0 Minecraft errors.
- Not separately verified: crouch (feet z does not change when crouching, so it looks like standing in the feet data; the earlier HnProbe already showed feet constant while crouched).
- Phase 2 as written in the plan ("walking in HN moves the hidden MC player") is met. Remaining phase 2 item: feed HN collision into MC (hardest part), and let MC physics matter. Neither is needed for phase 3 (blocks).

## Phase 3 probe, round 1 (2026-10-02 ~16:04): HnSpawn
- FOUND in the shipped game: `/Engine/BasicShapes/Cube.Cube`, `/Engine/BasicShapes/BasicShapeMaterial.BasicShapeMaterial`, `/Engine/EngineMaterials/DefaultMaterial.DefaultMaterial`. Missing: BasicShapes Plane and Sphere, `/Engine/EngineMeshes/Cube`.
- CRASH: `FindAllOf("StaticMesh")` (enumerating every loaded mesh) took the game down right after the survey's 6th lookup. Do not enumerate large object classes from Lua in HN. Targeted `StaticFindObject` is safe.
- Next: spawn test with F8 (SpawnActor StaticMeshActor + SetStaticMesh on the engine cube).
- Round 2 (16:09-16:10): `World:SpawnActor(StaticMeshActor, loc, rot)` WORKS once the level is fully loaded and you are playing (first F8, pressed too early, returned INVALID for all 6 variants; a later F8 spawned `StaticMeshActor_2` in Act1). Findings: `comp:SetMobility` is not callable from Lua (plain C++ in 4.20; set `comp.Mobility = 2` instead), so `SetStaticMesh` returned false while the component was Static. `ProceduralMeshComponent` class is present (fallback for custom meshes). CRASH on the call right after `SetActorScale3D`, which was `comp:SetCollisionProfileName("BlockAll")`: do not call it. Hot reload (Ctrl+R) enabled in UE4SS-settings.ini.
- Round 3 (16:14): F8 never reached the spawn: `pawn:GetWorld()` returned INVALID twice while in Act1 (it worked in round 2). GetWorld() via UE4SS is unreliable here.
- Round 4 plan (HnSpawn v3): primary path = `Default__GameplayStatics:BeginDeferredActorSpawnFromClass(pawn, StaticMeshActor, transform, AlwaysSpawn)` -> set `Mobility = Movable` + `SetStaticMesh` BEFORE `FinishSpawningActor`. No GetWorld needed, and mesh is set before registration. Fallback = World:SpawnActor with the world found via pawn/pc GetWorld, Level outer, or FindFirstOf("World").
- Round 5 (16:23) ROOT CAUSE FOUND: spawning WORKS. `BeginDeferredActorSpawnFromClass` created StaticMeshActor_3.._7 in Act1_Main's PersistentLevel with every world context tried (game mode, PC, camera manager, game state, pawn). UE4SS `IsValid()` returns false for these fresh actors AND for the Act1 Level and World (while pawn, PC, game mode in the same level are valid=true, and `GameplayStatics.GetPlayerController(pawn, 0)` resolves). My code trusted IsValid and threw the real actors away before giving them a mesh. Fix (HnSpawn v5): for objects we spawn, check that `GetFullName()` resolves instead of `IsValid()`. Lesson: in HN, never gate on UE4SS IsValid for world/level/freshly spawned objects.
- Minecraft side: dev client paused on focus loss (pause screen freezes the integrated server). Fixed: `options.pauseOnLostFocus = false` each tick, and the pause screen is closed while HN is connected.

## Phase 3 feasibility: PASS (2026-10-02 16:24-16:26, HnSpawn v5)
- 22 cubes spawned in Act1 and cleared with F7; user confirmed they are visible and the player collides with them.
- Working recipe (Lua, game thread): `Default__GameplayStatics:BeginDeferredActorSpawnFromClass(gameMode, StaticMeshActor, {Rotation=quat, Translation=..., Scale3D=...}, 1 /*AlwaysSpawn*/, nil)` -> `comp = actor.StaticMeshComponent; comp.Mobility = 2; comp:SetStaticMesh(/Engine/BasicShapes/Cube.Cube)` -> `FinishSpawningActor(actor, xf)` -> `SetActorScale3D(0.84)`. Collision enabled = 3 (QueryAndPhysics) by default; no collision call needed. Destroy with `K2_DestroyActor()`. One spawn costs ~1.5 ms of game-thread time in the log.
- Still to solve: textures (currently the engine's plain BasicShapeMaterial), many blocks (one actor per block will not scale; InstancedStaticMeshComponent and ProceduralMeshComponent are both present in the build), and the neighbor's AI/navmesh reacting to blocks (phase 5).

## Phase 3 step 1: Minecraft-owned blocks WORK (2026-10-02 ~16:34)
- Protocol: `kCmdPlaceBlock` now = cell to fill + BlockId (host does the trace); palette BlockId 0 air, 1 stone, 2 planks, 3 dirt, 4 cobblestone, 5 bricks, 6 glass, 255 other (hn_protocol.h, Layout.java, checked by LayoutTest).
- MC: `HnBlocks` applies place/break on the integrated server (overworld), reports what the cell really holds, tracks known cells, answers resync. Commands arrive on the client thread, run on the server thread, results go back through a queue drained on the client thread (single producer for the event ring). Tests: McLinkBlocksTest (handler path, no echo when a handler is set).
- HN: HnLink F8 place / F7 break the cell 2 blocks ahead, F5 cycle type, F6 resync; cubes spawn only on BlockChange events, on the shared 84-uu grid; resync on connect and on map change.
- User test: blocks place/break through Minecraft and line up on the grid (adjacent cubes merge into walls). Types are correct end to end (log: cobblestone, dirt, planks, stone; resync 11 in MC = 11 shown) but all looked the same: the engine cube renders with the checkered default material.
- Next: per-type colour via `comp:CreateAndSetMaterialInstanceDynamicFromMaterial(0, BasicShapeMaterial)` + `SetVectorParameterValue(FName("Color"), ...)` (FName passed explicitly; a plain-string FName arg is the suspect in the earlier SetCollisionProfileName crash). Real textures need a textured material cooked with the Mod Kit (.pak).
- Colours WORK (16:37): `CreateAndSetMaterialInstanceDynamicFromMaterial(0, BasicShapeMaterial)` + `SetVectorParameterValue(FName("Color"), {R,G,B,A})`, no crash. Passing `FName(...)` explicitly is safe; the earlier crash was most likely the plain-string FName argument. User confirmed the block types now look different.

## How SkyCraft ACTUALLY renders Minecraft inside Skyrim (read from the code, 2026-10-02)
DESIGN.md describes pixel compositing for everything; the code does something smarter for the world:
- WORLD (blocks, fluids, entities, mining cracks, selection outline): `fabric/.../render/WorldExporter.java` tessellates dirty chunk sections with Minecraft's own `ModelBlockRenderer`/`FluidRenderer` (<=12 sections or 3 ms per frame) into 44-byte vertices (pos, atlas UV, RGBA tint, block/sky light, face flags) and sends them, plus the block TEXTURE ATLAS (`SkyAtlas.java`, incl. animated regions), light-emitting blocks and per-section solid masks, over the render ring (`REN_SECTION`, `REN_ATLAS`, `REN_LIGHTS`, `REN_SOLIDS`, ...). Skyrim side `skse/src/WorldRender.cpp` draws them in its OWN D3D11 pass (runtime-compiled HLSL), sampling Skyrim's scene depth texture to discard hidden pixels (auto-detects reversed Z by probing), lit with Skyrim's sun/ambient/point lights/fog. So textures come from Minecraft's real atlas, and blocks sit in the 3D scene correctly occluded.
- HAND + HUD + SCREENS: `FrameExporter.java` renders Minecraft's main render target (hand, hotbar, hearts, inventory screens) on a transparent background, copies it GPU->staging buffer with 26.3's GPU API (`createCommandEncoder().copyTextureToBuffer`, one frame latency), triple-buffers RGBA8 frames into shared memory. `skse/src/Overlay.cpp` hooks `IDXGISwapChain::Present` (vtable 8), uploads with Map(WRITE_DISCARD), draws a fullscreen triangle with premultiplied-alpha blending (+ an invert blend for the crosshair), saving/restoring all D3D11 state.
- PLAYER MODEL: `AvatarExporter.java` (third person). INPUT: `InputBridge.java` + `skse/src/Input.cpp`. COLLISION into MC: `world/SkyCollision.java`, `TriCollider.java` + `skse/src/Collision.cpp`.
- Implication for HN (UE 4.20 is D3D11 too): the Present-hook overlay is generic and portable almost as is; the world pass is portable except where it reads Skyrim internals (camera, lights, depth-buffer location). Minecraft-side exporters target 26.3, the same version as ours. MIT licensed: keep their notice if we copy code.

## Rendering route, step 1: native Present hook (2026-10-02)
- `native/hn_gfx/hn_gfx.cpp` -> `hn_gfx.dll`, deployed as `ue4ss/Mods/HnGfx/dlls/main.dll` (mods.txt `HnGfx : 1`). UE4SS LoadLibrary()s it; we have no `start_mod` export (no UE4SS SDK), so DllMain pins the module and starts a thread that finds the DXGI swap chain vtable via a throwaway device/swap chain and patches Present (8) and ResizeBuffers (13). Test pattern drawn with `ID3D11DeviceContext1::ClearView` (no pipeline state touched). Log: `Binaries/Win64/hn_gfx.log`. Kill switch: empty file `Binaries/Win64/hn_gfx.disable`.
- Harness (`native/hn_gfx/tests/run.bat`): installs a pixel checker in the Present slot before loading the DLL, so the checker runs after our hook: 40/40 frames had the pattern, including after ResizeBuffers (hook releases its RTV first). B8G8R8A8 + flip-discard, like UE4.
- In-game attempt 1 CRASHED the game ~2 s after start. UE4SS loads C++ mods very early (before the game's D3D device exists), and its own GUI console window is a D3D11 swap chain presenting uncapped (~10k fps) on its own thread. The vtable patch hooks every swap chain, and v1 kept ONE global state that it swapped whenever a different swap chain presented: two threads -> released objects in use -> crash. The boxes showed on the UE4SS window only.
- Fix (v2): only the swap chain whose window class is `UnrealWindow` is touched (everything else passes straight through, no shared state); ResizeBuffers only releases our RTV for the game swap chain. Harness now runs an uncapped "noise" window on a second thread: game 40/40 frames with pattern, noise 283/283 clean. Also set `GuiConsoleEnabled = 0` in UE4SS-settings.ini (the GUI window was burning GPU at ~10k fps; we read UE4SS.log instead).

## Rendering route, step 2: Minecraft hand + HUD overlay (2026-10-02, built + harness-tested)
- Step 1 in game: PASS (test rectangles drawn on the game, game runs normally). Game back buffer 2560x1440, format 24 (R10G10B10A2), ~60 fps, one render thread.
- Overlay channel `protocol/hn_overlay.h`: separate mapping `Local\HelloNeighborMC_overlay_v0` (~99.5 MB: 3 slots of up to 3840x2160 RGBA8 + 4 KB header). Triple buffer with one ctl word (LATEST bits 0-1, READING bits 8-9) and CAS on both sides; host writes its back-buffer size into the header (hostViewportW/H). `tests/overlay_test.cpp`: 20k frames raced, 0 torn, 0 out of order.
- Minecraft (`fabric-mod`): mixins `LevelRendererMixin` (cancel world render in overlay mode -> main target = hand + HUD on the (0,0,0,0) clear), `MinecraftMixin` (capture after GameRenderer.render()), `WindowMixin` (focused / not iconified), `FramerateLimitTrackerMixin` (120 fps cap). `FrameExporter` (adapted from SkyCraft, MIT, attribution in the file) copies the main target GPU->staging buffer async and publishes into the overlay. `HnOverlay`: overlay mode = HN connected + in game + MC world loaded; hides the MC window (SDL_HideWindow; `-Dhnmc.showWindow=true` keeps it) and sizes it to HN's back buffer. Demo: survival + hotbar (diamond pickaxe/sword, stone, planks, bricks, glass, torches, apples, elytra).
- hn_gfx v3: reads the newest overlay frame into a dynamic RGBA8 texture, draws a fullscreen triangle with premultiplied-alpha blending, flipping rows (bottom-up flag). All drawing inside our own `ID3DDeviceContextState` (SwapDeviceContextState in/out) so Unreal's cached pipeline state is untouched; our state unbinds the RTV/SRV before swapping back (else ResizeBuffers fails). Harness: synthetic bottom-up frame with a 50% red square -> pixel exactly 80 00 3F over 00 00 7F blue, orientation correct, 40/40 frames incl. a resize, noise window untouched, viewport 800x450 reported back.
- First live run of the MC side: mixins applied, overlay mode ON, window hidden, capture 854x480 RGBA8_UNORM (MC default size until the new hn_gfx reports 2560x1440).
- Step 2 in game: PASS (2026-10-02 17:10). User sees Minecraft's hotbar, hearts, hunger, crosshair and hand over Hello Neighbor. Log: Minecraft resized itself to 2560x1440 on HN's request ~12 s after connecting; overlay uploads 1198 per 1200 game frames (~60 fps, 1:1) while the game keeps ~60 fps. Overlay mode toggles OFF/ON cleanly across an HN restart (window shown/hidden).

## Rendering route, step 3 part 1: input routing (2026-10-02, built + harness-tested)
- Overlay protocol v1 (`hn_overlay.h`): pixels moved to 64 KiB; input ring at 4096 (2048 x 16-byte events: key = SDL scancode, mouse button = SDL 1/2/3, scroll, cursor, text, release-all); header gains `mcFlags` (bit0 = a Minecraft screen is open) and `guiScale`, written by Minecraft each frame. hn_gfx refuses a mapping with another version (old Minecraft still holding v0).
- hn_gfx `hn_input.cpp`: subclasses the game window procedure (installed when the overlay opens). Keys mapped positionally (scancode set 1 -> SDL/USB HID). Routing only while Minecraft is alive (framesPublished advanced within 1 s): no screen -> mouse buttons, wheel, 1-9, E, Q, F to Minecraft (swallowed), rest to the game; screen open -> all keys/text/buttons/wheel to Minecraft, raw mouse becomes a cursor (drawn by the overlay shader), the game's camera does not move. Key-ups follow their key-downs; focus loss sends release-all. Harness: 8/8 routing checks (incl. "Minecraft silent -> everything to the game").
- Minecraft: `InputBridge` (adapted from SkyCraft, MIT) replays events into `keyboardHandler.keyPress` / `mouseHandler.onButton/onScroll/onMove` / `textInput` at the start of every frame (`MinecraftMixin` runTick HEAD), keeps a virtual keyboard for `InputConstants.isKeyDown`; `InputConstantsMixin` cancels the native mouse grab/release (the hidden window must never take the real mouse). Overlay ON grabs the mouse logically so the first click mines instead of grabbing.
- Real block changes: `LevelMixin` (server-side `Level.setBlock` RETURN) -> `BlockWatch` queue -> `HnBlocks.flush` -> BlockChange events. So mining/placing by the real Minecraft player updates the HN cubes; commands that change nothing still get an answer.
- Aim: HnLink now sends the CAMERA position (PlayerCameraManager) with z = camera - 1.62 blocks, so Minecraft's eye sits exactly at HN's camera, and the control rotation; `Coords.hostPitchToMc` flips the sign and normalises 0..360 (test added). Previously the pawn pitch was sent unconverted.
- Live test (17:26): hotbar switching (wheel, 1-9) and mining placed blocks with left click WORK through the real Minecraft player. Right-click placing did NOT: `ServerGamePacketListenerImpl.handleUseItemOn` only acts when `awaitingPositionFromClient == null`, and HnWorld did a server-side teleport every tick, so the server was always waiting for a teleport confirmation (mining uses handlePlayerAction, which has no such check). Fix: server teleport only for real jumps (new player, teleportSeq change, start/stop of driving, > 8 blocks); otherwise move the CLIENT player (setPos/rot, no gravity) and let normal movement packets carry it to the server (singleplayer owner skips the "moved too quickly" check). Verified: MC player matches target exactly, no movement complaints.
- HnLink: Minecraft restart (tick counter drops) -> automatic resync, so cubes from Minecraft's previous (now gone) world disappear.

## Direct placement on Hello Neighbor surfaces (2026-10-02 ~17:45, built, tests pass, awaiting in-game check)
Problem: the Minecraft world is void, so right-click only worked against an F8 seed block.
Fix (no permanent proxy blocks, so nothing can collide with the player and trigger server rejections):
- HnLink traces from HN's camera every sample (KismetSystemLibrary.LineTraceSingle, Visibility, 6 blocks) and sends
  `kCmdSurface` (6): hit point in MC blocks *1000 + face (Direction ordinal+1, 0 = no hit).
- Client `HnSurface` + `MinecraftPickMixin` (TAIL of Minecraft.pick): if the HN surface is within reach and closer
  than MC's own hit, the crosshair becomes a BlockHitResult on the cell just behind the surface. HN walls also
  occlude MC blocks/entities behind them.
- Server `ServerPlayerGameModeMixin`: around useItemOn, `SurfaceProxy` puts a BARRIER in that (air) cell and removes
  it afterwards, both with KNOWN_SHAPE|INVISIBLE|SKIP_ON_PLACE (no neighbour updates, so torches/ladders stay).
  BlockWatch is muted meanwhile. Log line: "Click on a Hello Neighbor surface at x, y, z".
- Height alignment: HnLink picks a per-map vertical grid offset (yOff, uu) so the floor you first stand on is a block
  boundary; placed blocks sit on the floor instead of floating. Applied to sent position, trace hits, cube spawns.
- F8/F7 remain as debug keys only.
Open: whether UE4SS fills the FHitResult out-param table (first trace logs its fields).

## Step 4 plan: Minecraft world layer (real textures)
- MC side: export chunk meshes near the player (block vertices + UVs into the block atlas) and the atlas itself over a
  new shared memory channel; dirty-chunk updates via BlockWatch.
- hn_gfx: upload atlas once, vertex buffers per chunk; draw in Present with HN's view/projection, depth-tested
  against UE's scene depth. Risk to probe first: getting UE 4.20's scene depth + camera matrices at Present time.
- Then the HN cubes become invisible collision proxies (neighbour still bumps into builds).

### Crash (2026-10-02 17:50): KismetSystemLibrary.LineTraceSingle from UE4SS Lua
First call (no hit) returned and filled the table (fields incl. Actor, Component, PhysMaterial); the game died within
the next calls, stack entirely inside UE4SS's Lua param conversion. Treat FHitResult out-params as unusable here.
Replacement: SphereOverlapComponents (out: component array) + PrimitiveComponent.K2_LineTraceComponent (outs: FVector,
FVector, FName) on static mesh / landscape components. Crash guard: breadcrumb file `hnlink_trace_breadcrumb.txt`
written around each call until 300 hits succeed; on the next load a leftover breadcrumb marks that method broken in
`hnlink_trace_broken.txt` (both in Binaries\Win64) so it can never crash the game twice.

### 17:55: no trace method passed the check
In UE 4.20, PrimitiveComponent.K2_LineTraceComponent ALSO has an `OutHit` FHitResult param, so the guard refused it.
Every engine trace returns an FHitResult. New method "probe": SphereOverlapComponents(ctx=pawn, p, r=16, {WorldStatic,
WorldDynamic}, StaticMeshComponent, {pawn}, {}) used only for its bool return. March along the view ray in 32 uu
steps, bisect the first contact 6x, then find the face: among axes facing the camera (largest ray component first),
the one where stepping 8 uu back frees the sphere; surface = contact centre - 16 uu along that normal. ~24 calls per
trace at ~15 traces/s; cost is logged ("trace cost").

## Step 4a: scene depth + camera probe (2026-10-02 ~18:15, built, harness passes, awaiting in-game check)
- hn_gfx `hn_world.cpp`: hooks the immediate context's OMSetRenderTargets (33) and ...AndUnorderedAccessViews (34)
  to count depth-texture binds per frame; at Present picks the most-bound depth texture of back-buffer size as the
  scene depth, reads it through our own SRV (R24G8 -> R24_UNORM_X8, R32G8X24 -> R32_FLOAT_X8X24, ...).
  Note: in the harness the context vtable is on the heap (not in d3d11.dll); patching it is per-object, fine.
- Camera: HnLink writes `hn_camera.txt` (PlayerCameraManager address + reflected offsets of CameraCache.POV
  Location/Rotation/FOV); hn_gfx reads the floats every frame under SEH. Projection rebuilt UE-style: reversed Z,
  infinite far, near plane assumed 10 uu (to verify), horizontal FOV, aspect = back buffer.
- F9 (HnLink) toggles `hn_testcube.txt`: a loud checker cube on the F8 cell, manually depth-tested against the scene
  depth, plus a depth preview top-right. Expect: sits on the F8 cube, stays put, hides behind walls.
- Watch for: one-frame camera lead (game thread updates CameraCache before the render thread presents) -> cube
  swims while turning; if so try LastFrameCameraCache.

## Step 4b: Minecraft blocks drawn in Hello Neighbor (2026-10-02 ~18:37, built, awaiting in-game check)
- Depth finding (18:24 run): hook calls = 0. Cause (proven in the harness): this D3D11 runtime keeps the context's
  function table inside the object and REWRITES it on SwapDeviceContextState, which hn_gfx does every frame. Fix:
  `rehook()` after our state restore every frame (endFrame).
- Hand jitter: 255x "moved wrongly" — MC feet are ~0.26 blocks below HN's floor (eye matched to the camera), inside
  floor-level blocks. `ServerGamePacketListenerMixin` sets player.noPhysics for each move packet while
  `HostDrive.active` (HN driving).
- World channel `Local\HelloNeighborMC_world_v0` (protocol/hn_world.h, WorldLink.java): header, 2 mesh slots
  (393216 verts of {xyz, uv, rgba}), block atlas (26.3: 2048x2048, id `AtlasIds.BLOCKS` = minecraft:blocks, NOT
  TextureAtlas.LOCATION_BLOCKS). Atlas read back once via copyTextureToBuffer.
- `HnWorldMesh` rebuilds from HnBlocks.known on every block change (+1 rebuild 4 ticks later): BlockStateModel
  parts -> BakedQuads, Block.shouldRenderFace culling, BlockTintSource tint, fixed face shade.
- hn_gfx draws it: own D32 depth (GREATER, reversed Z), point-sampled atlas, alpha cutout, scene-depth discard.
  MC -> UE: (x, -z, y) * 84, z - yOff (yOff now 5th field of hn_camera.txt).
- HnLink hides the cube actors (SetActorHiddenInGame, collision stays) while hn_world_status.txt shows hn_gfx drawing.
- Not yet: fluids, block entities (chests/beds/signs), animated textures, lighting, mining cracks, block outline.

### 18:41: textures CONFIRMED by the user; hand jitter gone (0 "moved wrongly"); depth still 0 hook calls
Even with the per-frame vtable re-patch, zero calls. Harness log with code hooks shows why: after a state swap the
context's table holds DIFFERENT implementation addresses for OMSetRenderTargets / ...AndUAVs (e.g. ...8950 vs ...9E90),
so vtable patching chases a moving target. Now: MinHook (vendored, BSD-2, native/third_party/minhook) code-hooks every
implementation address the table ever shows (re-read each frame, up to 4 per method).

### 18:45: scene depth WORKS (user confirmed: blocks hidden behind walls, F9 depth preview shows)
Code hooks see ~270 OMSetRenderTargets calls/frame. Scene depth = 2560x1440 fmt 44 (R24G8_TYPELESS, bind DS|SRV),
~24 binds/frame; read as R24_UNORM_X8_TYPELESS. Also seen: 128x128 and 2048x2048 R32G8X24 (shadow maps).
Selection briefly dropped once for ~3 s (no scene frames, e.g. pause/loading) and came back by itself.
STEP 4 (world layer) DONE: textured Minecraft blocks, depth-correct, cubes are invisible collision.

## Step 3b: Minecraft moves the player (2026-10-02 ~19:01, built, all tests pass, awaiting in-game check)
- Protocol: kHostMcDrives (HostState flag 8), kMcDriving (McState flag 16), kCmdProxyCell (7: x; (y&0xFFFF)|(z<<16);
  64-bit quarter-block mask lo/hi), kCmdProxyClear (8), overlay mcFlags kOverlayMcMoveKeys (2). Bridge M line now ends
  with the eye height.
- MC: block `hnmc:proxy` (HnProxy): invisible, unbreakable, dynamicShape; collision+outline from a static mask map
  shared by server and client; placed with UPDATE_CLIENTS|KNOWN_SHAPE|SKIP_ON_PLACE, BlockWatch muted, never over a
  real block. HnWorld drive mode: physics on, rotation from HN, teleports when HN bumps teleportSeq, respawn -> last
  on-ground spot; publishes the partial-tick-interpolated position EVERY RENDER FRAME (HnOverlay.beginFrame).
- hn_input: W A S D Space LShift LCtrl go to Minecraft while kOverlayMcMoveKeys (harness case added).
- HnLink: scanner (BoxOverlapComponents bool-only, StaticMeshComponent filter, WorldStatic+WorldDynamic, pawn
  ignored): region r3, -2..+3, floor first, 64 queries/tick, rescan after 4 s; empty cell = 1 query, else 9 + 8 per
  occupied octant. Drive flag only after one full pass. Follow: DisableMovement on the pawn, K2_TeleportTo (no
  FHitResult) every game frame to MC eye - measured camera offset; HN-initiated moves (>150 uu from where we put it)
  bump teleportSeq and wait for MC's ack. F4 toggles. Tick runs every frame while following, else every 33 ms.
- Known limits v1: physics objects (boxes) are not solid for Minecraft (PhysicsBody not scanned); landscape not
  scanned (StaticMeshComponent only); upper floors quantized to 21 uu.

### 19:08 first drive test: jittery, fell through floors, HN arms visible
- Jitter: tickMcDrives published the END-of-tick position (partial 1.0) 20x/s between frame publishes of the
  interpolated one -> forward/back jumps. Fix: only frames publish position.
- Falling: scanner filtered to StaticMeshComponent; HN floors include non-mesh geometry. Now: any class with
  WorldStatic, or StaticMeshComponent with WorldDynamic (2 queries for empty cells; budget 96).
- Fall loop: fell out of MC world -> respawn at void spawn -> HN pawn followed -> fell again. Now: >30 blocks below the
  last safe spot (10 ground ticks) -> snapped back, no death; respawn -> safe spot.
- Arms visible / bob: HN's camera hangs off the animated head; with movement off the anim moves it. Now: pawn hidden
  (SetActorHiddenInGame) while following, and the pawn target uses THIS frame's camera offset in x/y/z.

### 19:15 movement confirmed "proper"; A/D were swapped -> coordinate mapping was a MIRROR
mc.z = -host.y with Unreal left-handed and Minecraft right-handed reflects the world (A/D swapped, and textures /
directional blocks / text would be mirrored). New mapping everywhere: mc = (hx, hz, hy) / 84, mcYaw = hostYaw - 90,
hostYaw = mcYaw + 90 (Coords.java, hn_coords.h, HnLink cube spawn/cellAhead/trace/scanner/follow/test cube, hn_gfx
mesh shader). Tests: "right is right" handedness checks in layout_test.cpp and CoordsTest.rightStaysRight.

## Third person + Minecraft entities + all controls (2026-10-02 ~19:37, built, tests pass, awaiting in-game check)
- McState reserved[24] -> camDX/DY/DZ (camera - feet), camYaw, camPitch, camFov (vertical); kMcCamDetached (32).
  Bridge M line appends eye + those 6. HnWorld.publishDriven fills them from gameRenderer.mainCamera() each frame.
- World channel v2 (mapping renamed `_v2`; 108 layout values cross-checked C++/Java): texture table (512 x
  {id,w,h,heapOffset}) + 64 MB heap, 2 entity slots (1024 batches {texture, first, count, flags} + 196608 verts),
  entOrigin per slot. Texture id 0 = block atlas.
- MC: HnTextures (any texture -> id, async GPU readback once), HnEntities = SkyCraft AvatarExporter adapted (MIT):
  SubmitNodeCollector capturing the player (only when the camera is detached), entities within 48 blocks (items
  included), block entities, particles; batches per texture, raw UVs. Accessor mixins RenderType/RenderSetup/
  TextureBinding. Runs in HnOverlay.afterRender before FrameExporter.
- hn_gfx: syncTextures/syncEntities, vsEnt/psEnt (texture * vertex colour, alpha cutout, derivative-normal face
  shade), cutout pass then translucent pass (alpha blend, no depth write), sharing our depth buffer with the blocks.
- Input: while Minecraft drives, EVERY key goes to Minecraft except Esc, `, F4, F6-F12 (harness: T -> MC, Esc -> game).
- HnLink: F5 debug binding removed. Third person: spawns a CameraActor, SetViewTargetWithBlend to it, K2_TeleportTo
  it every frame to Minecraft's camera (yaw+90, -pitch), FieldOfView from MC's vertical FOV at 16:9. First person:
  view back on the pawn.
- Bug found from the log: after a Minecraft restart HnLink re-enabled driving at once (it thought the geometry was
  sent; the new world has none). Fix: MC restart also sets gridDirty (rescan before driving).

### 19:45 regression: everything Minecraft-driven broke (collision, F4, F5)
HnLink's new M-line parser did `v[#v+1] = tonumber(w)`; tonumber("M") is nil, so every field shifted by one and
flags got the x coordinate -> "number has no integer representation" on every tick -> link error loop.
Fixed (explicit index, `math.tointeger` for flags) and now EXECUTED before deploying: fengari (Lua 5.3 in JS, npm)
runs the real parser block from main.lua on a real bridge line (scratchpad/parse_test.js). Note: fengari integers are
32-bit, so 64-bit mask tests must not rely on it (UE4SS Lua 5.4 is 64-bit).
Lesson: syntax/global checks are not enough for Lua changes; run the changed logic.

### 19:50 void loop + HN menu unusable
- The 19:37 parser-bug run drove with no proxies: fell out of the world; respawn went to the host target, which was
  HN's pawn that had FOLLOWED Minecraft below the map -> fall/die loop; Ctrl+R resumed from that deep spot.
  Fix (HnWorld): an anchor always exists while driving (drive start, every spot stood on for 10 ticks, every
  HN-initiated teleport); falling 30 blocks below it or dying -> back to it. Void unreachable.
- HN pause menu: arrows/Enter/clicks went to Minecraft. Now HnLink sends kHostMenuOpen while
  GameplayStatics.IsGamePaused; Minecraft releases held keys and sets overlay kOverlayHostMenu (4); hn_input then
  passes every message to the game (harness case added).
- HnLink logic now executed with fengari before deploying (parse_test.js, flags_test.js).

### 19:53-20:00 falling through floors again: three bugs
1. HnBlocks.scheduleProxyOps: a proxy op arriving before the MC world existed was dropped by onServer() but left
   proxyTaskQueued=true forever -> NO geometry for the whole session. Now: no server -> flag reset, ops stay queued,
   flush() retries every tick; schedules on the server it checked.
2. Start position inside the floor (HN's hidden pawn had been left half a block low by earlier falls): Minecraft
   doesn't push players up out of blocks, so they fall through. HnWorld.unstick(): if the box overlaps geometry,
   lift to the first free height (1/16 steps, <= 3 blocks). Seen working: "lifted 0.50 blocks to y=2.00".
3. Torn reads: with McState now written every frame, hn_shm seqRead gave up after 64 spins and left `out`
   half-copied/zero; the bridge sent that (tick 0 = "Minecraft restarted", flags 0 = "not driving") -> drive off +
   geometry reset churn. seqRead now copies into a temp (out untouched on failure, 4096 tries + yield), the bridge
   resends the last clean McState, McLink.readHost commits fields only on a valid copy. layout_test covers it.
   HnLink: restart only if tick drops below 2000; unreadable map name keeps the last map.

### 20:05 third-person camera collapsed onto the face
Minecraft's Camera.getMaxZoom casts 8 rays from points within 0.1 blocks of the eye; any proxy there collapses the
distance to ~0. Cause: the scanner only ignored the pawn itself, not actors ATTACHED to it (arms/held items around
the hidden pawn's head) -> proxies at the head. Fix (HnLink): ActorsToIgnore = pawn + GetAttachedActors() (refreshed
every 2 s, crash marker "attached" around each call), and sub-cells overlapping the player's own body (Minecraft
box, feet+0.05 .. eye+0.3) are never solid (bodyMask, executed in fengari). MC logs "Third person: camera X blocks
from the eye ...; proxy blocks around the head: n/27" every 5 s while detached.

## Steve skin + crosshair accuracy (2026-10-02)
- `DefaultPlayerSkinMixin`: `DefaultPlayerSkin.get(UUID)` always returns wide Steve (index 15). Only the fallback; a real account's downloaded skin still wins. The dev account (random PlayerNNN UUID) used to get a random one of the 9 defaults.
- HnLink `traceSurface`: while Minecraft drives, the host surface trace starts at Minecraft's eye with Minecraft's yaw/pitch (host yaw = mc yaw + 90, host pitch = -mc pitch), not at Hello Neighbor's camera (behind the player in F5). Tested in fengari (`scratchpad/aim_test.js`).
- `HnSurface.adjustPick`: a Minecraft hit on a proxy is always kept (exact geometry); a hit on a real block is only replaced if the host surface is >0.35 blocks closer (the sphere probe reads short). Before, the crosshair could flip between a block and the wall behind it, which restarts mining.
- Stale objects after menu -> level: `FindFirstOf("PlayerController")` / `FindFirstOf("GameModeBase")` can return the main menu's (no pawn / wrong world). Symptoms: inGame never true ("only the UI"), and every spawn returns nothing (no cubes, no third-person CameraActor -> setView fell back to the pawn = face-cam). Fix: `playerController()` helper (prefers a controller with a pawn, via FindAllOf) is used everywhere and as the spawn world context; setView no longer falls back to the pawn when the camera actor is missing.
- Crash on level load (2026-10-02 23:06): cubes had been spawned in the main menu level (Start); after loading Act1 the map-change cleanup called GetFullName/K2_DestroyActor on the dead actors -> native crash inside UE4SS (pcall cannot catch). Fix: `checkWorld()` at the top of every tick drops all actor references (cubes, camActor, pcCache, ignoreList, follow state) without touching them when the controller they belong to is no longer live (address compare via FindAllOf/GetAddress); plus `RegisterLoadMapPreHook` / `NotifyOnNewObject(PlayerController)` call the same `forgetWorld`. No cube actors are spawned on the menu map. Tested in fengari (`scratchpad/world_test.js`).
- Startup crash (23:09): hn_gfx Present hook recursion with Steam's overlay (gameoverlayrenderer64 frames repeating on the stack, "game Present" counter at ~44k/s, stack ran out inside pollFiles' fopen). Timing race: depends on which hook installs first. Symbols: rebuild with /Zi /DEBUG /OPT:REF /OPT:ICF /MAP into a temp dir (same size/code) and map RVAs with awk. Fix: thread_local re-entry guard in hookPresent: inner calls never draw, pass straight to the original, cut at depth 8 (returns S_OK); first re-entry logs the caller module, our original's module and its first bytes.
- Inside-out player model: player skins are captured as entity_translucent; hn_gfx drew that pass without depth writes, so unsorted back faces covered front faces. Translucent entity pass now writes depth (like Minecraft), psEnt already discards alpha < 0.1.

## Block outline + mining crack (2026-10-02)
- Built in `HnEntities.submitTargetDecals` from what Minecraft extracted this frame: `levelRenderState.blockOutlineRenderState` (target pos + shape, high-contrast flag) and `levelRenderState.blockBreakingRenderStates` (pos, state, stage 0-9). Published as extra translucent entity batches, so hn_gfx needed no change.
- Outline: each `shape.forAllEdges` edge becomes a camera-facing quad, half width = appropriateLineWidth * dist / (projection.m11 * window height) (same on-screen width as Minecraft's lines), ends extended by the half width, vertices pulled 0.2% toward the eye. Colour as vanilla (black alpha 102 / high-contrast). Texture: `HnTextures.white()` (1x1 white published directly to the heap). Skipped for proxies.
- Crack: block model quads (same model/seed as LevelRenderer.submitBlockDestroyAnimation), pushed 0.002 off the faces, UVs projected per face axis, texture `minecraft:textures/block/destroy_stage_N.png` via HnTextures.idFor. Alpha-blended (vanilla multiplies: close enough).

## Fluids (2026-10-02)
- `HnFluids`: Minecraft's own `FluidRenderer` (new FluidRenderer(modelManager.getFluidStateModelSet())) tesselates every fluid cell among HnBlocks.knownBlocks into a recorder (positions are section-relative: add pos & ~15). Rebuilt with the world mesh (block changes include fluid spread). Sent every frame by HnEntities as block-atlas batches (id 0): TRANSLUCENT layer (water) in the blended pass, the rest (lava) opaque. No protocol/native change.
- Flow vs proxies: FlowingFluid.canHoldAnyFluid is false for the proxy block (not a LiquidBlockContainer, not WASHED_AWAY_BY_FLUIDS), so water never enters proxy cells: it sits on Hello Neighbor floors. Limitation: proxies only exist where HnLink scanned (radius 3 around the player); water placed further out over unscanned ground can fall into the void.
- Water animation: the atlas is read back once, so water/lava show a single (static) frame.
- `HnBlocks.idOf`: blocks without collision (fluids, torches, flowers, ...) are sent to Hello Neighbor as AIR (no collision cube); `known` now tracks by isAir, so Minecraft still draws them.
- Starter kit: water + lava bucket in hotbar slots 8-9; first inventory row: apple, elytra, 2 water, 1 lava, 1 empty bucket.

## Neighbour, step 1: target + knockback (2026-10-02 ~23:45, built, awaiting in-game check)
- Protocol: kCmdNeighbor 9 (feet x,y,z *1000 in Minecraft space, Minecraft yaw *100), kCmdNeighborSize 10 (radius, height *1000; c = present), kEvNeighborHit 5 (strength *100, push dir x,z *1000). Layout cross-check updated (layout_dump + Layout.java).
- HnLink: `findNeighbour()` = first AI-controlled (IsA /Script/AIModule.AIController) Character other than the pawn, looked up from FindAllOf("Character") EVERY time (no cached actor, so no dead references). Logs all characters + controller classes once per map (gives the real class names). `neighbourTick` sends position when moved/every 1 s, size every 2 s; `neighbourHit` = LaunchCharacter(dir * min(1100, 250 + 110*strength), Z 180 + 20*strength, override XY and Z).
- Minecraft: `HnNeighbor` keeps a vanilla Interaction entity (tag hnmc_neighbor) at his feet, sized to his capsule; the client copy is snapped to the link position every tick (server tracking lags). Fabric AttackEntityCallback (server side) -> strength = ATTACK_DAMAGE attribute * attack cooldown -> queued -> client thread pushes kEvNeighborHit.

## Neighbour fix + impacts on Hello Neighbor's world (2026-10-02 ~23:58, built, awaiting in-game check)
- Why hitting the neighbour did nothing: `FindAllOf("Character")` in this UE4SS build returned unrelated assets (AnimSequence, SoundCue objects), so no neighbour was ever sent. Now: GameplayStatics.GetAllActorsOfClass(AIController) (fallback FindAllOf("AIController")), each checked with IsA, pawn = the neighbour; cached 1 s, forgotten on level change. Logs "AI controllers (...): <controller class> -> <pawn class>".
- `risky(name, fn)`: breadcrumb crash guard for new engine calls (names: allactors, overlapout); trusted after 20 clean calls. `arrayOut(ret, out)` reads UE4SS output arrays either way.
- Events: kEvSurfaceHit 6 (point *1000, strength*100 | kind<<24; kind 0 melee, 1 arrow), kEvExplosion 7 (centre *1000, radius*100). Java: `dev.hnmc.HnImpacts` queue (any thread) drained by HnNeighbor.tick on the client thread. Sources: client mixin Minecraft.startAttack (target is a proxy or HnSurface's air hit), ArrowMixin AbstractArrow.onHitBlock on a proxy, ExplosionMixin ServerExplosion.explode HEAD; arrows vs the neighbour checked in HnNeighbor.update (Interaction entities are not hit by projectiles).
- HnLink `hitWorld`: SphereOverlapComponents (WorldStatic/WorldDynamic/PhysicsBody/Destructible) with an output array -> per actor GameplayStatics.ApplyDamage(damage = strength*10); DestructibleComponent (ApexDestruction) ApplyDamage / ApplyRadiusDamage; simulating components AddImpulse (vel change) / AddRadialImpulse. Every new actor class hit is logged once with its functions matching break/shatter/smash/crack/destr/damage/hit/impact/glass -> use that to add a specific reaction (e.g. a window's own break function) if generic damage is not enough.
- Kit: hotbar pickaxe, sword, bow, stone, planks, glass, torch, TNT, flint & steel; inventory: 2x64 arrows, 2 water, lava, bucket, bricks, apple, elytra.
- 2026-10-03 00:05 test: impacts reach real objects (Door_inward_C, BP_Lamp_post_metal_C, BP_Picture_set_2_C, BP_Switcher_C, BP_Window_3_C, Landscape, ...) but ApplyDamage breaks nothing; no DestructibleComponents hit. GetAllActorsOfClass(AIController) returned controllers that failed IsA -> neighbour never found.
- Changes: neighbour = Character (GetAllActorsOfClass(/Script/Engine.Character)) that is not the pawn and whose controller is not a PlayerController. Impacts now also throw a hidden 12 cm physics ball (StaticMeshActor + Sphere mesh, PhysicsActor profile, rigid-body notify, 5 kg, life span 1.5 s) into the point (`throwPebble`/`throwAt`; explosions throw one at up to 12 nearby components) so Hello Neighbor's own hit logic reacts like to a thrown object. `logImpactTarget` now logs the whole Blueprint parent chain with all functions plus the components (K2_GetComponentsByClass, risky "components").

## Natural knockdown + real window breaking + far arrows (2026-10-03 ~00:30, built, awaiting in-game check)
- From the game's loose Content (.uasset names) and the exe's reflection strings: the neighbour is BP_Sosed_C (SosedAIController; controller reads none in scripted states -> now accepted). Windows are BP_Window_N_C on native `Window3`: GlassMesh1-3 + bCrashed1-3, one GlassDestructibleMesh (window_N_glass_DM), Sound, m_fLifeTimeDestructibleMesh, OnHit bound to OnActorHit; no callable break function. Throwables (BP_Apple/Ball/Box/Can/Book/...) are Blueprints of native `Simple` (OnThrow(Character), OnHit(pSelfActor, pOtherActor, normalImpulse, Result), GetHitDistToSosed): their native hit code is what breaks windows and stuns the neighbour in normal play.
- So every melee/arrow hit now throws an invisible copy of a real item already in the level (`throwItemAt`: spawn class of an existing Simple actor, hidden, life span 3 s, SetNeedSave(false), OnThrow(pawn) when its signature is one object param, root SetSimulatePhysics + SetPhysicsLinearVelocity) from 45 uu in front of the target into it (neighbour: chest, 2600 uu/s; surfaces: 1600 + 300*strength). LaunchCharacter only as a fallback. The earlier physics ball never spawned: /Engine/BasicShapes/Sphere was not loaded (now Cube).
- Far arrows flew through walls because proxies exist only where scanned. Now `ServerEntityEvents.ENTITY_LOAD` on an arrow -> `HnImpacts.arrowPath` predicts its flight (drag 0.99, gravity 0.05, 8 samples/tick, <= 400 cells) -> kEvScanCell 8 per cell -> HnLink `voxRequest` queue scanned first in voxTick with 2x budget (cells fresher than 30 s skipped). Stats in the drive log: arrowPathCells=scanned/asked. Tested in fengari (path_test.js).
- 2026-10-03 ~00:40: windows break (thrown real items work). Neighbour flickered in/out (lookup alternated BP_Sosed_C / none): IsValid() dropped -> no IsValid in findNeighbour, BP_Sosed_C matched by name, "gone" only after 3 s missing. BP_Sosed.uasset names: SlipAndFall, Fall, MixerFall (+ fall_run/fall_walk anims, OnThrowGlue/OnThrowTomato) -> `neighbourFall` calls the first parameterless one on his class chain (all logged with signatures), 3 s cooldown; item throw (from 130 uu, outside his capsule) only as fallback. Thrown items muted: all ObjectProperty *Sound* on the item class chain set to nil before FinishSpawningActor; golden (collectible) items not used.
- 2026-10-03 00:45: neighbour knockdown works via his native `Sosed:MixerFall()` (ReturnValue:BoolProperty only; SlipAndFall/Fall not found as parameterless). Windows stopped breaking because the game crashed (00:32, stack entirely inside UE4SS) during the single guarded "throwitem" step, which the breadcrumb then disabled for good. Throw now split into steps item.spawn / item.mute / item.finish / item.onthrow / item.launch, each always breadcrumbed (never "trusted"), optional ones (mute, onthrow) skipped when broken. Removed the old "throwitem" line from hnlink_trace_broken.txt.

## Hello Neighbor interactions + its inventory as Minecraft items (2026-10-03)
- HN's pick-up/use/throw handlers are native, NOT UFUNCTIONs (probe: hn_interact_probe.txt). Callable: Human.GetInventory/GetHoldingItem/GetDragObject/SelectInventoryObject(Byte)/GetTimeNotInput/IsActive; Inventory.GetActorsInSlots/GetCurrentObject/PutActor/RemoveObject. No focus getter; BP_Cursor_C has only animations.
- So Minecraft presses HN's own keys: OverlayHeader.hostTaps[4] (offset 48). kTapUse/Apply/Throw = (pressCount << 1) | held; hn_gfx (hn_input.cpp pumpTaps) posts WM_HN_TAP and hands WM_KEYDOWN/UP or mouse messages straight to the game's original WndProc (router bypassed), holding while the MC key is held (pick-up needs a hold). Keys = the player's CURRENT HN bindings: HnLink reads PlayerInput.ActionMappings (InputPickUp/InputAction, InputApply, InputThrow/InputPutDown) into hn_keys.txt; hn_gfx re-reads it every ~2 s. The user rebinds pick-up E -> R in HN's menu (E is MC's inventory).
- MC bindings (HnInteract): R pick up / interact, G use, X throw (G/X only with an HN item in hand).
- HN inventory -> `hnmc:hn_object` items (HnItems): HnLink gives each actor in GetActorsInSlots an id, sends kCmdHnItem 11 (id | part<<16 | present<<24, 12 name bytes per part); server reconciles the MC inventory (new item -> into the hand); kEvHnSelect 9 = item id in the MC hand (0 = MC item: HnLink hides HN's held actor). SelectInventoryObject index base found by trial (list index first).
- Held pose: mixins on FirstPersonHandsAndItemsRenderer / ItemInHandLayer skip submitting an hn_object and record the PoseStack (first person: starts with the inverse view rotation -> camera pos + offset = world; third person: HnEntities' pass, relative to floored player pos) -> kEvHeldFirst 10 / kEvHeldThird 11 (world *1000, body yaw). HnLink teleports the actor so its bounds centre is there, scaled to half-extent 18 uu (min of all/colliding bounds; the magnet's all-parts bounds were 732 uu), real scale restored when it leaves the inventory.
- Open (user: "not perfect, later"): ~1 frame lag behind Steve; HN item rotation is yaw only (no arm-swing rotation); first person shows no arm.
- 2026-10-03 later: poses now sent RELATIVE (first person: offset from the MC camera + rotation delta vs the camera, kEvHeldRot 12; third person: offset from the feet + body yaw | arm swing << 16) and added in HnLink to the camera/feet of the same update (fixes pairing frame-N pose with frame-N+1 camera). User: "put this on hold" — remaining feel issue not re-tested; the true fix would be drawing HN's mesh in hn_gfx.

## Right-click to interact (2026-10-03, works)
- HnLink traceSurface: within 230 uu, interact.classify overlaps a 10 uu sphere just inside the hit surface; an owner IsA Simple / Door / Tumbler / Electric (or has an InteractiveComponent) sets kCmdSurface d | 0x100. NOT FlatActor (also windows, Stand furniture, BP_Act1_C). Class verdicts logged once ("right-click interact: X -> yes/no [chain]").
- MC: MinecraftUseMixin (startUseItem HEAD) -> HnInteract.rightClick: interactable + not sneaking -> holds HN's pick-up/interact key while right mouse is held (same channel as R); sneak + right-click places.
- The crosshair trace ignores the held HN item (interact.held). playerController()/sample() use exists() (name) instead of IsValid (after a restart it found no pawn).

## Mobs vs the neighbour, kit, misc (2026-10-03)
- HnMobs (server): a frozen, silent villager "body" (tag hnmc_neighbor_body, NOT invisible: mobs only notice invisible targets from ~2 blocks) at his feet; HnEntities skips it by entity id (HnMobs.bodyId). Fighters (Enemy, AbstractGolem, tamed TamableAnimal) within 48 blocks are set on it every 10 ticks; MobTargetMixin redirects any fighter's setTarget(player) to it. ALLOW_DAMAGE on the body -> HnImpacts.neighbour (MixerFall); Enemy damage to the player cancelled.
- Mobs could not path at all: ProxyBlock.isPathfindable used the shape at BlockPos.ZERO (empty) -> every HN floor/wall looked like air. Now returns false. Floors around each mob / the body (radius 2, per cell moved) and a corridor mob -> body (every 2 s) are requested via kEvScanCell; HnLink gives the player's area its own scan budget (the requests starved it: F4 never took over after a level load).
- World: difficulty Normal, spawn_mobs off, advance_time off, time noon (night darkened the hand / blocks: Minecraft lights them by its time of day); MobSunMixin cancels Mob.burnUndead.
- HN cutscene (Human.IsPlayCutScene) counts as its menu: all input to HN (Space skips); HnOverlay hides MC's HUD (gui.hud.toggle) while HN's menu / a cutscene is up.
- Name: PlayerNameMixin -> "Nicky". Kit (HnWorld.equip): diamond tools + armour, enchanted bow (Power V, Infinity, Flame, Punch II, Unbreaking III), infinite pearls/arrows/TNT/rockets (HnKit), "Mobs vs the neighbour" shulker box (spawn eggs, bones), "Extras" chest (building, redstone, golem parts, utility); 4 slots kept free for HN pick-ups.

## Performance (2026-10-05): stutter while flying = Lua garbage collector
- Measured with new logs: HnLink "frame cost ms" + "slow frame" lines (UE4SS.log), hn_gfx "our cost per frame" +
  frame gaps (hn_gfx.log), Minecraft "Minecraft frames ... frame copy" (runClient.out).
- Rendering is cheap: overlay upload ~0.6 ms, draw <0.1 ms, Minecraft ~119 fps, frame copy ~0.8 ms.
- Stalls of 50-250 ms were Lua GC pauses (memory dropping 30-90 MB inside the slow frame), driven by ~5 MB/s of
  scanner garbage: new tables per BoxOverlapComponents call and "x,y,z" string keys per cell lookup.
- Fixes: number cell keys (voxAhead.key), reused P/E/OUT query tables, cell records updated in place, generational GC,
  per-frame scan time cap (VOX_FRAME_MAX_S 5 ms, checked between cells). After: worst ~83 ms, rare.
- Coroutines do NOT work for engine calls in UE4SS 3.0.1 ("lua state has no instance inside lua_instances").
- Left: voxCells grows without bound (207k cells after a long flight, ~115 MB Lua heap) -> occasional GC pause.
  Next step would be dropping far-away cells or a per-map geometry cache on disk.
