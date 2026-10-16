package com.mcprovince.client;

import com.mcprovince.McProvince;
import com.mcprovince.client.render.ProvinceMapOverlay;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Field;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * 把行政区划叠到 Xaero 的世界地图上。
 *
 * <p>26.2 那边是在 {@code GuiMap.render} 的尾部做 {@code @Inject}。这套搬到 1.21.1 上会炸：
 * {@code GuiMap.render} 有几千条指令，Fabric Loader 0.20.0 自带的 ASM 9.11 在重算栈帧时
 * 抛 {@code LimitExceededException: Too many operations}，游戏起不来。
 * 更麻烦的是 {@code @Accessor} 也一样炸 —— Mixin 只要碰了这个类，就会把整个类重写一遍，
 * {@code MethodWriter.visitMaxs} 照样得给 {@code render} 重算栈帧。所以这里是「一个字节都不碰
 * GuiMap」：渲染走 Fabric 给 Screen 准备的事件，相机参数用反射直接读它的 private 字段
 * （Xaero 是第三方 mod，字段名不会被混淆，{@code cameraX}/{@code cameraZ}/{@code scale}/
 * {@code screenScale} 就叫这个名字）。</p>
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

        // 这里跟 26.2 的写法不一样，别照抄：26.2 是在 GuiMap.render 内部注入的，
        // 那时 Xaero 自己的变换可能还留在栈上，所以要 set 一个干净的矩阵。
        // 我们挂在 ScreenEvents.afterRender 上，它跑在 screen.render() 返回之后的
        // GameRenderer 里，GuiMap 早就把栈弹干净了 —— 此时栈里的状态正是 GameRenderer
        // 给 GUI 铺好的那个：translate(0, 0, -2000)，配上 setOrtho(0, w, h, 0, 1000, 3000)。
        // 要是照着 26.2 来一发 setIdentity()，Z 就变成 0，整个图层落在正交投影的
        // 近远平面之外，画了等于没画 —— 界面上就是干干净净什么都没有。
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            // afterRender 挂在 Screen.renderWithTooltip 上，跑起来时这一帧 GUI 的顶点
            // 早就提交过了，新加的顶点得自己冲一次才会出现在画面上。
            graphics.flush();
            // 导出地图要用按下按钮那一刻的视角，这里每帧记一份。
            MapExporter.updateCamera(cameraX, cameraZ, scale, screenScale);
            // 用 MouseHandler 的 double 坐标而不是 mouseX/mouseY 这两个 int：
            // 缩放地图时一个 GUI 像素可能顶好几个方块，取整会把画笔的落点钉在粗网格上。
            // 1.21.1 的 MouseHandler 只有窗口像素坐标（xpos/ypos），没有 getScaledXPos，
            // 所以这里按「窗口 → GUI」的缩放比换算；不取整，保住亚像素精度。
            double preciseX = minecraft.mouseHandler.xpos() * guiWidth / (double) window.getScreenWidth();
            double preciseY = minecraft.mouseHandler.ypos() * guiHeight / (double) window.getScreenHeight();
            ProvinceMapOverlay.render(graphics, font, guiWidth, guiHeight, preciseX, preciseY,
                    cameraX, cameraZ, scale, screenScale, window.getWidth(), window.getHeight());
        } finally {
            pose.popPose();
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

    public static boolean allowKeyPress(Screen screen, int keyCode, int scanCode, int modifiers) {
        // R：在世界地图上直接开 / 关区划图层，不用绕去设置界面。
        if (ProvinceKeys.toggleProvinces != null && ProvinceKeys.toggleProvinces.matches(keyCode, scanCode)) {
            ProvinceSettings settings = ProvinceSettings.get();
            settings.worldMapEnabled = !settings.worldMapEnabled;
            settings.save();
            return false;
        }
        if (ProvinceKeys.openEditor != null && ProvinceKeys.openEditor.matches(keyCode, scanCode)) {
            ProvinceEditor.toggleActive();
            return false;
        }
        if (ProvinceEditor.isActive() && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            ProvinceEditor.setActive(false);
            return false;
        }
        return true;
    }

    /** 键盘原始事件（来自 KeyboardHandler.keyPress 的混入）。返回 true = 这次按键被吃掉。 */
    public static boolean handleRawKey(int keyCode, int scanCode, int modifiers) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null || !isGuiMap(screen)) {
            return false;
        }
        return !allowKeyPress(screen, keyCode, scanCode, modifiers);
    }

    /** 鼠标原始事件（来自 MouseHandler.onPress 的混入）。press=true 是按下，false 是松开。 */
    public static boolean handleRawMouse(int button, boolean press) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = minecraft.screen;
        if (screen == null || !isGuiMap(screen)) {
            return false;
        }
        Window window = minecraft.getWindow();
        double mouseX = minecraft.mouseHandler.xpos() * window.getGuiScaledWidth()
                / (double) window.getScreenWidth();
        double mouseY = minecraft.mouseHandler.ypos() * window.getGuiScaledHeight()
                / (double) window.getScreenHeight();
        return press
                ? !allowMouseClick(screen, mouseX, mouseY, button)
                : !allowMouseRelease(screen, mouseX, mouseY, button);
    }
}
