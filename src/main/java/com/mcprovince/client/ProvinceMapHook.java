package com.mcprovince.client;

import com.mcprovince.McProvince;
import com.mcprovince.client.render.ProvinceMapOverlay;
import com.mojang.blaze3d.platform.Window;
import java.lang.reflect.Field;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

/**
 * 把行政区划叠到 Xaero 的世界地图上。
 *
 * <p>0.2.1/0.2.2 前期是 {@code @Mixin(GuiMap.class)}，直接影子 cameraX/cameraZ/scale/screenScale 再注入
 * 渲染与输入。这套在 fabric-loader 0.19.3（ASM 9.10.1）上能跑，但只要换成 0.20.0
 * （ASM 9.11）启动就崩：{@code GuiMap.render} 有 5019 条指令，Mixin 的 TreeTransformer 写回类时
 * 会让 ASM 给整个类的每个方法重算栈帧，于是抛
 * {@code LimitExceededException: Too many operations}。</p>
 *
 * <p>所以从 0.2.2 起「一个字节都不碰 GuiMap」：渲染走 Fabric 给 Screen 准备的
 * {@code ScreenEvents.afterForeground}，相机参数用反射读它的 private 字段（Xaero 是第三方 mod，
 * 字段名不会被混淆），输入挂到原版的 {@code KeyboardHandler} / {@code MouseHandler} 上。</p>
 */
public final class ProvinceMapHook {

    /** Xaero 世界地图界面的类名 —— 不直接引用类，免得类加载时机上出岔子。 */
    private static final String GUI_MAP_CLASS = "xaero.map.gui.GuiMap";

    private ProvinceMapHook() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((minecraft, screen, scaledWidth, scaledHeight) -> {
            if (!isGuiMap(screen)) {
                return;
            }
            // 每次 init 都要重新挂：从树状图 setScreen 回来时 GuiMap 会再走一遍 init，
            // 而 Fabric 的 per-screen 事件活不过那一趟往返，判重会让图层从此不再出现。
            ScreenEvents.afterRender(screen).register(ProvinceMapHook::renderOverlay);
        });
    }

    /** 沿着继承链找 Xaero 的世界地图界面。 */
    private static boolean isGuiMap(Screen screen) {
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            if (GUI_MAP_CLASS.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    /** 读 Xaero 的 private double 字段；读不到就给 0，绝不把地图界面拖崩。 */
    private static double readDouble(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.getDouble(target);
            } catch (NoSuchFieldException ignored) {
                // 继续往上找父类。
            } catch (Throwable throwable) {
                McProvince.LOGGER.warn("读不到 Xaero 世界地图的字段 {}", name, throwable);
                return 0.0;
            }
        }
        return 0.0;
    }

    private static void renderOverlay(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
            float tickDelta) {
        if (!isGuiMap(screen)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        Window window = minecraft.getWindow();
        int guiWidth = window.getGuiScaledWidth();
        int guiHeight = window.getGuiScaledHeight();

        double cameraX = readDouble(screen, "cameraX");
        double cameraZ = readDouble(screen, "cameraZ");
        double scale = readDouble(screen, "scale");
        double screenScale = readDouble(screen, "screenScale");

        // 26.2 的这块是 Matrix3x2fStack（纯 2D 仿射矩阵），set 一个干净的矩阵只是抹掉
        // 前面留下的平移缩放，不会像 1.x 线的 PoseStack 那样把 Z 一起清掉。
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.set(new Matrix3x2f());
        try {
            // 导出地图要用按下按钮那一刻的视角，这里每帧记一份。
            MapExporter.updateCamera(cameraX, cameraZ, scale, screenScale);
            // 用 MouseHandler 的 double 坐标而不是 mouseX/mouseY 这两个 int：
            // 缩放地图时一个 GUI 像素可能顶好几个方块，取整会把画笔的落点钉在粗网格上。
            double preciseX = minecraft.mouseHandler.getScaledXPos(window);
            double preciseY = minecraft.mouseHandler.getScaledYPos(window);
            ProvinceMapOverlay.render(graphics, font, guiWidth, guiHeight, preciseX, preciseY,
                    cameraX, cameraZ, scale, screenScale, window.getWidth(), window.getHeight());
        } finally {
            pose.popMatrix();
        }
    }

    public static boolean allowMouseClick(Screen screen, double mouseX, double mouseY, int button) {
        if (!isGuiMap(screen)) {
            return true;
        }
        Window window = Minecraft.getInstance().getWindow();
        boolean consumed = ProvinceMapOverlay.onMousePressed(mouseX, mouseY, button,
                window.getGuiScaledWidth(), window.getGuiScaledHeight(),
                readDouble(screen, "cameraX"), readDouble(screen, "cameraZ"), readDouble(screen, "scale"),
                readDouble(screen, "screenScale"), window.getWidth(), window.getHeight());
        // 编辑模式下点到的是画笔，别让 Xaero 顺手把地图拖走。
        return !consumed;
    }

    public static boolean allowMouseRelease(Screen screen, double mouseX, double mouseY, int button) {
        return !ProvinceMapOverlay.onMouseReleased(button);
    }

    public static boolean allowKeyPress(KeyEvent event) {
        // R：在世界地图上直接开 / 关区划图层，不用绕去设置界面。
        if (ProvinceKeys.toggleProvinces != null && ProvinceKeys.toggleProvinces.matches(event)) {
            ProvinceSettings settings = ProvinceSettings.get();
            settings.worldMapEnabled = !settings.worldMapEnabled;
            settings.save();
            return false;
        }
        if (ProvinceKeys.openEditor != null && ProvinceKeys.openEditor.matches(event)) {
            ProvinceEditor.toggleActive();
            return false;
        }
        if (ProvinceEditor.isActive() && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            ProvinceEditor.setActive(false);
            return false;
        }
        return true;
    }

    /** 键盘原始事件（来自 KeyboardHandler.keyPress 的混入）。返回 true = 这次按键被吃掉。 */
    public static boolean handleRawKey(KeyEvent event) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null || !isGuiMap(screen)) {
            return false;
        }
        return !allowKeyPress(event);
    }

    /** 鼠标原始事件（来自 MouseHandler.onButton 的混入）。press=true 是按下，false 是松开。 */
    public static boolean handleRawMouse(int button, boolean press) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = minecraft.screen;
        if (screen == null || !isGuiMap(screen)) {
            return false;
        }
        Window window = minecraft.getWindow();
        double mouseX = minecraft.mouseHandler.getScaledXPos(window);
        double mouseY = minecraft.mouseHandler.getScaledYPos(window);
        return press
                ? !allowMouseClick(screen, mouseX, mouseY, button)
                : !allowMouseRelease(screen, mouseX, mouseY, button);
    }
}
