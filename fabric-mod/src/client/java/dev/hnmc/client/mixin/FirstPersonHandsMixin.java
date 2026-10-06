package dev.hnmc.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.hnmc.HnItems;
import dev.hnmc.client.HnHeld;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** First person: a held Hello Neighbor item is not drawn; its view-space pose (bob and swing included) goes to HnHeld. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsMixin {
	@Unique private static ItemStack hnmc$stack = ItemStack.EMPTY;

	@Inject(method = "submitArmWithItem", at = @At("HEAD"))
	private void hnmc$remember(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, float partialTicks, float xRot,
		InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector collector,
		int lightCoords, CallbackInfo ci) {
		hnmc$stack = itemStack;
	}

	@Redirect(method = "submitArmWithItem", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
	private void hnmc$submit(ItemStackRenderState item, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, int outline) {
		if (HnItems.isHnItem(hnmc$stack)) {
			HnHeld.firstPerson(poseStack);
			return;
		}
		item.submit(poseStack, collector, light, overlay, outline);
	}
}
