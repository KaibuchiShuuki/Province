package com.mcprovince.client.render;

/** 把 Xaero 世界地图的相机参数包装成 {@link ProvinceRenderer.Projection}。 */
public final class XaeroMapProjection implements ProvinceRenderer.Projection {

    private final double cameraX;
    private final double cameraZ;
    private final double scale;
    private final double screenScale;
    private final double windowWidth;
    private final double windowHeight;

    public XaeroMapProjection(double cameraX, double cameraZ, double scale, double screenScale,
                              double windowWidth, double windowHeight) {
        this.cameraX = cameraX;
        this.cameraZ = cameraZ;
        this.scale = scale <= 0.0 ? 1.0 : scale;
        this.screenScale = screenScale <= 0.0 ? 1.0 : screenScale;
        this.windowWidth = windowWidth;
        this.windowHeight = windowHeight;
    }

    @Override
    public double screenX(double worldX, double worldZ) {
        return (this.windowWidth / 2.0 + (worldX - this.cameraX) * this.scale) / this.screenScale;
    }

    @Override
    public double screenY(double worldX, double worldZ) {
        return (this.windowHeight / 2.0 + (worldZ - this.cameraZ) * this.scale) / this.screenScale;
    }

    @Override
    public double worldX(double screenX, double screenY) {
        return (screenX * this.screenScale - this.windowWidth / 2.0) / this.scale + this.cameraX;
    }

    @Override
    public double worldZ(double screenX, double screenY) {
        return (screenY * this.screenScale - this.windowHeight / 2.0) / this.scale + this.cameraZ;
    }

    /** 每方块在 GUI 坐标下占多少像素。 */
    public double pixelsPerBlock() {
        return this.scale / this.screenScale;
    }
}
