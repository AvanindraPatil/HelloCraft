package dev.hnmc.client.mixin;

import dev.hnmc.client.HnInteract;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Right-click on something Hello Neighbor can interact with (a door, a switch, an item) opens / presses / picks it
 * up in Hello Neighbor instead of placing or using a Minecraft item, like a door in Minecraft. Sneak to place.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftUseMixin {
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void hnmc$useHelloNeighbor(CallbackInfo ci) {
		if (HnInteract.rightClick((Minecraft) (Object) this)) ci.cancel();
	}
}
