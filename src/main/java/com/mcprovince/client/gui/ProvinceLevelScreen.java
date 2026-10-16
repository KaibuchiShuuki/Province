package com.mcprovince.client.gui;

import com.mcprovince.core.AdminLevels;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 加一条自己的级别名。
 *
 * <p>中文名是必填的——级别格子里就是靠它认的。英文名留空就跟着中文走，
 * 免得选了「英文全称」格式却拼出半截名字。</p>
 *
 * <p>用原版那套控件（{@link EditBox} + {@link Button}），跟
 * {@link ProvinceNameScreen} 的自绘风格不一样，但这个界面就两行输入，不值得为它再写一套。</p>
 */
public class ProvinceLevelScreen extends Screen {

    private final ProvinceNameScreen parent;
    private EditBox zhBox;
    private EditBox enBox;
    private String hint = "";

    public ProvinceLevelScreen(ProvinceNameScreen parent) {
        super(Component.literal("方块政区 · 新增级别名"));
        this.parent = parent;
    }

    public static void open(ProvinceNameScreen parent) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new ProvinceLevelScreen(parent));
    }

    @Override
    protected void init() {
        int boxW = Math.min(240, this.width - 40);
        int boxX = (this.width - boxW) / 2;

        this.zhBox = new EditBox(this.font, boxX, 56, boxW, 18, Component.empty());
        Ui.takeOverText(this.zhBox);
        this.zhBox.setMaxLength(12);
        addRenderableWidget(this.zhBox);

        this.enBox = new EditBox(this.font, boxX, 92, boxW, 18, Component.empty());
        Ui.takeOverText(this.enBox);
        this.enBox.setMaxLength(32);
        addRenderableWidget(this.enBox);

        int buttonY = 128;
        addRenderableWidget(Button.builder(Component.literal("添加"), button -> submit())
                .bounds(this.width / 2 - 84, buttonY, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose())
                .bounds(this.width / 2 + 4, buttonY, 80, 20).build());
    }

    private void submit() {
        String zh = this.zhBox == null ? "" : this.zhBox.getValue();
        if (zh.trim().isEmpty()) {
            this.hint = "中文名不能空着。";
            return;
        }
        AdminLevels.Name added = AdminLevels.addUser(zh, this.enBox == null ? "" : this.enBox.getValue());
        if (added == null) {
            this.hint = "中文名不能空着。";
            return;
        }
        // 加完就跳回命名界面，顺带把它选中、翻到它所在的那一页。
        if (this.parent != null) {
            this.parent.selectLevel(added.key());
        }
        onClose();
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics,
            int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int boxW = Math.min(240, this.width - 40);
        int boxX = (this.width - boxW) / 2;
        graphics.drawString(this.font, "级别名（必填）", boxX, 46, 0xFFFFFFFF);
        graphics.drawString(this.font, "英文名（留空就用中文名）", boxX, 82, 0xFFFFFFFF);
        Ui.boxValue(graphics, this.font, this.zhBox);
        Ui.boxValue(graphics, this.font, this.enBox);
        if (!this.hint.isEmpty()) {
            graphics.drawCenteredString(this.font, this.hint, this.width / 2, 156, 0xFFFF8080);
        }
    }

    @Override
    public void onClose() {
        if (this.parent != null) {
            this.minecraft.setScreenAndShow(this.parent);
        } else {
            super.onClose();
        }
    }
}
