package dev.hnmc.client.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.hnmc.client.HnOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Minecraft window is hidden in overlay mode; Hello Neighbor has the real focus, so pretend we do too. */
@Mixin(Window.class)
public abstract class WindowMixin {
	@Inject(method = "isFocused", at = @At("HEAD"), cancellable = true)
	private void hnmc$focused(CallbackInfoReturnable<Boolean> cir) {
		if (HnOverlay.active()) cir.setReturnValue(true);
	}

	@Inject(method = "isIconified", at = @At("HEAD"), cancellable = true)
	private void hnmc$notIconified(CallbackInfoReturnable<Boolean> cir) {
		if (HnOverlay.active()) cir.setReturnValue(false);
	}
}
