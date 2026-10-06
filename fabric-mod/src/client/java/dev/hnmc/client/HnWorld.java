package dev.hnmc.client;

import dev.hnmc.HnMc;
import dev.hnmc.link.Layout;
import dev.hnmc.link.McLink;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

/**
 * The hidden Minecraft world: an empty (void) Superflat world that is created and entered automatically,
 * plus the per-tick step that moves the real Minecraft player to where Hello Neighbor says the player is.
 */
final class HnWorld {
	private static final int TITLE_TICKS_BEFORE_CREATE = 40; // let the title screen settle first
	private static final String LEVEL_PREFIX = "HN_Void_";
	// Where the player waits while Hello Neighbor is not in a level.
	private static final double HOLD_X = 8.5, HOLD_Y = 64.0, HOLD_Z = 8.5;
	private static final double ARRIVED_BLOCKS = 1.5; // within this of the target = teleport acknowledged
	private static final double JUMP_BLOCKS = 8.0;   // further than this from the target: server teleport instead of a client move

	private final McLink link;
	private int titleTicks;
	private boolean creating;
	private int ackedTeleport;
	private ServerPlayer equipped; // server thread only
	private LocalPlayer lastPlayer;
	private int lastTeleportSeq = -1;
	private boolean lastDriven;

	private static HnWorld instance;

	HnWorld(McLink link) {
		this.link = link;
		instance = this;
		dev.hnmc.HnReset.kit = this::equip;   // /hnreset gives the starting kit again
	}

	/** Called every client tick (after {@code link.poll()}); decides what to publish. */
	void tick(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null || mc.level == null) {
			link.setNotInWorld();
			maybeCreateWorld(mc);
			return;
		}
		creating = false;

		if (mc.player.isDeadOrDying()) mc.player.respawn(); // the void must never leave us on a death screen

		final boolean driven = link.hostInGame();
		final double x = driven ? link.targetX() : HOLD_X, y = driven ? link.targetY() : HOLD_Y, z = driven ? link.targetZ() : HOLD_Z;
		final float yaw = driven ? link.targetYaw() : 0f, pitch = driven ? link.targetPitch() : 0f;

		if (link.hostWantsMcDrive()) {
			tickMcDrives(mc, server, x, y, z, yaw, pitch);
			return;
		}
		if (mcDriving) stopMcDriving(mc, server);
		dev.hnmc.HostDrive.active = driven;

		// Follow the host. A SERVER teleport every tick would leave the server permanently waiting for the client
		// to confirm the teleport, and while it waits it ignores block placement (handleUseItemOn checks
		// awaitingPositionFromClient == null). So: server teleport only for real jumps (new player, map change,
		// big leap); otherwise move the CLIENT player like normal movement and let it report to the server.
		LocalPlayer p = mc.player;
		double dx0 = p.getX() - x, dy0 = p.getY() - y, dz0 = p.getZ() - z;
		boolean jump = p != lastPlayer || link.targetTeleportSeq() != lastTeleportSeq || driven != lastDriven
			|| dx0 * dx0 + dy0 * dy0 + dz0 * dz0 > JUMP_BLOCKS * JUMP_BLOCKS;
		if (jump) {
			lastPlayer = p;
			lastTeleportSeq = link.targetTeleportSeq();
			lastDriven = driven;
			server.execute(() -> moveServerPlayer(server, x, y, z, yaw, pitch));
		} else {
			p.setNoGravity(true);
			p.setDeltaMovement(Vec3.ZERO);
			p.setPos(x, y, z);
			p.setYRot(yaw);
			p.setXRot(pitch);
			p.setYHeadRot(yaw);
		}

		if (driven) {
			// Publish what the real client player is actually doing (it follows the server within a tick or two).
			double dx = mc.player.getX() - x, dy = mc.player.getY() - y, dz = mc.player.getZ() - z;
			if (dx * dx + dy * dy + dz * dz < ARRIVED_BLOCKS * ARRIVED_BLOCKS) ackedTeleport = link.targetTeleportSeq(); // ack only once we really arrived
			link.setPublished(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot(),
				mc.player.onGround(), ackedTeleport);
		} else {
			link.setNotInWorld();
		}
	}

	// ---- step 3b: Minecraft's physics moves the player; Hello Neighbor follows what we publish ----

	/** Read by HnOverlay (render thread = this thread). */
	static boolean mcDriving() { return mcDriving; }
	private static boolean mcDriving;
	private int driveTeleportSeq = -1;
	private int seenDeaths = dev.hnmc.HnDeath.COUNT.get();
	private long deathWaitUntil;             // after a death: when to stop waiting for Hello Neighbor's own respawn
	private static final long DEATH_WAIT_MS = 8000;
	private double safeX, safeY, safeZ;      // last on-ground position: where a respawn puts us back
	private boolean haveSafe;
	private int groundTicks;
	private static final double FALL_RESCUE_BLOCKS = 30.0;
	private static final double FAR_TELEPORT_BLOCKS = 10.0;

	/** The view goes back to first person (F5 is the way to third person again). */
	private static void firstPerson(Minecraft mc, String why) {
		if (mc.options.getCameraType() == net.minecraft.client.CameraType.FIRST_PERSON) return;
		mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
		HnMc.LOGGER.info("{}: back to first person", why);
	}

	private void tickMcDrives(Minecraft mc, IntegratedServer server, double x, double y, double z, float yaw, float pitch) {
		LocalPlayer p = mc.player;
		dev.hnmc.HostDrive.active = false;     // the server checks our moves normally again
		// The anchor ("safe" spot) always exists while driving: where driving started, every spot stood on for half a
		// second, and wherever Hello Neighbor itself puts the player. Falls and deaths go back to it, so the player
		// can never end up in the void (and Hello Neighbor's body, which follows, never below the map).
		if (!mcDriving) {
			mcDriving = true;
			seenDeaths = dev.hnmc.HnDeath.COUNT.get();
			firstPerson(mc, "Minecraft starts moving the player");
			driveTeleportSeq = link.targetTeleportSeq();
			lastPlayer = p;
			setAnchor(x, y, z);
			server.execute(() -> startDriveOnServer(server, x, y, z, yaw, pitch));
			p.setNoGravity(false);
			p.snapTo(x, y, z, yaw, pitch);
			p.setDeltaMovement(Vec3.ZERO);
			HnMc.LOGGER.info("Minecraft now moves the player (start at {}, {}, {})", String.format("%.2f", x), String.format("%.2f", y), String.format("%.2f", z));
		} else if (link.targetTeleportSeq() != driveTeleportSeq) {
			// Hello Neighbor moved the player itself (caught by the neighbour, level restart, cutscene): follow it.
			driveTeleportSeq = link.targetTeleportSeq();
			deathWaitUntil = 0;                // Hello Neighbor moved us (its own respawn after a death, or anything else)
			// Sent far away (caught and back at a checkpoint, a level restart, a cutscene): first person again. The
			// third-person camera was left behind and showed only the face; a small hop (a ladder ended) keeps the view.
			if (p.distanceToSqr(x, y, z) > FAR_TELEPORT_BLOCKS * FAR_TELEPORT_BLOCKS) firstPerson(mc, "Hello Neighbor teleported the player far away");
			setAnchor(x, y, z);
			server.execute(() -> teleportOnServer(server, x, y, z, yaw, pitch));
			p.snapTo(x, y, z, yaw, pitch);
			p.setDeltaMovement(Vec3.ZERO);
			HnMc.LOGGER.info("Hello Neighbor teleported the player to ({}, {}, {})", String.format("%.2f", x), String.format("%.2f", y), String.format("%.2f", z));
		} else if (dev.hnmc.HnDeath.COUNT.get() != seenDeaths) {
			// Died (HnDeath cancelled it: same player, same inventory): Hello Neighbor catches the player and
			// respawns it at its own respawn point (the teleport branch above follows it). If it does not move us
			// within DEATH_WAIT_MS, back to the anchor instead.
			seenDeaths = dev.hnmc.HnDeath.COUNT.get();
			firstPerson(mc, "The player died");
			link.pushEvent(Layout.EV_PLAYER_DIED, 0, 0, 0, 0);
			deathWaitUntil = System.currentTimeMillis() + DEATH_WAIT_MS;
			HnMc.LOGGER.info("The player died: asking Hello Neighbor to respawn it");
		} else if (deathWaitUntil != 0 && System.currentTimeMillis() > deathWaitUntil) {
			deathWaitUntil = 0;
			backToAnchor(server, p, yaw, pitch, "Hello Neighbor did not respawn the player");
		} else if (p != lastPlayer) {
			// Respawned (died): back to the anchor, never the void world's spawn.
			lastPlayer = p;
			firstPerson(mc, "The player respawned");
			backToAnchor(server, p, yaw, pitch, "Respawned");
		}
		p.setNoGravity(false);
		// The mouse still turns Hello Neighbor's camera: Minecraft looks where it looks.
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.setYHeadRot(yaw);
		unstick(server, p, yaw, pitch);
		groundTicks = p.onGround() ? groundTicks + 1 : 0;
		if (groundTicks >= 10) setAnchor(p.getX(), p.getY(), p.getZ());
		// Fell far below the anchor (a hole in the scanned geometry, or off the map): put back, don't die.
		if (p.getY() < safeY - FALL_RESCUE_BLOCKS) {
			HnMc.LOGGER.warn("Fell {} blocks below the anchor", String.format("%.0f", safeY - p.getY()));
			backToAnchor(server, p, yaw, pitch, "Rescued");
		}

		double dx = p.getX() - x, dy = p.getY() - y, dz = p.getZ() - z;
		if (dx * dx + dy * dy + dz * dz < ARRIVED_BLOCKS * ARRIVED_BLOCKS) ackedTeleport = link.targetTeleportSeq();
		// No position publish here: the end-of-tick position is one tick AHEAD of what the frames interpolate,
		// and publishing it made Hello Neighbor's camera jump forward and back 20 times a second.
		// HnOverlay.beginFrame publishes the interpolated position every frame (ack included).
	}

	/**
	 * Minecraft never pushes a player UP out of a block it overlaps, it just falls through. Hello Neighbor can hand
	 * us a position inside its floor (its body was left below the floor, a teleport, a rounded height), so: if the
	 * player's box overlaps solid geometry, lift it to the first free height (1/16 block steps, up to 3 blocks).
	 */
	private void unstick(IntegratedServer server, LocalPlayer p, float yaw, float pitch) {
		var level = Minecraft.getInstance().level;
		if (level == null) return;
		net.minecraft.world.phys.AABB box = p.getBoundingBox().deflate(1e-3);
		if (level.noCollision(p, box)) return;
		for (int i = 1; i <= 48; i++) {
			double dy = i / 16.0;
			if (level.noCollision(p, box.move(0, dy, 0))) {
				double nx = p.getX(), ny = p.getY() + dy, nz = p.getZ();
				server.execute(() -> teleportOnServer(server, nx, ny, nz, yaw, pitch));
				p.snapTo(nx, ny, nz, yaw, pitch);
				p.setDeltaMovement(Vec3.ZERO);
				if (++unsticks <= 5 || unsticks % 100 == 0) {
					HnMc.LOGGER.info("Player was inside Hello Neighbor geometry: lifted {} blocks to y={} ({} times)", String.format("%.2f", dy),
						String.format("%.2f", ny), unsticks);
				}
				return;
			}
		}
	}

	private int unsticks;
	private long lastCamLog;

	private void setAnchor(double x, double y, double z) {
		safeX = x;
		safeY = y;
		safeZ = z;
		haveSafe = true;
	}

	private void backToAnchor(IntegratedServer server, LocalPlayer p, float yaw, float pitch, String why) {
		double rx = safeX, ry = safeY, rz = safeZ;
		server.execute(() -> teleportOnServer(server, rx, ry, rz, yaw, pitch));
		p.snapTo(rx, ry, rz, yaw, pitch);
		p.setDeltaMovement(Vec3.ZERO);
		p.resetFallDistance();
		groundTicks = 0;
		HnMc.LOGGER.info("{}: back to ({}, {}, {})", why, String.format("%.2f", rx), String.format("%.2f", ry), String.format("%.2f", rz));
	}

	/** Render thread, every frame while driving: publish the interpolated position so Hello Neighbor's camera moves
	 * smoothly at the frame rate, not in 20 Hz steps. Same thread as the client tick, so McLink stays single-threaded. */
	static void publishFrame(Minecraft mc) {
		HnWorld w = instance;
		if (w == null || !mcDriving || mc.player == null) return;
		w.publishDriven(mc.player, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		w.link.publish();
	}

	/** Publish the player for Hello Neighbor to follow. Also called every rendered frame (interpolated). */
	void publishDriven(LocalPlayer p, float partialTick) {
		Vec3 pos = p.getPosition(partialTick);
		link.setPublished(pos.x, pos.y, pos.z, p.getYRot(), p.getXRot(), p.onGround(), ackedTeleport);
		link.setEyeHeight(p.getEyeHeight());
		link.addFlags(Layout.MC_DRIVING);
		// Minecraft's camera (F5): Hello Neighbor puts its view there in third person.
		net.minecraft.client.Camera cam = Minecraft.getInstance().gameRenderer.mainCamera();
		if (cam != null && cam.isInitialized()) {
			Vec3 c = cam.position();
			link.setCamera((float) (c.x - pos.x), (float) (c.y - pos.y), (float) (c.z - pos.z), cam.yRot(), cam.xRot(), cam.getFov());
			if (cam.isDetached()) {
				link.addFlags(Layout.MC_CAM_DETACHED);
				// Diagnostic: Minecraft pulls the camera in when its rays hit something near the head.
				long now = System.currentTimeMillis();
				if (HnMc.VERBOSE && now - lastCamLog > 5000) {
					lastCamLog = now;
					Vec3 eye = p.getEyePosition(partialTick);
					var level = Minecraft.getInstance().level;
					int proxies = 0;
					if (level != null) {
						for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
							if (dev.hnmc.HnProxy.isProxy(level.getBlockState(net.minecraft.core.BlockPos.containing(eye.x + dx * 0.5, eye.y + dy * 0.5, eye.z + dz * 0.5)))) proxies++;
						}
					}
					HnMc.LOGGER.info("Third person: camera {} blocks from the eye (Minecraft wants 4); proxy blocks around the head: {}/27",
						String.format("%.2f", c.distanceTo(eye)), proxies);
				}
			}
		}
	}

	private void stopMcDriving(Minecraft mc, IntegratedServer server) {
		mcDriving = false;
		lastPlayer = null;          // forces a server teleport to the host position on the next follow tick
		HnMc.LOGGER.info("Hello Neighbor moves the player again");
	}

	private void startDriveOnServer(IntegratedServer server, double x, double y, double z, float yaw, float pitch) {
		ServerPlayer sp = serverPlayer(server);
		if (sp == null) return;
		sp.setNoGravity(false);
		sp.teleportTo(sp.level(), x, y, z, Set.of(), yaw, pitch, false);
		sp.setDeltaMovement(0, 0, 0);
		sp.resetFallDistance();
		if (equipped != sp) equip(sp);
	}

	private void teleportOnServer(IntegratedServer server, double x, double y, double z, float yaw, float pitch) {
		ServerPlayer sp = serverPlayer(server);
		if (sp == null) return;
		sp.teleportTo(sp.level(), x, y, z, Set.of(), yaw, pitch, false);
		sp.setDeltaMovement(0, 0, 0);
		sp.resetFallDistance();
	}

	private static ServerPlayer serverPlayer(IntegratedServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		return players.isEmpty() ? null : players.getFirst();
	}

	private void moveServerPlayer(IntegratedServer server, double x, double y, double z, float yaw, float pitch) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) return;
		ServerPlayer sp = players.getFirst();
		// Phase 2, stage A: the host is authoritative. No gravity or momentum, so the void cannot kill the player.
		sp.setNoGravity(true);
		sp.teleportTo(sp.level(), x, y, z, Set.of(), yaw, pitch, false);
		sp.setDeltaMovement(0, 0, 0);
		if (equipped != sp) equip(sp);
	}

	/**
	 * Survival (so the HUD shows hearts and hunger), full diamond gear and a kit of everything useful, once per
	 * player entity. Ender pearls, arrows, TNT and rockets never run out (HnKit). Server thread.
	 */
	private void equip(ServerPlayer sp) {
		equipped = sp;
		sp.setGameMode(GameType.SURVIVAL);
		Item[] kit = {
			// Hotbar
			Items.DIAMOND_PICKAXE, Items.DIAMOND_SWORD, Items.BOW, Items.ENDER_PEARL, Items.STONE, Items.OAK_PLANKS,
			Items.TORCH, Items.TNT, Items.FLINT_AND_STEEL,
			// Row 1: the other diamond tools, the fishing rod, arrows, buckets
			Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.SHULKER_BOX, Items.SHIELD, Items.FISHING_ROD, Items.ARROW,
			Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.BUCKET,
			// Row 2: flying and riding
			Items.ELYTRA, Items.FIREWORK_ROCKET, Items.MINECART, Items.RAIL, Items.POWERED_RAIL, Items.REDSTONE_BLOCK,
			Items.REDSTONE_TORCH, Items.LEVER, Items.OAK_BOAT,
			// Row 3: building and food; 4 slots stay free for what Hello Neighbor's pick-up puts in the inventory (HnItems)
			Items.GLASS, Items.LADDER, Items.CHEST,
			Items.GOLDEN_APPLE, Items.COOKED_BEEF };
		var inv = sp.getInventory();
		for (int i = 0; i < kit.length; i++) inv.setItem(i, new ItemStack(kit[i], Math.min(64, kit[i].getDefaultMaxStackSize())));
		// The bow: Power V, Infinity, Flame, Punch II, Unbreaking III.
		var enchants = sp.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
		for (int i = 0; i < kit.length; i++) {
			if (kit[i] != Items.BOW) continue;
			ItemStack bow = inv.getItem(i);
			bow.enchant(enchants.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.POWER), 5);
			bow.enchant(enchants.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.INFINITY), 1);
			bow.enchant(enchants.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.FLAME), 1);
			bow.enchant(enchants.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.PUNCH), 2);
			bow.enchant(enchants.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING), 3);
		}
		// The chest: useful extras (place it, open it). Counts kept small.
		for (int i = 0; i < kit.length; i++) {
			if (kit[i] != Items.CHEST) continue;
			Object[][] extras = {
				{ Items.CRAFTING_TABLE, 4 }, { Items.FURNACE, 4 }, { Items.OAK_DOOR, 8 }, { Items.OAK_TRAPDOOR, 16 },
				{ Items.OAK_FENCE, 64 }, { Items.OAK_FENCE_GATE, 16 }, { Items.OAK_SLAB, 64 }, { Items.OAK_STAIRS, 64 },
				{ Items.SCAFFOLDING, 64 }, { Items.OBSIDIAN, 32 }, { Items.GLOWSTONE, 32 }, { Items.LANTERN, 16 },
				{ Items.SLIME_BLOCK, 16 }, { Items.COBWEB, 16 }, { Items.PISTON, 16 }, { Items.STICKY_PISTON, 16 },
				{ Items.REDSTONE, 64 }, { Items.REDSTONE_LAMP, 16 }, { Items.STONE_BUTTON, 16 }, { Items.STONE_PRESSURE_PLATE, 16 },
				{ Items.HOPPER, 8 }, { Items.IRON_BLOCK, 16 }, { Items.CARVED_PUMPKIN, 4 }, { Items.SHEARS, 1 },
				{ Items.SPYGLASS, 1 }, { Items.NAME_TAG, 8 }, { Items.TOTEM_OF_UNDYING, 1 } };
			java.util.List<ItemStack> stuff = new java.util.ArrayList<>();
			for (Object[] e : extras) stuff.add(new ItemStack((Item) e[0], (Integer) e[1]));
			ItemStack chest = inv.getItem(i);
			chest.setCount(1);
			chest.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(stuff));
			chest.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Extras"));
		}
		// The shulker box: spawn eggs to set mobs on the neighbour (HnMobs), bones to tame wolves.
		for (int i = 0; i < kit.length; i++) {
			if (kit[i] != Items.SHULKER_BOX) continue;
			Item[] mobs = { Items.ZOMBIE_SPAWN_EGG, Items.HUSK_SPAWN_EGG, Items.SKELETON_SPAWN_EGG, Items.CREEPER_SPAWN_EGG,
				Items.SPIDER_SPAWN_EGG, Items.WITCH_SPAWN_EGG, Items.PILLAGER_SPAWN_EGG, Items.VINDICATOR_SPAWN_EGG,
				Items.SLIME_SPAWN_EGG, Items.WOLF_SPAWN_EGG, Items.BONE, Items.IRON_GOLEM_SPAWN_EGG, Items.SNOW_GOLEM_SPAWN_EGG };
			java.util.List<ItemStack> eggs = new java.util.ArrayList<>();
			for (Item m : mobs) eggs.add(new ItemStack(m, 64));
			ItemStack box = inv.getItem(i);
			box.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(eggs));
			box.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Mobs vs the neighbour"));
		}
		sp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		sp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		sp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
		sp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
		HnMc.LOGGER.info("Equipped the Minecraft player: survival, diamond armour and the full kit");
	}

	private void maybeCreateWorld(Minecraft mc) {
		if (creating || !(mc.gui.screen() instanceof TitleScreen)) {
			if (!(mc.gui.screen() instanceof TitleScreen)) titleTicks = 0;
			return;
		}
		if (++titleTicks < TITLE_TICKS_BEFORE_CREATE) return;
		creating = true;

		String id = LEVEL_PREFIX + System.currentTimeMillis();
		deleteOldWorlds(id);
		HnMc.LOGGER.info("Creating hidden void world '{}'", id);
		LevelSettings settings = new LevelSettings(id, GameType.CREATIVE,
			new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, true), true, WorldDataConfiguration.DEFAULT);
		mc.createWorldOpenFlows().createFreshLevel(id, settings, new WorldOptions(0L, false, false),
			HnWorld::voidDimensions, mc.gui.screen());
	}

	/**
	 * Every start makes a new world and nothing needs the old ones (a void world holds nothing worth keeping), so
	 * delete the earlier ones: they would pile up in the saves folder, tens of MB each. Only folders named exactly
	 * HN_Void_<digits> are touched, and symbolic links are never followed.
	 */
	private static void deleteOldWorlds(String keep) {
		java.nio.file.Path saves = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("saves");
		Thread t = new Thread(() -> {
			try (var dirs = java.nio.file.Files.list(saves)) {
				for (java.nio.file.Path dir : (Iterable<java.nio.file.Path>) dirs::iterator) {
					String name = dir.getFileName().toString();
					if (name.equals(keep) || !name.matches(LEVEL_PREFIX + "\\d+")
						|| java.nio.file.Files.isSymbolicLink(dir) || !java.nio.file.Files.isDirectory(dir)) continue;
					try (var files = java.nio.file.Files.walk(dir)) {
						for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator) {
							java.nio.file.Files.deleteIfExists(p);
						}
					} catch (java.io.IOException | RuntimeException e) {
						HnMc.LOGGER.warn("Could not delete the old world {}: {}", name, e.toString());
					}
				}
			} catch (java.io.IOException | RuntimeException e) {
				HnMc.LOGGER.warn("Could not look for old worlds: {}", e.toString());
			}
		}, "hnmc-old-worlds");
		t.setDaemon(true);
		t.start();
	}

	private static WorldDimensions voidDimensions(HolderLookup.Provider registries) {
		var biomes = registries.lookupOrThrow(Registries.BIOME);
		var structureSets = registries.lookupOrThrow(Registries.STRUCTURE_SET);
		var placedFeatures = registries.lookupOrThrow(Registries.PLACED_FEATURE);
		FlatLevelGeneratorSettings voidSettings = FlatLevelGeneratorSettings.getDefault(biomes, structureSets, placedFeatures)
			.withBiomeAndLayers(List.of(new FlatLayerInfo(1, Blocks.AIR)), Optional.empty(), FlatLevelGeneratorSettings.getDefaultBiome(biomes));
		WorldDimensions flat = registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions();
		return flat.replaceOverworldGenerator(registries, new FlatLevelSource(voidSettings));
	}
}
