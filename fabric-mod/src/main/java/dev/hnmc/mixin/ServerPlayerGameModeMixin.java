package dev.hnmc.mixin;

import dev.hnmc.SurfaceProxy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A right-click on a Hello Neighbor wall/floor: give vanilla placement a (temporary) block to place against. */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void hnmc$surfaceIn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit,
		CallbackInfoReturnable<InteractionResult> cir) {
		SurfaceProxy.begin(level, hit.getBlockPos());
	}

	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void hnmc$surfaceOut(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit,
		CallbackInfoReturnable<InteractionResult> cir) {
		SurfaceProxy.end(level, hit.getBlockPos());
	}
}
