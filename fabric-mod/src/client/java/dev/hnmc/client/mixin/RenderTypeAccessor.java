package dev.hnmc.client.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A render type's name and setup (its textures). Same accessor as SkyCraft (MIT, github.com/chasmlol/SkyCraft). */
@Mixin(RenderType.class)
public interface RenderTypeAccessor {
	@Accessor("state")
	RenderSetup hnmc$state();

	@Accessor("name")
	String hnmc$name();
}
