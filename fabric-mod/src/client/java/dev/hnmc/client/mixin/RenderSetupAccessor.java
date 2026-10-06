package dev.hnmc.client.mixin;

import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sampler name -> RenderSetup.TextureBinding. Same accessor as SkyCraft (MIT, github.com/chasmlol/SkyCraft). */
@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {
	@Accessor("textures")
	Map<String, ?> hnmc$textures();
}
