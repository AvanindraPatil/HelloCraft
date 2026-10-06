package dev.hnmc.mixin;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The player's name in chat, advancements, death messages...: the Minecraft account's own name, unless a name is set
 * with {@code playerName=...} in {@code config/hnmc.properties} or {@code -Dhnmc.name=...} (the development run sets
 * one: its offline account is called PlayerNNN).
 */
@Mixin(Player.class)
public abstract class PlayerNameMixin {
	private static final Component HNMC_NAME = chosen();

	private static Component chosen() {
		String n = System.getProperty("hnmc.name");
		if (n == null || n.isBlank()) {
			Path file = FabricLoader.getInstance().getConfigDir().resolve("hnmc.properties");
			if (Files.isRegularFile(file)) {
				try (Reader r = Files.newBufferedReader(file)) {
					Properties p = new Properties();
					p.load(r);
					n = p.getProperty("playerName");
				} catch (IOException | RuntimeException e) {
					n = null;
				}
			}
		}
		return n == null || n.isBlank() ? null : Component.literal(n.trim());
	}

	@Inject(method = "getName", at = @At("HEAD"), cancellable = true)
	private void hnmc$name(CallbackInfoReturnable<Component> cir) {
		if (HNMC_NAME != null) cir.setReturnValue(HNMC_NAME);
	}
}
