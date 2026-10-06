package dev.hnmc.client.mixin;

import dev.hnmc.client.HnOverlay;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hello Neighbor draws the world. In overlay mode Minecraft renders nothing of its own level (no sky, fog or
 * terrain), so the main target is just hand + HUD on the (0,0,0,0) clear. Same hook as SkyCraft (MIT).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(
		method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void hnmc$skipLevel(CallbackInfo ci) {
		if (HnOverlay.active()) ci.cancel();
	}
}
