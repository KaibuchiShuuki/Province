package com.mcprovince.mixin;

import com.mcprovince.client.MapExporter;
import com.mcprovince.client.ProvinceEditor;
import com.mcprovince.client.ProvinceKeys;
import com.mcprovince.client.ProvinceSettings;
import com.mcprovince.client.render.ProvinceMapOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.glfw.GLFW;
import xaero.map.gui.GuiMap;

/**
 * 把行政区划叠到 Xaero 的世界地图上。
 *
 * <p>Xaero 的相机参数（cameraX / cameraZ / scale / screenScale）都是 private 的，
 * 这里直接影子过来自己算屏幕位置——公式跟 GuiMap 内部用的完全一致，
 * 所以两边画出来的方块能对齐。</p>
 */
@Mixin(GuiMap.class)
public abstract class GuiMapMixin {

    @Shadow
    private double cameraX;

    @Shadow
    private double cameraZ;

    @Shadow
    private double scale;

    @Shadow
    private double screenScale;

    @Inject(method = "render", at = @At("TAIL"))
    private void mcprovince$renderOverlay(GuiGraphics graphics, int mouseX, int mouseY,
            float partialTick, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        Font font = minecraft.font;
        int guiWidth = minecraft.getWindow().getGuiScaledWidth();
        int guiHeight = minecraft.getWindow().getGuiScaledHeight();

        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.set(new Matrix3x2f());
        try {
            // 导出地图要用按下按钮那一刻的视角，这里每帧记一份。
            MapExporter.updateCamera(this.cameraX, this.cameraZ, this.scale, this.screenScale);
            // 用 MouseHandler 的 double 坐标而不是 mouseX/mouseY 这两个 int：
            // 缩放地图时一个 GUI 像素可能顶好几个方块，取整会把画笔的落点钉在粗网格上。
            double preciseX = minecraft.mouseHandler.getScaledXPos(minecraft.getWindow());
            double preciseY = minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());
            ProvinceMapOverlay.render(graphics, font, guiWidth, guiHeight, preciseX, preciseY,
                    this.cameraX, this.cameraZ, this.scale, this.screenScale,
                    minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
        } finally {
            pose.popMatrix();
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void mcprovince$mouseClicked(MouseButtonEvent event, boolean doubleClick,
            CallbackInfoReturnable<Boolean> cir) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        boolean consumed = ProvinceMapOverlay.onMousePressed(event.x(), event.y(), event.button(),
                minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight(),
                this.cameraX, this.cameraZ, this.scale, this.screenScale,
                minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
        if (consumed) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void mcprovince$mouseReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (ProvinceMapOverlay.onMouseReleased(event.button())) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void mcprovince$keyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        // R：在世界地图上直接开 / 关区划图层，不用绕去设置界面。
        if (ProvinceKeys.toggleProvinces != null && ProvinceKeys.toggleProvinces.matches(event)) {
            ProvinceSettings settings = ProvinceSettings.get();
            settings.worldMapEnabled = !settings.worldMapEnabled;
            settings.save();
            cir.setReturnValue(Boolean.TRUE);
            return;
        }
        if (ProvinceKeys.openEditor != null && ProvinceKeys.openEditor.matches(event)) {
            ProvinceEditor.toggleActive();
            cir.setReturnValue(Boolean.TRUE);
            return;
        }
        if (ProvinceEditor.isActive() && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            ProvinceEditor.setActive(false);
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
