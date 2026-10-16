package com.mcprovince.mixin;

import com.mcprovince.client.render.ProvinceMinimapOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler;

/**
 * 把小地图这一帧的变换参数（ps / pc / zoom / halfWView）转交给区划图层。
 *
 * <p>{@code prepareRender} 在整条小地图流程里只有一个调用点，就在把小地图纹理画干净、
 * 矩阵复位成「原点在纹理中心」之后 —— 之后画进去的东西才跟路径点同一套坐标。</p>
 */
@Mixin(MinimapElementMapRendererHandler.class)
public class MinimapElementMapRendererHandlerMixin {

    @Inject(method = "prepareRender(DDDF)V", at = @At("TAIL"))
    private void mcprovince$captureTransform(double ps, double pc, double zoom, float halfWView, CallbackInfo ci) {
        ProvinceMinimapOverlay.setTransform(ps, pc, zoom, halfWView);
    }
}
