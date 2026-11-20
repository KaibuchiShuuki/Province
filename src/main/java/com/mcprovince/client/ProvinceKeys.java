package com.mcprovince.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** 快捷键。默认都不占用游戏常用键位。 */
public final class ProvinceKeys {

    /**
     * 1.21.1 的 {@code KeyMapping} 收的还是分类的 lang 键字符串本身
     * （26.2 才换成 {@code KeyMapping.Category} 对象），而且 1.x 线的分类键是复数
     * {@code key.categories.*}——单数写法要等到 26.x 才认。
     */
    public static final String CATEGORY = "key.categories.mc-province.main";

    /** 打开编辑器。 */
    public static KeyMapping openEditor;
    /** 在世界地图界面上切换「显示区划 / 隐藏区划」。 */
    public static KeyMapping toggleProvinces;

    private ProvinceKeys() {
    }

    public static void register() {
        openEditor = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mc-province.open_editor", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, CATEGORY));
        toggleProvinces = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mc-province.toggle_provinces", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));
    }
}
