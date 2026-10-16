package com.mcprovince.client.render;

/**
 * 小地图上的投影。
 *
 * <p>旋转与缩放全部照抄 Xaero 自己那一套：{@code ps}/{@code pc} 是它算出来的正余弦，
 * {@code zoom} 是「每方块多少纹理像素」。公式与它在
 * {@code MinimapElementRendererHandler#transformAndRenderForRenderer} 里把元素摆到
 * 小地图纹理上的那两行完全一致 —— 早先按世界地图那套（X 用 {@code pc*dx + ps*dz}）
 * 写过一遍，在小地图里画出来的东西是转了反方向的，等于什么都没对上。</p>
 *
 * <p>坐标原点是小地图纹理中心（GUI 层那条路径里对应小地图矩形的中心）。</p>
 */
public final class MinimapProjection implements ProvinceRenderer.Projection {

    private final double centerX;
    private final double centerY;
    private final double cameraX;
    private final double cameraZ;
    private final double ps;
    private final double pc;
    private final double zoom;

    public MinimapProjection(double centerX, double centerY, double cameraX, double cameraZ,
            double ps, double pc, double zoom) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.cameraX = cameraX;
        this.cameraZ = cameraZ;
        this.ps = ps;
        this.pc = pc;
        this.zoom = zoom;
    }

    @Override
    public double screenX(double worldX, double worldZ) {
        double dx = worldX - this.cameraX;
        double dz = worldZ - this.cameraZ;
        return this.centerX + (this.ps * dx - this.pc * dz) * this.zoom;
    }

    @Override
    public double screenY(double worldX, double worldZ) {
        double dx = worldX - this.cameraX;
        double dz = worldZ - this.cameraZ;
        return this.centerY + (this.pc * dx + this.ps * dz) * this.zoom;
    }

    @Override
    public double worldX(double screenX, double screenY) {
        double rx = (screenX - this.centerX) / this.zoom;
        double ry = (screenY - this.centerY) / this.zoom;
        double det = this.ps * this.ps + this.pc * this.pc;
        return this.cameraX + (this.ps * rx + this.pc * ry) / det;
    }

    @Override
    public double worldZ(double screenX, double screenY) {
        double rx = (screenX - this.centerX) / this.zoom;
        double ry = (screenY - this.centerY) / this.zoom;
        double det = this.ps * this.ps + this.pc * this.pc;
        return this.cameraZ + (this.ps * ry - this.pc * rx) / det;
    }
}
