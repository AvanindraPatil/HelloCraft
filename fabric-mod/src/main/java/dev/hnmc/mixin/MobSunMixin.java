package dev.hnmc.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zombies and skeletons never burn in Minecraft's daylight. The hidden Minecraft world is kept at noon (HnMobs) so the
 * hand, blocks and mobs drawn into Hello Neighbor are lit like daytime; mobs must survive it to fight the neighbour.
 */
@Mixin(Mob.class)
public abstract class MobSunMixin {
	@Inject(method = "burnUndead", at = @At("HEAD"), cancellable = true)
	private void hnmc$noSunBurn(CallbackInfo ci) {
		ci.cancel();
	}
}
