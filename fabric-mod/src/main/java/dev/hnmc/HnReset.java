package dev.hnmc;

import com.mojang.brigadier.Command;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;

/**
 * /hnreset: a clean slate without restarting Minecraft. Every block a player or a fluid put into the world is removed
 * (Hello Neighbor's own geometry, the invisible proxy blocks, is put back where a block had replaced it), every mob and
 * item is removed (not the neighbour's body), and the player gets the full starting kit again, healed.
 */
public final class HnReset {
	/** Set by the client side (HnWorld): gives the kit again. */
	public static volatile Consumer<ServerPlayer> kit = p -> {};

	private HnReset() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(Commands.literal("hnreset").executes(ctx -> {
				var src = ctx.getSource();
				ServerPlayer player = src.getPlayer();
				if (player == null) return 0;
				ServerLevel level = player.level();
				int blocks = 0, entities = 0;

				List<Long> cells = new ArrayList<>(BlockWatch.PLACED);
				for (long packed : cells) {
					BlockPos pos = BlockPos.of(packed);
					if (!level.hasChunkAt(pos)) continue;
					var state = level.getBlockState(pos);
					if (HnProxy.isProxy(state)) continue;
					if (!state.isAir()) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);   // reported to Hello Neighbor like any change
						blocks++;
					}
					HnProxy.restore(level, pos);
				}
				BlockWatch.PLACED.clear();

				List<Entity> gone = new ArrayList<>();
				for (Entity e : level.getAllEntities()) {
					if (e instanceof ServerPlayer || HnMobs.isBody(e)) continue;
					gone.add(e);
				}
				for (Entity e : gone) { e.discard(); entities++; }

				player.getInventory().clearContent();
				player.setHealth(player.getMaxHealth());
				player.removeAllEffects();
				player.clearFire();
				player.getFoodData().setFoodLevel(20);
				kit.accept(player);

				final int b = blocks, n = entities;
				HnMc.LOGGER.info("/hnreset: {} block(s) removed, {} entit(ies) removed, kit given again", b, n);
				src.sendSuccess(() -> Component.literal("Reset: " + b + " blocks, " + n + " mobs and items removed, kit back"), false);
				return Command.SINGLE_SUCCESS;
			})));
	}
}
