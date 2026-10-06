package dev.hnmc;

import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The player never really dies. A real death creates a new player (empty inventory, reloaded chunks, a respawn at
 * the void world's spawn), and the kit was lost until Ctrl+R. Instead the death is cancelled and the player is
 * healed; the client (HnWorld) then puts the player back at its last safe spot, like Hello Neighbor's own respawn.
 */
public final class HnDeath {
	/** Counts cancelled deaths; HnWorld (same process) puts the player back at the anchor when it changes. */
	public static final AtomicInteger COUNT = new AtomicInteger();

	/** Shown one after the other, one per death, then round again. */
	private static final String[] MESSAGES = {
		"Respawning. I will pretend that never happened - ACK",
		"The neighbor didn't even have to try.",
		"Even the neighbor felt sorry for you.",
		"Skill issue. Respawning at the start - ACK",
		"You forgot to bring a water bucket.",
		"Gravity: 1, you: 0",
		"At least don't die in Hello Neighbor - ACK",
		"Totem of undying: not found.",
		"You died in Minecraft. The neighbor is judging you. - ACK",
		"That's what you get for flying into a wall.",
		"Back to the start. The neighbor is fine, don't worry about him.",
		"You died. The neighbor is laughing in the basement.",
	};

	private HnDeath() {}

	public static void register() {
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (!(entity instanceof ServerPlayer p)) return true;
			p.setHealth(p.getMaxHealth());
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(5.0f);
			p.removeAllEffects();
			p.clearFire();
			p.setAirSupply(p.getMaxAirSupply());
			p.resetFallDistance();
			p.setDeltaMovement(0, 0, 0);
			int n = COUNT.getAndIncrement();
			p.sendSystemMessage(Component.literal(MESSAGES[n % MESSAGES.length]), true);
			HnMc.LOGGER.info("Player died ({}): death cancelled, back to the start of the level", source.getMsgId());
			return false;
		});
	}
}
