# hnmc — the Minecraft side

Fabric mod for Minecraft 26.3 (Java 25) of the Minecraft-in-Hello-Neighbor project. It runs hidden while Hello
Neighbor is played. See [docs/DEVELOPING.md](../docs/DEVELOPING.md) to build and run it from source, and
[docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) for how it fits together.

- `src/main/java/dev/hnmc` — the integrated server and shared code: Hello Neighbor geometry as proxy blocks
  (`HnProxy`), block events (`BlockWatch`), the kit (`HnKit`), mobs vs the neighbour (`HnMobs`), impacts (`HnImpacts`),
  death handling (`HnDeath`), fluids (`FluidGuard`), `/hnreset` (`HnReset`), the shared memory link (`link/`).
- `src/client/java/dev/hnmc/client` — the client: following / driving the player (`HnWorld`), the overlay frame
  (`HnOverlay`, `FrameExporter`), input from Hello Neighbor (`InputBridge`), blocks and entities for Hello Neighbor's
  scene (`HnWorldMesh`, `HnEntities`), Hello Neighbor's inventory and actions (`HnInteract`, `HnHeld`).

Build: `gradlew compileJava compileClientJava`. Tests: `gradlew test` after `..\protocol\build_java_deps.bat`.
