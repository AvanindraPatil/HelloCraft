package dev.hnmc.mixin;

import dev.hnmc.BlockWatch;
import dev.hnmc.SurfaceProxy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Report every successful server-side block change (mining, placing, commands) to BlockWatch. */
@Mixin(Level.class)
public abstract class LevelMixin {
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
	private void hnmc$blockChanged(BlockPos pos, BlockState state, int updateFlags, int updateLimit, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && !SurfaceProxy.quiet && !((Level) (Object) this).isClientSide()) {
			BlockWatch.changed(pos, state);
			if ((Object) this instanceof net.minecraft.server.level.ServerLevel sl) dev.hnmc.FluidGuard.changed(sl, pos, state);
		}
	}
}
