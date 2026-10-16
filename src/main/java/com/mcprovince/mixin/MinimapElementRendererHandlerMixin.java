package com.mcprovince.mixin;

import com.mcprovince.client.render.ProvinceMinimapOverlay;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.hud.minimap.element.render.MinimapElementGraphics;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRendererHandler;

/**
 * 趁着 Xaero 自己画小地图元素的时候，把区划一起画进小地图的 FBO 里。
 *
 * <p>{@code render} 定义在抽象基类上（不是那些 final 的子类），所以必须 mixin 基类；
 * 它会被好几个位置各调一次，只有 {@code IN_MINIMAP} 那次画在纹理上，别的时候直接返回。</p>
 */
@Mixin(MinimapElementRendererHandler.class)
public class MinimapElementRendererHandlerMixin {

    @Shadow
    protected MinimapElementGraphics guiGraphics;

    @Shadow
    protected MinimapElementRenderLocation location;

    @Inject(method = "render(Lnet/minecraft/world/phys/Vec3;FLcom/mojang/blaze3d/pipeline/RenderTarget;DLnet/minecraft/resources/ResourceKey;)V",
            at = @At("HEAD"))
    private void mcprovince$drawProvinces(Vec3 renderPos, float partialTick, RenderTarget renderTarget,
            double d, ResourceKey<Level> dimension, CallbackInfo ci) {
        if (this.location != MinimapElementRenderLocation.IN_MINIMAP) {
            return;
        }
        ProvinceMinimapOverlay.render(this.guiGraphics, renderPos);
    }
}
