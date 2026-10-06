package dev.hnmc.client.mixin;

import dev.hnmc.client.HnOverlay;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Capture the frame for Hello Neighbor right after Minecraft has rendered it. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void hnmc$beginFrame(boolean advanceGameTime, CallbackInfo ci) {
		HnOverlay.beginFrame((Minecraft) (Object) this);
	}

	@Inject(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER)
	)
	private void hnmc$afterRender(boolean advanceGameTime, CallbackInfo ci) {
		HnOverlay.afterRender((Minecraft) (Object) this);
	}
}
