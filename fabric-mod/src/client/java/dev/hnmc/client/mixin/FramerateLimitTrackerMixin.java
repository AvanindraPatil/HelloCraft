package dev.hnmc.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.hnmc.client.HnOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A hidden window would otherwise be throttled to a crawl; keep up with Hello Neighbor (~60 fps) with headroom. */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
	@Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
	private void hnmc$limit(CallbackInfoReturnable<Integer> cir) {
		if (HnOverlay.active()) cir.setReturnValue(120);
	}
}
