package dev.hnmc.client.mixin;

import dev.hnmc.client.HnSurface;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hello Neighbor's walls and floors count for the crosshair (see HnSurface). */
@Mixin(Minecraft.class)
public abstract class MinecraftPickMixin {
	@Inject(method = "pick(F)V", at = @At("TAIL"))
	private void hnmc$pickHelloNeighbor(float partialTicks, CallbackInfo ci) {
		HnSurface.adjustPick((Minecraft) (Object) this);
	}
}
