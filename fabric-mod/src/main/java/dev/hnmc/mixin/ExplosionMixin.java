package dev.hnmc.mixin;

import dev.hnmc.HnImpacts;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** TNT, creepers, ...: Hello Neighbor's world feels the blast too (its geometry is blast-proof in Minecraft). */
@Mixin(ServerExplosion.class)
public abstract class ExplosionMixin {
	@Inject(method = "explode", at = @At("HEAD"))
	private void hnmc$blastHelloNeighbor(CallbackInfoReturnable<Integer> cir) {
		ServerExplosion explosion = (ServerExplosion) (Object) this;
		HnImpacts.explosion(explosion.center(), explosion.radius());
	}
}
