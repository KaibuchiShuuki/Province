package com.mcprovince.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
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

    /** 跑马灯：两份文字之间的空档、滚动速度、悬停后先停多久再动。 */
    public static final double MARQUEE_GAP = 24.0;
    public static final double MARQUEE_SPEED = 30.0;
    public static final long MARQUEE_HOLD_MS = 600L;

    /** 正在滚的那一段（按文字内容认），以及它开始滚的时刻。 */
    private static String marqueeKey;
    private static long marqueeStart;

    private Ui() {
    }

    /** 矩形命中判定：鼠标坐标是 double，界面坐标都是 int。 */
    public static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** 画一个按钮：底色随 hover / selected 变，文字垂直居中。 */
    public static void button(GuiGraphics graphics, Font font, int mouseX, int mouseY,
            int x, int y, int w, int h, String label, boolean selected) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        graphics.fill(x, y, x + w, y + h, selected ? BTN_SELECTED : hover ? BTN_HOVER : BTN);
        graphics.renderOutline(x, y, w, h, selected ? EDGE_SELECTED : BTN);
        scrollingText(graphics, font, "button:" + label, label, mouseX, mouseY, x, y, w, h, TEXT, true);
    }

    /**
     * 画一段可能放不下的字：放得下就正常画，放不下先露开头，鼠标停在上面时开始跑马灯。
     *
     * <p>按钮标签、设置项的名字都走这里——翻译成英文/俄文以后常见「中文够宽、译文溢出」，
     * 与其把按钮撑大或者硬截断，不如让字自己滚过去。跑马灯按 {@code key} 认，
     * 鼠标挪到同一段字上不会重新从第一帧开始。</p>
     *
     * @param center 放得下的时候是否水平居中（按钮 true，左对齐的标签 false）
     */
    public static void scrollingText(GuiGraphics graphics, Font font, String key, String text,
            int mouseX, int mouseY, int x, int y, int w, int h, int color, boolean center) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int textW = font.width(text);
        int baseline = y + (h - font.lineHeight) / 2;
        if (textW <= w - 6) {
            if (center) {
                graphics.drawCenteredString(font, text, x + w / 2, baseline, color);
            } else {
                graphics.drawString(font, text, x + 3, baseline, color);
            }
            return;
        }
        // 上下不内缩：字高就是框高时，缩 1px 会把字的顶边削掉。
        graphics.enableScissor(x + 2, y, x + w - 2, y + h);
        if (!inside(mouseX, mouseY, x, y, w, h)) {
            // 平时就露开头那一段，剩下的裁在框外——想看全名把鼠标停上去。
            graphics.drawString(font, text, x + 3, baseline, color);
            graphics.disableScissor();
            return;
        }
        long now = System.currentTimeMillis();
        if (!key.equals(marqueeKey)) {
            marqueeKey = key;
            marqueeStart = now;
        }
        double span = textW + MARQUEE_GAP;
        double travelled = Math.max(0L, now - marqueeStart - MARQUEE_HOLD_MS) / 1000.0 * MARQUEE_SPEED;
        int offset = (int) Math.round(travelled % span);
        graphics.drawString(font, text, x + 3 - offset, baseline, color);
        // 后一份紧跟在前一份后面：滚出去的同时下一份从右边接上，看着是连着的。
        graphics.drawString(font, text, x + 3 - offset + (int) Math.round(span), baseline, color);
        graphics.disableScissor();
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
    public static void boxValue(GuiGraphics graphics, Font font, EditBox box) {
        if (box == null || box.getValue().isEmpty()) {
            return;
        }
        graphics.drawString(font, font.plainSubstrByWidth(box.getValue(), box.getInnerWidth()),
                box.getX() + 4, box.getY() + (box.getHeight() - 8) / 2, TEXT);
    }
}
