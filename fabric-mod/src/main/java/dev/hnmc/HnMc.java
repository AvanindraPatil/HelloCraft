package dev.hnmc;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HnMc implements ModInitializer {
	public static final String MOD_ID = "hnmc";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	/** Status lines every few seconds (positions, frame times, the third-person camera): run with -Dhnmc.verbose=true. */
	public static final boolean VERBOSE = Boolean.getBoolean("hnmc.verbose");
	/**
	 * The released game (-Dhnmc.release=true, set in the Prism instance): Play.bat started this Minecraft only for Hello
	 * Neighbor, so its window is never shown and it closes when Hello Neighbor does.
	 */
	public static final boolean RELEASE = Boolean.getBoolean("hnmc.release");

	@Override
	public void onInitialize() {
		LOGGER.info("Hello Neighbor Link loaded (protocol v0)");
		HnProxy.register();   // Hello Neighbor geometry as invisible collision blocks (step 3b)
		HnItems.register();   // Hello Neighbor's own inventory as Minecraft items
		HnKit.register();     // ender pearls, arrows, TNT and rockets never run out
		HnMobs.register();    // Minecraft's mobs against the neighbour
		HnReset.register();   // /hnreset: your blocks, mobs and items away, the kit back (no Minecraft restart)
		HnDeath.register();   // dying keeps the inventory and the world: back at the safe spot
	}
}
