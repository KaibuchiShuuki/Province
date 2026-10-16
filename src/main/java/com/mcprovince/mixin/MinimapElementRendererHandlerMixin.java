package com.mcprovince.mixin;

import com.mcprovince.client.render.ProvinceMinimapOverlay;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRendererHandler;

/**
 * 趁着 Xaero 自己画小地图元素的时候，把区划一起画进小地图的 FBO 里。
 *
 * <p>{@code render} 定义在抽象基类上（不是那些 final 的子类），所以必须 mixin 基类；
 * 它会被好几个位置各调一次，只有 {@code IN_MINIMAP} 那次画在纹理上，别的时候直接返回。</p>
 *
 * <p>1.20.1 的 Xaero（小地图 26.6.0）把 {@code GuiGraphics} 当成 render 的第一个参数直接传进来，
 * 而不是像 26.2 那样挂在一个 {@code MinimapElementGraphics} 字段上；这里改成接参数来用。
 * 画法本身不变：{@code GuiGraphics#fill(int,int,int,int,int)} 与原来那个
 * {@code Sink} 接口的签名一致。</p>
 */
@Mixin(MinimapElementRendererHandler.class)
public class MinimapElementRendererHandlerMixin {

    @Shadow
    protected MinimapElementRenderLocation location;

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/phys/Vec3;FLcom/mojang/blaze3d/pipeline/RenderTarget;DLnet/minecraft/resources/ResourceKey;)V",
            at = @At("HEAD"))
    private void mcprovince$drawProvinces(GuiGraphics guiGraphics, Vec3 renderPos, float partialTick,
            RenderTarget renderTarget, double d, ResourceKey<Level> dimension, CallbackInfo ci) {
        if (this.location != MinimapElementRenderLocation.IN_MINIMAP) {
            return;
        }
        ProvinceMinimapOverlay.render(guiGraphics, renderPos);
    }
}
