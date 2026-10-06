package dev.hnmc.client.mixin;

import dev.hnmc.HnImpacts;
import dev.hnmc.HnProxy;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A left click on Hello Neighbor's own geometry (a proxy block, or the surface HnSurface put under the crosshair,
 * which is air in Minecraft) hits what Hello Neighbor has there. Minecraft itself would do nothing.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftAttackMixin {
	@Inject(method = "startAttack", at = @At("HEAD"))
	private void hnmc$hitHelloNeighbor(CallbackInfoReturnable<Boolean> cir) {
		Minecraft mc = (Minecraft) (Object) this;
		if (mc.player == null || mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
		var state = mc.level.getBlockState(hit.getBlockPos());
		if (!state.isAir() && !HnProxy.isProxy(state)) return;   // a real Minecraft block: vanilla mining
		// Minecraft's melee numbers (read before the attack resets the cooldown): attack damage times cooldown.
		float strength = (float) mc.player.getAttributeValue(Attributes.ATTACK_DAMAGE) * mc.player.getAttackStrengthScale(0.5f);
		HnImpacts.surface(hit.getLocation(), strength, HnImpacts.KIND_MELEE);
	}
}
