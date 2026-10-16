package com.mcprovince.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;

/**
 * 几个自绘界面共用的命中判定与按钮。
 *
 * <p>以前每个界面各抄一份 {@code isInside} 和 {@code drawButton}，改一处配色要翻好几个文件；
 * 现在统一从这里取。各页自己的面板底色、标题色仍然留在本页——它们确实不一样。</p>
 */
public final class Ui {

    /** 按钮三档底色，以及选中时的描边。 */
    public static final int BTN = 0xC0222230;
    public static final int BTN_HOVER = 0xE0343448;
    public static final int BTN_SELECTED = 0xE0285C8C;
    public static final int EDGE_SELECTED = 0xFF7FD4FF;
    /** 按钮文字色。 */
    public static final int TEXT = 0xFFFFFFFF;

    private Ui() {
    }

    /** 矩形命中判定：鼠标坐标是 double，界面坐标都是 int。 */
    public static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** 画一个按钮：底色随 hover / selected 变，文字垂直居中。 */
    public static void button(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
            int x, int y, int w, int h, String label, boolean selected) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        graphics.fill(x, y, x + w, y + h, selected ? BTN_SELECTED : hover ? BTN_HOVER : BTN);
        graphics.outline(x, y, w, h, selected ? EDGE_SELECTED : BTN);
        graphics.centeredText(font, label, x + w / 2, y + (h - 8) / 2, TEXT);
    }

    /**
     * 把输入框里的字接过来自己画。
     *
     * <p>原版 {@link EditBox} 按 {@code isEditable} 在 {@code textColor} 与 {@code textColorUneditable}
     * 两套颜色里挑一套画值，实机上怎么设都偏暗；这里干脆两套都设成全透明，值交给
     * {@link #boxValue} 用纯白画。光标与选中高亮仍是原版的。</p>
     */
    public static void takeOverText(EditBox box) {
        box.setTextColor(0x00000000);
        box.setTextColorUneditable(0x00000000);
    }

    /** 用纯白画输入框当前的值，超宽的部分按原版的规则截断。 */
    public static void boxValue(GuiGraphicsExtractor graphics, Font font, EditBox box) {
        if (box == null || box.getValue().isEmpty()) {
            return;
        }
        graphics.text(font, font.plainSubstrByWidth(box.getValue(), box.getInnerWidth()),
                box.getX() + 4, box.getY() + (box.getHeight() - 8) / 2, TEXT);
    }
}
