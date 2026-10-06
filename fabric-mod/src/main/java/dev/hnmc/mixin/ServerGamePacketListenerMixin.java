package dev.hnmc.mixin;

import dev.hnmc.HostDrive;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While Hello Neighbor drives the player, its position is the truth: Minecraft's eye is matched to Hello Neighbor's
 * camera, so Minecraft's feet sit ~0.26 blocks below Hello Neighbor's floor and inside floor-level blocks. The server
 * would call that "moved wrongly" and snap the player back every tick (the jittering hand, and right-clicks ignored
 * while it waits for the client to accept the snap). noPhysics makes the server take the move as is, for this packet;
 * Player.tick resets the flag itself every server tick.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
	@Shadow public ServerPlayer player;

	@Inject(
		method = "handleMovePlayer",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V", shift = At.Shift.AFTER)
	)
	private void hnmc$hostDrivenMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
		if (HostDrive.active) player.noPhysics = true;
	}
}
