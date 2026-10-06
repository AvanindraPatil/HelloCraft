package dev.hnmc.client.mixin;

import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The texture a RenderSetup binds. Same accessor as SkyCraft (MIT, github.com/chasmlol/SkyCraft). */
@Mixin(targets = "net.minecraft.client.renderer.rendertype.RenderSetup$TextureBinding")
public interface TextureBindingAccessor {
	@Accessor("location")
	Identifier hnmc$location();
}
