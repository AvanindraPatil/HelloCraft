package dev.hnmc.mixin;

import dev.hnmc.HnImpacts;
import dev.hnmc.HnProxy;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An arrow landing in Hello Neighbor's geometry (a proxy block) hits whatever Hello Neighbor has there. */
@Mixin(AbstractArrow.class)
public abstract class ArrowMixin {
	@Inject(method = "onHitBlock", at = @At("HEAD"))
	private void hnmc$hitHelloNeighbor(BlockHitResult hit, CallbackInfo ci) {
		AbstractArrow arrow = (AbstractArrow) (Object) this;
		if (arrow.level().isClientSide() || !HnProxy.isProxy(arrow.level().getBlockState(hit.getBlockPos()))) return;
		// Vanilla arrow damage is speed (blocks/tick, ~3 at full draw) times its base damage of 2.
		HnImpacts.surface(hit.getLocation(), (float) arrow.getDeltaMovement().length() * 2.0f, HnImpacts.KIND_ARROW);
	}
}
