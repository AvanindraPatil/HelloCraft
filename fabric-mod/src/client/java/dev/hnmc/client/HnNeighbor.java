package dev.hnmc.client;

import dev.hnmc.HnImpacts;
import dev.hnmc.HnMc;
import dev.hnmc.link.Layout;
import dev.hnmc.link.McLink;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;

/**
 * The neighbour in Minecraft. HnLink sends where Hello Neighbor's AI character is (kCmdNeighbor) and how big
 * (kCmdNeighborSize); the integrated server keeps a vanilla Interaction entity there: invisible, no physics, but
 * Minecraft's crosshair targets it and the player can hit it. Hello Neighbor keeps drawing the neighbour itself.
 * A hit goes back to Hello Neighbor as kEvNeighborHit (HnLink knocks him back).
 *
 * Threads: {@link #tick} on the client thread; the entity lives on the server thread (server.execute); hits are
 * seen on the server thread and queued for the client thread, the only one allowed to push events.
 */
final class HnNeighbor {
	private static final String TAG = "hnmc_neighbor";
	/** Server thread only. */
	private static Interaction entity;
	private static volatile UUID entityUuid;
	private static volatile int entityId = -1;
	private static boolean shown;
	private static long events, scanCells;

	private HnNeighbor() {}

	static void register() {
		// A fired arrow: have Hello Neighbor scan its flight path first, so it sticks in far walls too.
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			// Any fresh projectile (arrow, ender pearl, fishing bobber, snowball...): scan Hello Neighbor's geometry along
			// its predicted flight, so it hits the walls far away too.
			if (entity instanceof net.minecraft.world.entity.projectile.Projectile p && p.getDeltaMovement().lengthSqr() > 0.04) {
				HnImpacts.projectilePath(p.position(), p.getDeltaMovement(), level.getMinY(), p.getGravity());
			}
		});
		AttackEntityCallback.EVENT.register((player, level, hand, target, hitResult) -> {
			UUID id = entityUuid;
			if (!level.isClientSide() && id != null && target.getUUID().equals(id)) {
				// Minecraft's own melee numbers: the weapon's attack damage times the attack cooldown.
				float strength = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE) * player.getAttackStrengthScale(0.5f);
				Vec3 d = target.position().subtract(player.position());
				double len = Math.sqrt(d.x * d.x + d.z * d.z);
				if (len < 1e-3) {
					d = player.getLookAngle();
					len = Math.max(1e-3, Math.sqrt(d.x * d.x + d.z * d.z));
				}
				HnImpacts.neighbour(strength, d.x / len, d.z / len);
			}
			return InteractionResult.PASS;
		});
	}

	/** Client tick, after {@code link.poll()}. Also pushes every other impact (HnImpacts) to Hello Neighbor. */
	static void tick(Minecraft mc, McLink link) {
		HnImpacts.Event ev;
		while ((ev = HnImpacts.poll()) != null) {
			link.pushEvent(ev.type(), ev.a(), ev.b(), ev.c(), ev.d());
			if (ev.type() == Layout.EV_SCAN_CELL) { scanCells++; continue; }
			if (++events <= 10 || events % 50 == 0) {
				String what = ev.type() == Layout.EV_NEIGHBOR_HIT ? "hit the neighbour" : ev.type() == Layout.EV_SURFACE_HIT ? "hit Hello Neighbor's world" : "explosion";
				HnMc.LOGGER.info("Impact for Hello Neighbor: {} ({}, {}, {}, {}); {} so far, {} arrow-path cells asked for", what, ev.a(), ev.b(), ev.c(), ev.d(), events, scanCells);
			}
		}

		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) return;
		boolean present = link.neighborPresent() && link.hostInGame();
		if (present != shown) {
			shown = present;
			HnMc.LOGGER.info(present ? "Neighbour present in Minecraft ({} wide, {} tall)" : "Neighbour gone from Minecraft",
				2 * link.neighborRadius(), link.neighborHeight());
		}
		double x = link.neighborX(), y = link.neighborY(), z = link.neighborZ();
		float yaw = link.neighborYaw(), width = Math.max(0.2f, 2 * link.neighborRadius()), height = Math.max(0.5f, link.neighborHeight());
		server.execute(() -> update(server.overworld(), present, x, y, z, yaw, width, height));

		// The client's copy only gets the server position every few ticks: put it there directly, so the crosshair
		// finds him where Hello Neighbor shows him.
		if (present && mc.level != null && entityId >= 0) {
			Entity e = mc.level.getEntity(entityId);
			if (e != null) e.setPos(x, y, z);
		}
	}

	/** Server thread. */
	private static void update(ServerLevel level, boolean present, double x, double y, double z, float yaw, float width, float height) {
		dev.hnmc.HnMobs.updateBody(level, present, x, y, z, yaw);   // what mobs attack
		if (!present) {
			if (entity != null) {
				entity.discard();
				entity = null;
				entityUuid = null;
				entityId = -1;
			}
			return;
		}
		if (entity == null || entity.isRemoved() || entity.level() != level) {
			// One left over from an earlier session (it was saved with the world) would be a second, stale target.
			for (Entity old : level.getAllEntities()) {
				if (old instanceof Interaction && old.entityTags().contains(TAG)) old.discard();
			}
			Interaction e = EntityTypes.INTERACTION.create(level, EntitySpawnReason.COMMAND);
			if (e == null) return;
			e.addTag(TAG);
			e.snapTo(x, y, z, yaw, 0f);
			e.setWidth(width);
			e.setHeight(height);
			if (!level.addFreshEntity(e)) return;
			entity = e;
			entityUuid = e.getUUID();
			entityId = e.getId();
			HnMc.LOGGER.info("Neighbour entity spawned at ({}, {}, {})", String.format("%.2f", x), String.format("%.2f", y), String.format("%.2f", z));
			return;
		}
		if (entity.getWidth() != width) entity.setWidth(width);
		if (entity.getHeight() != height) entity.setHeight(height);
		if (entity.position().distanceToSqr(x, y, z) > 1e-4 || Math.abs(entity.getYRot() - yaw) > 0.5f) {
			entity.snapTo(x, y, z, yaw, 0f);
		}
		// Arrows fly through Interaction entities, so check the flying ones against his box here. Vanilla arrow
		// damage is its speed (blocks/tick) times 2; it is used up on him.
		for (AbstractArrow arrow : level.getEntitiesOfClass(AbstractArrow.class, entity.getBoundingBox().inflate(0.25),
			a -> a.getDeltaMovement().lengthSqr() > 0.04)) {
			Vec3 v = arrow.getDeltaMovement();
			HnImpacts.neighbour((float) v.length() * 2.0f, v.x, v.z);
			arrow.discard();
		}
	}
}
