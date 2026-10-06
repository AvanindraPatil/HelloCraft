package dev.hnmc.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.hnmc.HnItems;
import dev.hnmc.client.HnHeld;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Third person: a held Hello Neighbor item is not drawn; its pose in Steve's hand goes to HnHeld. */
@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerMixin {
	@Unique private static ItemStack hnmc$stack = ItemStack.EMPTY;
	@Unique private static float hnmc$bodyYaw, hnmc$armX;
	@Unique private static double hnmc$x, hnmc$y, hnmc$z;

	@Inject(method = "submitArmWithItem", at = @At("HEAD"))
	private void hnmc$remember(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack itemStack, HumanoidArm arm, PoseStack poseStack,
		SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
		hnmc$stack = itemStack;
		hnmc$bodyYaw = state.bodyRot;
		hnmc$x = state.x;
		hnmc$y = state.y;
		hnmc$z = state.z;
		hnmc$armX = 0;
		// The arm's swing (setupAnim already ran): the item turns with it.
		if (((RenderLayer<?, ?>) (Object) this).getParentModel() instanceof HumanoidModel<?> h) hnmc$armX = (arm == HumanoidArm.LEFT ? h.leftArm : h.rightArm).xRot;
	}

	@Redirect(method = "submitArmWithItem", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
	private void hnmc$submit(ItemStackRenderState item, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, int outline) {
		if (HnItems.isHnItem(hnmc$stack)) {
			HnHeld.thirdPerson(poseStack, hnmc$x, hnmc$y, hnmc$z, hnmc$bodyYaw, hnmc$armX);
			return;
		}
		item.submit(poseStack, collector, light, overlay, outline);
	}
}
