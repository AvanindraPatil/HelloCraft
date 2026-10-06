package dev.hnmc.mixin;

import dev.hnmc.HnMobs;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Mobs fighting the neighbour never pick the player: their own target AI gets the neighbour's body instead (HnMobs). */
@Mixin(Mob.class)
public abstract class MobTargetMixin {
	@ModifyVariable(method = "setTarget", at = @At("HEAD"), argsOnly = true)
	private @Nullable LivingEntity hnmc$neighbourInstead(@Nullable LivingEntity target) {
		return HnMobs.redirectTarget((Mob) (Object) this, target);
	}
}
