package com.mcprovince.client;

import com.mcprovince.core.Province;
import com.mcprovince.core.ProvinceStore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 走进一块新的行政区划时，在屏幕中间弹出地名。 */
public final class ProvinceTitleTracker {

    private static final int NONE = Integer.MIN_VALUE;

    private static int lastProvinceId = NONE;

    private ProvinceTitleTracker() {
    }

    public static void tick(Minecraft minecraft) {
        ProvinceSettings settings = ProvinceSettings.get();
        if (!settings.titleEnabled || minecraft.player == null || minecraft.level == null) {
            lastProvinceId = NONE;
            return;
        }
        ProvinceStore store = ProvinceClient.data().store();
        if (store.isEmpty()) {
            lastProvinceId = NONE;
            return;
        }
        int blockX = (int) Math.floor(minecraft.player.getX());
        int blockZ = (int) Math.floor(minecraft.player.getZ());
        Province province = store.findAt(settings.titleTier, blockX, blockZ);
        int id = province == null ? NONE : province.id();
        if (id == lastProvinceId) {
            return;
        }
        lastProvinceId = id;
        if (province == null) {
            return;
        }
        String label = province.displayName(settings.titleFormat);
        if (label.isEmpty()) {
            return;
        }
        minecraft.gui.hud.setTimes(settings.titleFadeIn, settings.titleStay, settings.titleFadeOut);
        minecraft.gui.hud.setTitle(Component.literal(label));
        // 副标题＝这一块的父级名，样式跟主标题一套，只是原版把它画成灰色。
        Province parent = province.parentId() == Province.NO_PARENT ? null : store.byId(province.parentId());
        String parentLabel = parent == null ? "" : parent.displayName(settings.titleFormat);
        if (settings.titleSubtitle && !parentLabel.isEmpty()) {
            minecraft.gui.hud.setSubtitle(Component.literal(parentLabel).withStyle(ChatFormatting.GRAY));
        } else {
            minecraft.gui.hud.setSubtitle(Component.empty());
        }
    }
}
