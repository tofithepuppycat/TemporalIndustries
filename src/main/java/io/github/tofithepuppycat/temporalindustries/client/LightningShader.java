package io.github.tofithepuppycat.temporalindustries.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/** The core shader behind {@link PylonBoltRenderer}'s lightning, and the additive render type that uses it. */
public final class LightningShader {
    private static ShaderInstance shader;

    private static final RenderType RENDER_TYPE = RenderType.create(
            "temporalindustries_lightning",
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            4096,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(LightningShader::get))
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    private LightningShader() {}

    public static void register(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "lightning"),
                DefaultVertexFormat.POSITION_TEX_COLOR), instance -> shader = instance);
    }

    public static ShaderInstance get() {
        return shader;
    }

    public static RenderType renderType() {
        return RENDER_TYPE;
    }
}
