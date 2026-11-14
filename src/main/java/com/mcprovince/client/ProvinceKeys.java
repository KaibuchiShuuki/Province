package com.mcprovince.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** 快捷键。默认都不占用游戏常用键位。 */
public final class ProvinceKeys {

    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mc-province", "main"));

    /** 打开编辑器。 */
    public static KeyMapping openEditor;
    /** 在世界地图界面上切换「显示区划 / 隐藏区划」。 */
    public static KeyMapping toggleProvinces;

    private ProvinceKeys() {
    }

    public static void register() {
        openEditor = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mc-province.open_editor", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, CATEGORY));
        toggleProvinces = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mc-province.toggle_provinces", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));
    }
}
