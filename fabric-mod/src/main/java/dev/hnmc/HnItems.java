package dev.hnmc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.component.CustomData;

/**
 * Hello Neighbor's own inventory inside Minecraft's. Every item the Hello Neighbor player carries (picked up with
 * Hello Neighbor's own pick-up, see HnInteract) is an `hnmc:hn_object` item in the Minecraft inventory, named after
 * it and tagged with the id HnLink gave its actor (kCmdHnItem). Selecting that hotbar slot takes the real item out
 * in Hello Neighbor; selecting anything else puts it away (kEvHnSelect). The Minecraft item has no in-hand model:
 * what you see in your hand is Hello Neighbor's real item.
 *
 * Hello Neighbor is the source of truth: the server makes the Minecraft inventory match the list (adds new items,
 * selecting them, as a pick-up puts the item in your hand; removes thrown or used ones). A dropped hn_object never
 * lands in the Minecraft world; it goes back into the inventory.
 */
public final class HnItems {
	public static final ResourceKey<Item> KEY = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(HnMc.MOD_ID, "hn_object"));
	public static Item ITEM;
	private static final String TAG_ID = "hn_id";

	/** id -> name, as Hello Neighbor last reported it (written on the client thread, read on the server thread). */
	private static final Map<Integer, String> ITEMS = new ConcurrentHashMap<>();
	private static final Map<Integer, char[]> NAME_PARTS = new ConcurrentHashMap<>();
	private static final AtomicInteger VERSION = new AtomicInteger();
	private static int appliedVersion = -1;
	private static int ticks;

	private HnItems() {}

	public static void register() {
		ITEM = Registry.register(BuiltInRegistries.ITEM, KEY, new Item(new Item.Properties().setId(KEY).stacksTo(1)));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % 10 != 0 && VERSION.get() == appliedVersion) return;
			int v = VERSION.get();
			for (ServerPlayer p : server.getPlayerList().getPlayers()) reconcile(p);
			appliedVersion = v;
		});
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof ItemEntity ie && isHnItem(ie.getItem())) {
				ie.discard();   // Hello Neighbor's item never lies in the Minecraft world; reconcile puts it back
				HnMc.LOGGER.info("A Hello Neighbor item was dropped in Minecraft; it goes back into the inventory");
			}
		});
	}

	public static boolean isHnItem(ItemStack s) { return ITEM != null && !s.isEmpty() && s.getItem() == ITEM; }

	/** The Hello Neighbor item id of this stack, 0 if it is not one. */
	public static int idOf(ItemStack s) {
		if (!isHnItem(s)) return 0;
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d == null ? 0 : d.copyTag().getIntOr(TAG_ID, 0);
	}

	/** kCmdHnItem (client thread). */
	public static void hostItem(int a, int b, int c, int d) {
		int id = a & 0xFFFF, part = (a >>> 16) & 0xFF;
		boolean present = ((a >>> 24) & 0xFF) != 0;
		if (!present) {
			boolean changed = id == 0 ? !ITEMS.isEmpty() : ITEMS.remove(id) != null;
			if (id == 0) { ITEMS.clear(); NAME_PARTS.clear(); } else NAME_PARTS.remove(id);
			if (changed) VERSION.incrementAndGet();
			return;
		}
		if (part > 1) return;
		char[] name = NAME_PARTS.computeIfAbsent(id, k -> new char[24]);
		int[] words = { b, c, d };
		for (int i = 0; i < 12; i++) name[part * 12 + i] = (char) ((words[i / 4] >>> (8 * (i % 4))) & 0xFF);
		if (part == 0) for (int i = 12; i < 24; i++) name[i] = 0;
		int len = 0;
		while (len < 24 && name[len] != 0) len++;
		String s = new String(name, 0, len);
		if (!s.equals(ITEMS.get(id))) {
			ITEMS.put(id, s);
			VERSION.incrementAndGet();
		}
	}

	/** Server thread: make the player's inventory hold exactly Hello Neighbor's items. */
	private static void reconcile(ServerPlayer p) {
		Inventory inv = p.getInventory();
		Map<Integer, Integer> have = new java.util.HashMap<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!isHnItem(s)) continue;
			int id = idOf(s);
			String want = ITEMS.get(id);
			if (want == null || have.containsKey(id)) {
				inv.setItem(i, ItemStack.EMPTY);   // thrown / used up in Hello Neighbor, or a duplicate
				HnMc.LOGGER.info("Hello Neighbor item {} left the inventory", id);
				continue;
			}
			have.put(id, i);
			if (!want.equals(nameOf(s))) s.set(DataComponents.ITEM_NAME, Component.literal(want));
		}
		for (Map.Entry<Integer, String> e : ITEMS.entrySet()) {
			if (have.containsKey(e.getKey())) continue;
			ItemStack s = new ItemStack(ITEM);
			CompoundTag t = new CompoundTag();
			t.putInt(TAG_ID, e.getKey());
			s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
			s.set(DataComponents.ITEM_NAME, Component.literal(e.getValue()));
			int slot = placeInHand(inv, s);
			if (slot < 0) {
				HnMc.LOGGER.warn("No room in the Minecraft inventory for Hello Neighbor's {}", e.getValue());
				continue;
			}
			if (Inventory.isHotbarSlot(slot)) {
				inv.setSelectedSlot(slot);
				p.connection.send(new ClientboundSetHeldSlotPacket(slot));
			}
			HnMc.LOGGER.info("Hello Neighbor's {} (item {}) is now in Minecraft slot {}", e.getValue(), e.getKey(), slot);
		}
	}

	private static String nameOf(ItemStack s) {
		Component c = s.get(DataComponents.ITEM_NAME);
		return c == null ? "" : c.getString();
	}

	/** A fresh pick-up goes into the hand: the selected slot if empty, else a free hotbar slot, else the selected slot
	 * after moving its Minecraft item into the main inventory. Returns the slot, or -1 if the inventory is full. */
	private static int placeInHand(Inventory inv, ItemStack s) {
		int sel = inv.getSelectedSlot();
		if (inv.getItem(sel).isEmpty()) { inv.setItem(sel, s); return sel; }
		for (int i = 0; i < Inventory.getSelectionSize(); i++) if (inv.getItem(i).isEmpty()) { inv.setItem(i, s); return i; }
		for (int i = Inventory.getSelectionSize(); i < 36; i++) {
			if (!inv.getItem(i).isEmpty()) continue;
			inv.setItem(i, inv.getItem(sel));
			inv.setItem(sel, s);
			return sel;
		}
		return -1;
	}
}
