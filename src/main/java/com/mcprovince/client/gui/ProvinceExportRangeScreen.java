package com.mcprovince.client.gui;

import com.mcprovince.core.Lang;

import com.mcprovince.client.MapExporter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 「导出指定范围地图」的输入页：四个方块坐标框，确认后交给 {@link MapExporter} 去导。
 *
 * <p>坐标一律按方块算、含端点：X1..X2、Z1..Z2 就是导出图盖住的那一片方块。
 * 两个方向都允许填反，确认时会自己换成小的在前后大的在后，不会因为写反就导出一张空图。</p>
 */
public class ProvinceExportRangeScreen extends Screen {

    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_MUTED = 0xFFAAAAAA;
    private static final int COL_HINT = 0xFFFF8080;
    private static final int COL_PANEL = 0xF0121218;
    private static final int COL_EDGE = 0xFF3C3C46;

    /** 面板左右内边距：输入框、按钮都摆在这圈里，标签在框正上方，不占横向空间。 */
    private static final int PANEL_PAD = 14;
    private static final int MIN_PANEL_W = 260;
    private static final int MAX_PANEL_W = 400;
    private static final int FIELD_H = 18;
    private static final int ROW1 = 88;
    private static final int ROW2 = 126;
    private static final int BUTTON_W = 80;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_Y = 154;
    private static final int PANEL_TOP = 34;
    private static final int PANEL_BOTTOM = 196;
    private static final int LABEL_GAP = 13;

    private final Screen parent;
    private EditBox x1Box;
    private EditBox x2Box;
    private EditBox z1Box;
    private EditBox z2Box;
    private String hint = "";

    /** 面板与输入框的横向几何只在 init 里算一次，render 和点击判定都读这里。 */
    private int panelW;
    private int panelLeft;
    private int fieldW;

    public ProvinceExportRangeScreen(Screen parent) {
        super(Component.literal(Lang.t("province.range.title")));
        this.parent = parent;
    }

    /** 从地图界面打开；默认值就是当前屏幕上看得见的那一块，改两个角就行。 */
    public static void open(Screen parent) {
        Minecraft.getInstance().setScreen(new ProvinceExportRangeScreen(parent));
    }

    @Override
    protected void init() {
        this.panelW = Math.min(MAX_PANEL_W, Math.max(MIN_PANEL_W, this.width - 64));
        this.panelLeft = (this.width - this.panelW) / 2;
        this.fieldW = (this.panelW - 20) / 2;
        int x1 = this.panelLeft;
        int x2 = this.panelLeft + this.panelW - this.fieldW;

        int[] initial = MapExporter.currentScreenBlockRange(this.minecraft);
        int[] values = initial == null ? new int[] {0, 0, 0, 0} : initial;

        this.x1Box = box(x1, ROW1, values[0]);
        this.x2Box = box(x2, ROW1, values[2]);
        this.z1Box = box(x1, ROW2, values[1]);
        this.z2Box = box(x2, ROW2, values[3]);
        // 焦点给 X1，玩家进来直接敲数字。
        this.setFocused(this.x1Box);
    }

    private EditBox box(int x, int y, int value) {
        EditBox box = new EditBox(this.font, x, y, this.fieldW, FIELD_H, Component.empty());
        Ui.takeOverText(box);
        box.setMaxLength(12);
        box.setValue(Integer.toString(value));
        addRenderableWidget(box);
        return box;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xB0000000);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.fill(this.panelLeft - PANEL_PAD, PANEL_TOP,
                this.panelLeft + this.panelW + PANEL_PAD, PANEL_BOTTOM, COL_PANEL);
        graphics.renderOutline(this.panelLeft - PANEL_PAD, PANEL_TOP,
                this.panelW + PANEL_PAD * 2, PANEL_BOTTOM - PANEL_TOP, COL_EDGE);

        int headW = this.panelW + PANEL_PAD * 2;
        int headX = this.panelLeft - PANEL_PAD;
        String header = Lang.t("province.range.header");
        Ui.scrollingText(graphics, this.font, "range:header", header, mouseX, mouseY,
                headX, PANEL_TOP + 12, headW, 10, COL_TEXT, true);
        String subtitle = Lang.t("province.range.subtitle");
        Ui.scrollingText(graphics, this.font, "range:subtitle", subtitle, mouseX, mouseY,
                headX, PANEL_TOP + 26, headW, 10, COL_MUTED, true);

        int x1 = this.panelLeft;
        int x2 = this.panelLeft + this.panelW - this.fieldW;
        int labelTop1 = ROW1 - LABEL_GAP;
        int labelTop2 = ROW2 - LABEL_GAP;
        graphics.drawString(this.font, "X1", x1, labelTop1, COL_TEXT);
        graphics.drawString(this.font, "X2", x2, labelTop1, COL_TEXT);
        graphics.drawString(this.font, "Z1", x1, labelTop2, COL_TEXT);
        graphics.drawString(this.font, "Z2", x2, labelTop2, COL_TEXT);
        Ui.boxValue(graphics, this.font, this.x1Box);
        Ui.boxValue(graphics, this.font, this.x2Box);
        Ui.boxValue(graphics, this.font, this.z1Box);
        Ui.boxValue(graphics, this.font, this.z2Box);

        Ui.button(graphics, this.font, mouseX, mouseY, okX(), BUTTON_Y, BUTTON_W, BUTTON_H, Lang.t("province.gui.export"), false);
        Ui.button(graphics, this.font, mouseX, mouseY, cancelX(), BUTTON_Y, BUTTON_W, BUTTON_H, Lang.t("province.gui.cancel"), false);

        String foot = this.hint.isEmpty()
                ? Lang.t("province.range.limit_hint", MapExporter.MAX_RANGE_BLOCKS)
                : this.hint;
        Ui.scrollingText(graphics, this.font, "range:foot:" + foot, foot, mouseX, mouseY,
                10, BUTTON_Y + BUTTON_H + 8, this.width - 20, 10,
                this.hint.isEmpty() ? COL_MUTED : COL_HINT, true);
    }

    private int okX() {
        return this.width / 2 - BUTTON_W - 4;
    }

    private int cancelX() {
        return this.width / 2 + 4;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (Ui.inside(mouseX, mouseY, okX(), BUTTON_Y, BUTTON_W, BUTTON_H)) {
            confirm();
            return true;
        }
        if (Ui.inside(mouseX, mouseY, cancelX(), BUTTON_Y, BUTTON_W, BUTTON_H)) {
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void confirm() {
        int x1;
        int x2;
        int z1;
        int z2;
        try {
            x1 = Integer.parseInt(this.x1Box.getValue().trim());
            x2 = Integer.parseInt(this.x2Box.getValue().trim());
            z1 = Integer.parseInt(this.z1Box.getValue().trim());
            z2 = Integer.parseInt(this.z2Box.getValue().trim());
        } catch (NumberFormatException e) {
            this.hint = Lang.t("province.range.bad_number");
            return;
        }
        // 写反了不是错：小的当前面、大的当后面，直接换过来。
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        long width = (long) maxX - minX + 1;
        long height = (long) maxZ - minZ + 1;
        if (width <= 0 || height <= 0) {
            this.hint = Lang.t("province.range.invalid");
            return;
        }
        if (width > MapExporter.MAX_RANGE_BLOCKS || height > MapExporter.MAX_RANGE_BLOCKS) {
            this.hint = Lang.t("province.range.too_large", MapExporter.MAX_RANGE_BLOCKS, width, height);
            return;
        }
        MapExporter.requestExport(MapExporter.ExportMode.RANGE, minX, minZ, maxX, maxZ);
        onClose();
    }

    @Override
    public void onClose() {
        if (this.parent != null) {
            this.minecraft.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }
}
