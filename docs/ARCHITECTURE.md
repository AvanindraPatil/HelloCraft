# How it works

Hello Neighbor is an Unreal Engine 4.20 game; Minecraft is a Java game. Neither can load the other, so the mod runs
both and connects them. Minecraft does the *player* (movement, physics, items, blocks, mobs, HUD); Hello Neighbor does
the *world* (the house, the neighbour) and shows everything on screen.

```
 Hello Neighbor process                            hn_bridge.exe              Minecraft process (hidden window)
 ┌──────────────────────────────────┐   named     ┌──────────────┐  shared    ┌──────────────────────────────┐
 │ UE4SS                            │   pipes     │ owns the     │  memory    │ Fabric mod "hnmc"            │
 │  HnLink (Lua)  game logic ◄──────┼─text lines─►│ main mapping │◄──────────►│  HnWorld / HnBlocks / ...    │
 │  HnGfx  (C++ DLL) rendering,     │             └──────────────┘            │                              │
 │         input  ◄─────────────────┼──────── overlay mapping (frames, input) ─┤  FrameExporter, InputBridge  │
 │                ◄─────────────────┼──────── world mapping (blocks, models) ──┤  HnWorldMesh, HnEntities     │
 └──────────────────────────────────┘                                         └──────────────────────────────┘
```

## The parts

| Part | Where | What it does |
|---|---|---|
| **HnLink** | `ue4ss-mods/HnLink/Scripts/main.lua` | UE4SS Lua mod inside Hello Neighbor. Reads the player, the neighbour, the level; scans Hello Neighbor's geometry for Minecraft; moves Hello Neighbor's player where Minecraft says; applies Minecraft's hits, explosions and interactions to Hello Neighbor. |
| **HnGfx** | `native/hn_gfx/` | C++ DLL loaded by UE4SS. Hooks Direct3D 11 `Present` and draws Minecraft's image over the game (hand, HUD, screens) and Minecraft's blocks and entities into the 3D scene (depth-tested against it). Also takes over the game window's input and forwards it to Minecraft. |
| **hn_bridge** | `protocol/tools/hn_bridge.cpp` | Small console program. UE4SS Lua cannot map shared memory, so Lua talks to the bridge over two named pipes and the bridge owns the shared mapping. |
| **hnmc** | `fabric-mod/` | Fabric mod for Minecraft 26.3. Runs a hidden void world; mirrors Hello Neighbor's geometry as invisible blocks; lets Minecraft's physics move the player; exports frames, blocks and entities; adds the kit, mobs-vs-neighbour, death handling, `/hnreset`. |
| **protocol** | `protocol/*.h` | The shared memory layouts, mirrored by hand in Java (`link/Layout.java`, `OverlayLink.java`, `WorldLink.java`). |
| **start script** | `tools/start_mod.ps1` | Starts bridge → Minecraft → (once Minecraft is in its world) Hello Neighbor; copies changed DLL / script into the game; retries a Hello Neighbor start that hangs on a white window. |

## Channels

**Main link** — mapping `Local\HelloNeighborMC_v0` (`protocol/hn_protocol.h`): a header with heartbeats, a
`HostState` slot (Hello Neighbor → Minecraft) and a `McState` slot (Minecraft → Hello Neighbor), both seqlocked, and two
single-producer rings of 32-byte messages: commands (`kCmd*`, host → Minecraft) and events (`kEv*`, Minecraft → host).

HnLink speaks to the bridge in text lines, once per tick:

```
Lua → bridge   C <type> <a> <b> <c> <d>                          a command (any number of them)
               S <flags> <teleportSeq> <world> <x> <y> <z> <yaw> <pitch> <onGround> <secs>
bridge → Lua   E <type> <a> <b> <c> <d>                          the events since the last S
               M <flags> <x> <y> <z> <yaw> <pitch> <tpAck> <slot> <tick> <eye> <cam...>
```

The bridge does not interpret types, so a new command or event only needs a constant on both sides.

**Overlay** — mapping `Local\HelloNeighborMC_overlay_v0` (`protocol/hn_overlay.h`): Minecraft's rendered frame (hand,
HUD, screens on a transparent background) in a triple buffer, written by `FrameExporter`, read by HnGfx every frame.
The same mapping carries the input ring (HnGfx → Minecraft: keys, mouse buttons, wheel, cursor), the flags that say who
gets input (a Minecraft screen is open, Minecraft moves the player, Hello Neighbor's menu is open), and `hostTaps`
(Minecraft asks Hello Neighbor to press its own pick-up / use / throw / slot keys).

**World** — mapping `Local\HelloNeighborMC_world_v2` (`protocol/hn_world.h`): Minecraft's blocks near the player as a
textured triangle mesh plus the block atlas, and entities (the player model in third person, mobs, items, chests,
particles) with their textures. HnGfx draws them in Hello Neighbor's scene using the game's own depth buffer.

**Small files in `<Win64>`**, written by HnLink and read by HnGfx: `hn_camera.txt` (where the camera is in game memory,
plus the height offset), `hn_keys.txt` (Hello Neighbor's key bindings), `hn_world_status.txt`. And by HnLink for
itself: `hn_respawn.txt` (learnt respawn points), `hnlink_trace_broken.txt` (steps that crashed the game once and are now
skipped; see DEVELOPING.md).

## Coordinates

One Minecraft block is **84 Unreal units** (Hello Neighbor's player is 151.2 uu tall, Minecraft's 1.8 blocks).

```
mc.x = ue.X / 84          mc.z = ue.Y / 84          mc.y = (ue.Z + yOff) / 84
mc.yaw = ue.yaw - 90
```

`yOff` is chosen per level so the floor you start on is a block boundary, plus a fixed lift of 100 blocks so every
level, including the deep secret areas, is inside Minecraft's height range (-64..320). HnGfx reads `yOff` from
`hn_camera.txt`, so all three parts agree. Details and tests: `protocol/hn_coords.h`, `protocol/tests/layout_test.cpp`.

## Hello Neighbor's geometry in Minecraft

Minecraft runs in an empty void world. Hello Neighbor's walls and floors only exist there where HnLink has scanned them.

- **Scan**: for each Minecraft cell, yes/no overlap queries (`KismetSystemLibrary.BoxOverlapComponents`) at
  quarter-block resolution give a 64-bit mask (bit `x + 4y + 16z`). Traces that return an `FHitResult` crash the game
  from UE4SS Lua, so only yes/no queries are used.
- **What counts as solid**: any component that *blocks* some collision channel — static and movable meshes, props,
  glass, the game's invisible walls. Trigger zones only overlap and are left out (they made doorways solid). The
  player's own arms and held item are left out, and so is anything inside the player's body above the knees.
- **Sent** as `kCmdProxyCell`; Minecraft puts an invisible `hnmc:proxy` block there whose collision shape is the mask
  (`HnProxy`). It counts as solid for fluids and path finding, and is never replaced by a player's block.
- **Order of work** (`voxPass`), within a time cap of 5 ms per frame:
  1. *urgent*: cells a projectile is about to fly through, cells under flowing water / lava, and every cell a moving
     door covers (door watch, below);
  2. *ahead*: the predicted path of a fast player (elytra), 1.5 s ahead;
  3. *asked*: cells mobs are walking into;
  4. *area*: everything within 3 cells of the player.
- **Rescans**: empty cells every 2 s (parts of a level stream in late), solid ones every 15 s, cells near doors /
  furniture / props every 4 s and every 0.5 s right around the player. **Door watch**: once a second the doors within
  8 blocks are listed; every frame their bounds are read, and while one moves its cells are rescanned at once.
- **Memory**: what was scanned is kept as plain numbers per cell (no table per cell — that made Lua's garbage
  collector freeze the game), and scan times are forgotten for cells not visited for 30-60 s.

## Who moves the player

- **Minecraft drives** once the area around the player is scanned (`kHostMcDrives` → `kMcDriving`). Its physics move
  the player; every frame HnLink teleports Hello Neighbor's pawn so its camera sits on Minecraft's eye. The pawn's own
  movement is off and its capsule passes through geometry (it still overlaps, so ladders, doors, catching and triggers
  work). Its body is kept hidden.
- **Hello Neighbor moves the player** (`teleportSeq`): caught, a cutscene, a checkpoint, a respawn. HnLink notices the
  pawn far from where it put it and Minecraft follows. After a move of more than 10 blocks, Hello Neighbor holds the
  player until the new area is scanned, so Minecraft never starts in an unscanned void.
- **Hand-offs**: in the player states Hello Neighbor must handle itself (`EHumanState`: ladder, cupboard, under a bed,
  window, peeping, bear trap, banana slide, stunned, evade, hiding object, vehicle), Minecraft stops driving until the
  state ends.
- **F4** toggles all of this: off, Hello Neighbor moves the player and Minecraft only follows.
- **Deaths** are cancelled (`HnDeath`): no new player entity, inventory kept; Hello Neighbor puts the player at its
  respawn point for the level, which HnLink learns from where the game puts you after being caught.

## Rendering and input

- Minecraft's window is hidden and sized to Hello Neighbor's back buffer. The level is not rendered (Hello Neighbor
  shows the world); the frame holds the hand, HUD and screens.
- HnGfx draws, every Hello Neighbor frame, first Minecraft's blocks and entities into the scene, then the overlay frame
  on top. The camera comes straight from Hello Neighbor's `PlayerCameraManager` in memory. In third person (F5) Hello
  Neighbor's view moves to a camera actor placed where Minecraft's camera is.
- HnGfx subclasses the game window. While Minecraft drives, every key except Esc, the console key and F4/F6-F12 goes
  to Minecraft; Space goes to both games (for Hello Neighbor's "press Space" moments), and so does F (used by Hello
  Neighbor mods such as the Animatronics mods). In Hello Neighbor's menus and
  cutscenes everything goes to Hello Neighbor.

## Hello Neighbor ↔ Minecraft gameplay

- **Its inventory as Minecraft items** (`kCmdHnItem`, `HnItems`): Hello Neighbor's picked-up objects appear in the
  hotbar; selecting one selects it in Hello Neighbor (`kEvHnSelect`); the object is posed where Minecraft draws the
  held item (`kEvHeldFirst` / `kEvHeldThird` / `kEvHeldRot`).
- **Its actions**: R / G / X and right-click become Hello Neighbor's own key presses (`hostTaps`), so doors, switches,
  picking up and throwing run through the game's own code.
- **Impacts** (`kEvSurfaceHit`, `kEvExplosion`, `kEvNeighborHit`): HnLink applies damage and impulses, knocks the
  neighbour down, and throws an invisible Hello Neighbor item at the point, because its glass only breaks — and its
  objects only move — when something thrown hits them. Away from windows that item has its hit events off (silent).
- **Mobs**: a villager follows the neighbour in Minecraft (`HnMobs`) and is never drawn (not made invisible: mobs
  barely notice invisible targets). Hostile mobs, golems and tamed wolves target it, hits on it are hits on the
  neighbour, and hostile mobs leave the player alone.
