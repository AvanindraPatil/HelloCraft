package dev.hnmc.client.mixin;

import java.util.UUID;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Without a downloaded skin Minecraft picks one of nine default characters from the player's UUID (the offline dev
 * account gets a random one). Always use classic Steve (wide arms) instead. A real account's own skin still wins:
 * this only replaces the fallback.
 */
@Mixin(DefaultPlayerSkin.class)
public abstract class DefaultPlayerSkinMixin {
	@Shadow @Final private static PlayerSkin[] DEFAULT_SKINS;

	private static final int WIDE_STEVE = 15; // "entity/player/wide/steve"

	@Inject(method = "get(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/PlayerSkin;", at = @At("HEAD"), cancellable = true)
	private static void hnmc$steve(UUID profileId, CallbackInfoReturnable<PlayerSkin> cir) {
		cir.setReturnValue(DEFAULT_SKINS[WIDE_STEVE]);
	}
}
