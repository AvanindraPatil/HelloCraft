# Minecraft in Hello Neighbor

Everything we love about Minecraft right inside Hello Neighbor.
<img width="2560" height="1440" alt="Screenshot (2855)" src="https://github.com/user-attachments/assets/e97a3901-99c0-4628-9799-35ae1db6eb09" />

A real Minecraft client runs hidden in the background. Hello Neighbor shows what it renders and tells it where the
walls are; Minecraft tells Hello Neighbor where the player is and what they do. The idea comes from
[SkyCraft](https://github.com/chasmlol/SkyCraft) (Minecraft inside Skyrim).

> Unofficial fan project. Not affiliated with tinyBuild, Dynamic Pixels, Mojang or Microsoft. You need your own copy of
> Hello Neighbor and a Microsoft account with Minecraft; no game files are included here.

## What works

- **Minecraft movement** in every act: walking, sprinting, sneaking, elytra flight, ender pearls.
  Hello Neighbor's walls, floors, furniture, props and glass are solid for Minecraft.
- **Minecraft rendering**: your hand and held item, the hotbar, hearts and hunger, third person (F5) with the Minecraft
  player model, blocks you place, mobs, dropped items, particles — drawn in Hello Neighbor's 3D scene.
- **Building and breaking**: place and mine blocks on Hello Neighbor's surfaces; water and lava flow on its floors.
- **Hello Neighbor's own game**: doors, switches, picking up and throwing its objects, its inventory (shown as Minecraft
  items), ladders, hiding in cupboards and under beds, being caught, the Act 3 "press Space" escapes.
- **Fighting**: hit the neighbour with minecraft items and arrows that will knock him down, TNT also kind of knocks him down and can make him fly a bit, send Minecraft mobs on him. Hits
  break windows and knock objects around.
- **Deaths**: a Minecraft death keeps your inventory and puts you at Hello Neighbor's own respawn point for the level.

## Requirements

- Windows 10 or 11
- **Hello Neighbor** on Steam
- **Minecraft: Java Edition** (Microsoft account with Minecraft)
- [**Prism Launcher**](https://prismlauncher.org/) (free; it sets up Java and Fabric for you)

## Install

1. Install Prism Launcher, open it, sign in with your Microsoft account, then close it.
2. Download `HelloCraft-<version>.zip` from the [Releases](../../releases) page and unzip it anywhere.
3. Run **`Install.bat`**. It finds Hello Neighbor (through Steam) and Prism by itself, installs the mod into Hello
   Neighbor and adds a Prism instance for the Minecraft part. Close Hello Neighbor first if it is running.

Windows may warn that the scripts come from the internet: choose *More info → Run anyway*. The first start downloads
Minecraft and Java through Prism, so it takes a few minutes.

**Safety.** The mod has no network code and never touches your Minecraft login (Prism handles that); its Minecraft
world is private and single-player. It changes only Hello Neighbor's folder (UE4SS, the two mods, a few small files)
and adds one Prism instance; replaced files are kept as `*.before-hnmc`, and `Uninstall.bat` removes it all. Antivirus
programs may warn about it, because it hooks the game's graphics and UE4SS loads as `dwmapi.dll`: the source is here,
and each release lists the zip's SHA-256 checksum so you can verify your download.

## Play

Run **`Play.bat`** and wait. Minecraft starts hidden in the background, then Hello Neighbor opens. Load a level and
play. Keep the Play.bat window open; when you close Hello Neighbor, Minecraft closes too. `Uninstall.bat` removes the
mod again.

Your name in chat and death messages is your Minecraft account name; to use another, set `playerName` in the
instance's `config\hnmc.properties` (see the `README.txt` in the zip).

### Controls

| Key | |
|---|---|
| Mouse, W A S D, Space, Shift, Ctrl, 1-9, E, Q, F5, T, ... | Minecraft, as usual |
| **R** (hold) | Hello Neighbor: pick up / interact |
| **G** | Hello Neighbor: use the held item |
| **X** | Hello Neighbor: throw the held item |
| **Right-click** | Opens doors, switches, turn on TV... (when the crosshair is on one) |
| **Space** | Jump; also push ability and skipping cutscenes |
| **F** | Sent to both Minecraft and Hello Neighbor |
| **Esc** | Hello Neighbor's menu |
| **F4** | Minecraft movement on / off (off = Hello Neighbor moves the player) |
| **F6** | Show Minecraft's blocks in Hello Neighbor again (resync) |
| **F9** | Log what blocks the cells in front of you (for bug reports) |
| **`/hnreset`** | Remove your blocks, mobs and dropped items, and get the starting kit back |

R / G / X can be changed in Minecraft's controls (category "Hello Neighbor"). They press whatever keys Hello Neighbor's
own settings use for pick up, use and throw, which the mod reads from the game, so the two do not have to match.

## Known limitations

- Held Hello Neighbor items still lag slightly behind the hand and vary in size.
- The world is only known around you: geometry is scanned as you go (a second after a big teleport Hello Neighbor
  holds the player while the new area is scanned).
- You might just go in the void, I recommend pressing f4 and restart from menu. Then press f4 again once you are on the floor to get the minecraft character back.
  
## For developers

To build the mod from source, change it or find out how it works, see [docs/DEVELOPING.md](docs/DEVELOPING.md) and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Credits and licences

- [SkyCraft](https://github.com/chasmlol/SkyCraft) (MIT): the overall approach; `FrameExporter`, `InputBridge` and the
  entity export are adapted from it (marked in the files).
- [MinHook](https://github.com/TsudaKageyu/minhook) (BSD-2-Clause), in `native/third_party/minhook`.
- [UE4SS](https://github.com/UE4SS-RE/RE-UE4SS), [Fabric](https://fabricmc.net/), [fengari](https://fengari.io/) (tests).

This project's own code is under the [MIT licence](LICENSE).
