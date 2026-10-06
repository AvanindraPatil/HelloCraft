package dev.hnmc.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.hnmc.client.HnOverlay;
import dev.hnmc.client.InputBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In overlay mode the keyboard state comes from Hello Neighbor (InputBridge), and the hidden window must never
 * grab the real mouse (that would take it away from Hello Neighbor). Same approach as SkyCraft (MIT).
 */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
	@Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
	private static void hnmc$isKeyDown(int key, CallbackInfoReturnable<Boolean> cir) {
		if (HnOverlay.active()) cir.setReturnValue(InputBridge.isKeyDown(key));
	}

	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private static void hnmc$grabMouse(Window window, double xpos, double ypos, CallbackInfo ci) {
		if (HnOverlay.active()) ci.cancel();
	}

	@Inject(method = "releaseMouse", at = @At("HEAD"), cancellable = true)
	private static void hnmc$releaseMouse(Window window, double xpos, double ypos, CallbackInfo ci) {
		if (HnOverlay.active()) ci.cancel();
	}
}
