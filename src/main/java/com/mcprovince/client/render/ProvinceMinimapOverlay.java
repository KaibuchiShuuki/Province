package com.mcprovince.client.render;

import com.mcprovince.McProvince;
import com.mcprovince.client.ProvinceClient;
import com.mcprovince.client.ProvinceSettings;
import com.mcprovince.core.ProvinceStore;
import net.minecraft.world.phys.Vec3;
import xaero.hud.minimap.element.render.MinimapElementGraphics;

/**
 * 小地图上的区划图层。
 *
 * <p>画在小地图自己的那块 FBO 里，而不是叠在 GUI 上层。Xaero 的小地图本体是 PIP
 * （延迟贴图）画出来的，同层的普通绘制会被它整个盖住 —— 之前试过在
 * {@code MinimapRenderer#render} 末尾换层再画，换层确实生效（同层手写的方块看得见），
 * 但同一批区划命令就是不显示，所以改回「跟路径点一样，作为小地图元素一起画进纹理」。</p>
 *
 * <p>变换参数由 {@code MinimapElementMapRendererHandler#prepareRender} 每帧给出，
 * 公式照抄它自己的 {@code transformAndRenderForRenderer}：元素相对玩家的偏移乘缩放后
 * 按 {@code ps}/{@code pc} 线性组合。这样画出来的东西跟 Xaero 的路径点在同一个坐标系里。</p>
 */
public final class ProvinceMinimapOverlay {

    private static double ps = 1.0;
    private static double pc = 0.0;
    private static double zoom = 1.0;
    private static double halfWView;

    private ProvinceMinimapOverlay() {
    }

    /**
     * Xaero 每帧在开始画小地图元素之前把这一帧的变换参数交出来。
     *
     * @param ps        朝向的正弦
     * @param pc        朝向的余弦
     * @param zoom      每方块多少纹理像素
     * @param halfWView 小地图纹理半宽（纹理像素），也是元素剔除的边界
     */
    public static void setTransform(double ps, double pc, double zoom, double halfWView) {
        ProvinceMinimapOverlay.ps = ps;
        ProvinceMinimapOverlay.pc = pc;
        ProvinceMinimapOverlay.zoom = zoom;
        ProvinceMinimapOverlay.halfWView = halfWView;
    }

    /**
     * 把小地图纹理里的区划画出来。
     *
     * @param graphics Xaero 的元素绘制器，坐标原点就是小地图纹理中心
     * @param renderPos 这一帧插值后的相机位置
     */
    public static void render(MinimapElementGraphics graphics, Vec3 renderPos) {
        if (graphics == null || renderPos == null) {
            return;
        }
        ProvinceSettings settings = ProvinceSettings.get();
        if (!settings.minimapEnabled) {
            return;
        }
        if (!(zoom > 0.0) || !(halfWView > 0.0)) {
            return;
        }
        ProvinceStore store = ProvinceClient.data().store();
        if (store == null || store.isEmpty()) {
            return;
        }

        double half = halfWView;
        MinimapProjection projection = new MinimapProjection(0.0, 0.0,
                renderPos.x, renderPos.z, ps, pc, zoom);

        try {
            // 地名不画：小地图就巴掌大，字只会糊成一团；圆形遮罩由 Xaero 的 FBO 自己负责。
            ProvinceRenderer.drawShapes(graphics::fill, store, settings, projection,
                    -half, -half, half, half, zoom, 0);
        } catch (Throwable throwable) {
            // 画崩了也不该把小地图一起拖下水。
            McProvince.LOGGER.warn("小地图区划绘制失败", throwable);
        }
    }
}
