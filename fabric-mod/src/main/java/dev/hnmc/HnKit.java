package dev.hnmc;

import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Drops into the inventory (see register), and the player's never-ending supplies: every tick, stacks of these items in the inventory are topped up to a full stack
 * (a stack thrown or used to the last one keeps its slot, since it never runs out within a tick).
 */
public final class HnKit {
	public static final Set<Item> INFINITE = Set.of(Items.ENDER_PEARL, Items.ARROW, Items.TNT, Items.FIREWORK_ROCKET);

	private HnKit() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) refill(p.getInventory());
		});
		// Drops go straight into the inventory: the world is a void with floors only where Hello Neighbor's geometry
		// was scanned, so a broken chest (or anything a mob drops) fell through and was lost. Items the player throws
		// (Q) keep their thrower and still drop.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (!(entity instanceof net.minecraft.world.entity.item.ItemEntity ie) || ie.getOwner() != null || HnItems.isHnItem(ie.getItem())) return;
			level.getServer().execute(() -> {
				if (ie.isRemoved()) return;
				ServerPlayer p = level.getServer().getPlayerList().getPlayers().stream().filter(pl -> pl.level() == level)
					.min(java.util.Comparator.comparingDouble(pl -> pl.distanceToSqr(ie))).orElse(null);
				if (p == null || p.distanceToSqr(ie) > 32 * 32) return;
				ItemStack s = ie.getItem().copy();
				p.getInventory().add(s);
				if (s.isEmpty()) ie.discard(); else ie.setItem(s);
			});
		});
	}

	private static void refill(Inventory inv) {
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && INFINITE.contains(s.getItem()) && s.getCount() < s.getMaxStackSize()) s.setCount(s.getMaxStackSize());
		}
	}
}
