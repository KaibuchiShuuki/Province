package com.mcprovince.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 一列可滚动的行内容共用的视口。
 *
 * <p>区划树和设置两页都是「固定行高的长列表 + 右侧拖动条」：滑块多长、滑块停在哪、拖到哪算哪、
 * 滚轮滚几格，两边算的完全是同一套东西。这套计算集中在这里，各页只管自己的布局和行内容。</p>
 *
 * <p>每帧画之前用 {@link #layout} 登记视口几何（行数变了则用 {@link #setContentH}），
 * 之后 {@link #scroll()} 就是当前偏移，行 {@code i} 的顶边是 {@code top() + i * step - scroll()}。</p>
 */
final class ScrollViewport {

    /** 拖动条宽（像素）。 */
    static final int BAR_W = 4;
    /** 滑块最短高度：内容特别长时也不至于细得抓不住。 */
    private static final int MIN_THUMB = 18;
    /** 鼠标离条这么近就算是「停在条上」。 */
    private static final int HOT_MARGIN = 3;

    /** 每行多高（含行距），滚轮一滚两行。 */
    private int step = 1;
    private int barX;
    private int top;
    private int viewH = 1;
    private int contentH = 1;
    private int maxScroll;
    private int scroll;
    private int thumbGrab;
    private boolean dragging;

    /** 每行多高（含行距）。 */
    void setStep(int step) {
        this.step = Math.max(1, step);
    }

    /** 每帧布局后调用：登记视口几何，并把滚动位置夹回合法区间。 */
    void layout(int barX, int top, int viewH, int contentH) {
        this.barX = barX;
        this.top = top;
        this.viewH = Math.max(1, viewH);
        this.setContentH(contentH);
    }

    /** 内容总高变了（删了一块区划之类）之后调用，重新夹一次滚动位置。 */
    void setContentH(int contentH) {
        this.contentH = Math.max(1, contentH);
        this.maxScroll = Math.max(0, this.contentH - this.viewH);
        this.scroll = clamp(this.scroll, 0, this.maxScroll);
    }

    int scroll() {
        return this.scroll;
    }

    /** 视口顶边。 */
    int top() {
        return this.top;
    }

    int viewH() {
        return this.viewH;
    }

    /** 鼠标的 y 是否落在视口里。 */
    boolean containsY(double mouseY) {
        return mouseY >= this.top && mouseY < this.top + this.viewH;
    }

    /** 内容装不下才有滚动条。 */
    boolean visible() {
        return this.maxScroll > 0;
    }

    private int thumbH() {
        return Math.max(MIN_THUMB, this.viewH * this.viewH / this.contentH);
    }

    private int thumbY() {
        int travel = this.viewH - thumbH();
        if (travel <= 0 || this.maxScroll <= 0) {
            return this.top;
        }
        return this.top + (int) Math.round((double) travel * this.scroll / this.maxScroll);
    }

    /** 鼠标是否落在拖动条上（含一点余量）。 */
    boolean overBar(double mouseX, double mouseY) {
        return visible() && mouseX >= this.barX - HOT_MARGIN && mouseX < this.barX + BAR_W + HOT_MARGIN
                && containsY(mouseY);
    }

    /** 在条上按下：压在滑块上就抓住它，压在空白处就当成「跳到这儿」，抓取点取滑块中心。 */
    void beginDrag(double mouseY) {
        int ty = thumbY();
        int th = thumbH();
        this.dragging = true;
        this.thumbGrab = (mouseY >= ty && mouseY < ty + th) ? (int) mouseY - ty : th / 2;
        dragTo(mouseY);
    }

    boolean dragging() {
        return this.dragging;
    }

    void dragTo(double mouseY) {
        int travel = this.viewH - thumbH();
        if (travel <= 0) {
            this.scroll = 0;
            return;
        }
        int y = (int) mouseY - this.top - this.thumbGrab;
        this.scroll = clamp((int) Math.round((double) y * this.maxScroll / travel), 0, this.maxScroll);
    }

    void endDrag() {
        this.dragging = false;
    }

    /**
     * 滚轮滚一次。
     *
     * @return 是否真的滚了（内容装得下时就交给界面自己处理）
     */
    boolean scrollBy(double scrollY) {
        if (this.maxScroll <= 0 || scrollY == 0.0) {
            return false;
        }
        this.scroll = clamp(this.scroll - (int) Math.round(scrollY * this.step * 2.0), 0, this.maxScroll);
        return true;
    }

    /** 画轨道与滑块；颜色由各页自己定，保持各自的配色。 */
    void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            int trackColor, int thumbColor, int thumbHotColor) {
        if (!visible()) {
            return;
        }
        graphics.fill(this.barX, this.top, this.barX + BAR_W, this.top + this.viewH, trackColor);
        int ty = thumbY();
        int th = thumbH();
        boolean hot = this.dragging || (mouseX >= this.barX - HOT_MARGIN
                && mouseX < this.barX + BAR_W + HOT_MARGIN && mouseY >= ty && mouseY < ty + th);
        graphics.fill(this.barX, ty, this.barX + BAR_W, ty + th, hot ? thumbHotColor : thumbColor);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
