package dev.hnmc;

import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft's mobs against the neighbour.
 *
 * His Minecraft stand-in for clicks is an Interaction entity, which mobs cannot target. So he also gets a BODY: a
 * silent, frozen villager kept at his feet, never drawn in Hello Neighbor (HnEntities skips its id) (zombies hate villagers anyway). Every half second every hostile
 * mob, iron / snow golem and tamed wolf near him is set on that body. Damage to it never happens; it becomes a hit on
 * the real neighbour (kEvNeighborHit: knocked down). Hostile mobs never hurt the player: they are against him.
 *
 * Hello Neighbor's floors only exist in Minecraft where they were scanned (around the player), so cells around every
 * mob and around him are asked for (kEvScanCell) as they move: mobs do not fall into the void.
 *
 * Also: difficulty Normal (Peaceful removes hostile mobs) and no natural spawning (mobs come from spawn eggs).
 * Server thread.
 */
public final class HnMobs {
	private static final String BODY_TAG = "hnmc_neighbor_body";
	private static final double TARGET_RANGE = 48.0;
	private static Villager body;
	/** The body's entity id (client and integrated server share ids): HnEntities never draws it. */
	public static volatile int bodyId = -1;
	private static boolean worldSet;
	private static int ticks;
	private static final Map<Integer, Long> lastScanCell = new HashMap<>();

	private HnMobs() {}

	public static boolean isBody(Entity e) { return e != null && e.entityTags().contains(BODY_TAG); }

	private static boolean fighter(Mob m) {
		return m instanceof Enemy || m instanceof AbstractGolem || (m instanceof TamableAnimal t && t.isTame());
	}

	/** Mob.setTarget (MobTargetMixin): a fighter that picks the player gets the neighbour's body instead. */
	public static LivingEntity redirectTarget(Mob mob, LivingEntity target) {
		if (!(target instanceof net.minecraft.world.entity.player.Player) || !fighter(mob) || mob.level().isClientSide()) return target;
		Villager b = body;
		if (b != null && !b.isRemoved() && b.level() == mob.level()) return b;
		return mob instanceof Enemy ? null : target;   // no neighbour here: hostile mobs leave the player alone
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(HnMobs::tick);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (isBody(entity)) {
				// A hit on the neighbour's body = a hit on the neighbour (from where the attacker is).
				Entity from = source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
				Vec3 d = from != null ? entity.position().subtract(from.position()) : new Vec3(0, 0, 1);
				double len = Math.max(1e-3, Math.sqrt(d.x * d.x + d.z * d.z));
				HnImpacts.neighbour(Math.min(20f, amount), d.x / len, d.z / len);
				HnMc.LOGGER.info("Mob hit on the neighbour: {} for {}", from == null ? source.getMsgId() : from.getType().toShortString(), String.format("%.1f", amount));
				return false;
			}
			// Hostile mobs fight the neighbour, not the player.
			if (entity instanceof ServerPlayer && source.getEntity() instanceof Enemy) return false;
			return true;
		});
		// The body is never a trading villager.
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> isBody(entity) ? InteractionResult.FAIL : InteractionResult.PASS);
	}

	/** Server thread (HnNeighbor): where the neighbour is, or that he is gone. */
	public static void updateBody(ServerLevel level, boolean present, double x, double y, double z, float yaw) {
		if (!present) {
			if (body != null) { body.discard(); body = null; bodyId = -1; }
			return;
		}
		if (body == null || body.isRemoved() || body.level() != level) {
			for (Entity old : level.getAllEntities()) if (isBody(old)) old.discard();   // saved with the world earlier
			Villager v = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (v == null) return;
			v.addTag(BODY_TAG);
			v.setNoAi(true);
			v.setNoGravity(true);
			// NOT invisible: Minecraft mobs only notice an invisible target from ~2 blocks. HnEntities skips it by id.
			v.setSilent(true);
			v.snapTo(x, y, z, yaw, 0f);
			if (!level.addFreshEntity(v)) return;
			body = v;
			bodyId = v.getId();
			HnMc.LOGGER.info("Neighbour body for mobs spawned (entity {})", bodyId);
			return;
		}
		if (body.position().distanceToSqr(x, y, z) > 1e-4) body.snapTo(x, y, z, yaw, 0f);
		body.setDeltaMovement(Vec3.ZERO);
	}

	private static void tick(MinecraftServer server) {
		if (!worldSet) {
			worldSet = true;
			server.setDifficulty(Difficulty.NORMAL, true);
			server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
			// Always noon in the hidden Minecraft world: Minecraft lights the hand, blocks and mobs it draws into Hello
			// Neighbor by its own time of day (night made them dark). Undead do not burn in it (MobSunMixin).
			server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
			try {
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
			} catch (RuntimeException e) {
				HnMc.LOGGER.warn("Could not set the time to night", e);
			}
			HnMc.LOGGER.info("Mobs: difficulty Normal, natural spawning off (use spawn eggs), always noon, undead do not burn");
		}
		if (++ticks % 10 != 0) return;
		ServerLevel level = server.overworld();
		Villager b = body != null && !body.isRemoved() ? body : null;
		ServerPlayer player = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().getFirst();
		Vec3 centre = b != null ? b.position() : player != null ? player.position() : null;
		if (centre == null) return;
		int set = 0;
		for (Mob m : level.getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(centre, centre).inflate(TARGET_RANGE))) {
			if (isBody(m)) continue;
			scanAround(m);
			if (!fighter(m)) continue;
			LivingEntity target = m.getTarget();
			if (b != null) {
				if (target != b) { m.setTarget(b); set++; }
				if (ticks % 40 == 0) scanCorridor(m, b);   // a floor to walk on all the way to him
			} else if (target instanceof ServerPlayer && m instanceof Enemy) {
				m.setTarget(null);   // no neighbour in this level: leave the player alone
			}
		}
		if (b != null) scanAround(b);
		if (b != null && ticks % 100 == 0) diagnose(level, b);
		if (set > 0 && ticks % 100 == 0) HnMc.LOGGER.info("Mobs: {} set on the neighbour", set);
		lastScanCell.keySet().removeIf(id -> level.getEntity(id) == null);
	}

	/** Every 5 s: what the nearest fighter is doing (target, distance, path), to see why one does not attack. */
	private static void diagnose(ServerLevel level, Villager b) {
		Mob best = null;
		double bestD = Double.MAX_VALUE;
		for (Mob m : level.getEntitiesOfClass(Mob.class, b.getBoundingBox().inflate(TARGET_RANGE))) {
			if (isBody(m) || !fighter(m)) continue;
			double d = m.distanceTo(b);
			if (d < bestD) { bestD = d; best = m; }
		}
		if (best == null) return;
		var nav = best.getNavigation();
		var path = nav.getPath();
		LivingEntity t = best.getTarget();
		HnMc.LOGGER.info("Mobs: nearest fighter {} at {} blocks; target {}; path {}; on ground {}; at ({}, {}, {})",
			best.getType().toShortString(), String.format("%.1f", bestD), t == null ? "none" : t == b ? "the neighbour" : t.getType().toShortString(),
			path == null ? "none" : (path.canReach() ? "reaches him" : "partial") + " " + path.getNodeCount() + " nodes" + (nav.isDone() ? " (done)" : ""),
			best.onGround(), String.format("%.1f", best.getX()), String.format("%.1f", best.getY()), String.format("%.1f", best.getZ()));
	}

	/**
	 * Hello Neighbor's floor exists in Minecraft only where it was scanned: ask for a strip (3 wide, from 2 below to
	 * 1 above) along the straight line from the mob to the neighbour, so the mob's path finder sees the way.
	 */
	private static void scanCorridor(Mob m, Entity to) {
		Vec3 a = m.position(), b = to.position();
		double len = a.distanceTo(b);
		if (len > TARGET_RANGE) return;
		java.util.Set<Long> seen = new java.util.HashSet<>();
		for (double t = 0; t <= len; t += 1.0) {
			Vec3 p = a.lerp(b, len < 1e-3 ? 0 : t / len);
			int x = net.minecraft.util.Mth.floor(p.x), y = net.minecraft.util.Mth.floor(p.y), z = net.minecraft.util.Mth.floor(p.z);
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -2; dy <= 1; dy++)
				if (seen.add(BlockPos.asLong(x + dx, y + dy, z + dz))) HnImpacts.scanCell(x + dx, y + dy, z + dz);
		}
	}

	/** Ask Hello Neighbor for its geometry around this entity (radius 2), once per cell it moves into. */
	private static void scanAround(Entity e) {
		BlockPos p = e.blockPosition();
		long key = p.asLong();
		Long last = lastScanCell.get(e.getId());
		if (last != null && last == key) return;
		lastScanCell.put(e.getId(), key);
		for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -2; dy <= 2; dy++)
			HnImpacts.scanCell(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
	}
}
