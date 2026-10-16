package com.mcprovince.client.gui;

import com.mcprovince.client.ProvinceEditor;
import com.mcprovince.client.ProvinceSettings;

import static com.mcprovince.client.ProvinceSettings.clamp;
import com.mcprovince.core.NameFormat;
import com.mcprovince.core.Province;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 方块政区的显示设置。
 *
 * <p>整页都是自绘的行控件（左减 / 右加），不依赖原版控件系统——
 * 这样布局在 26.2 的各个小版本之间不会因为控件 API 变化而失效。</p>
 *
 * <p>条目一多就装不下，所以内容区做成了固定行高的滚动视口：滚轮、右侧拖动条都能滚，
 * 视口用 {@code enableScissor} 裁掉溢出的行。</p>
 */
public class ProvinceSettingsScreen extends Screen {

    private static final int MAXW = 460;
    private static final int ROW_H = 16;
    private static final int ROW_GAP = 1;
    private static final int ROW_STEP = ROW_H + ROW_GAP;
    private static final int PAD = 10;
    /** 标题那一行加脚注占掉的高度，剩下的才是滚动视口。 */
    private static final int HEAD_H = 26;

    private static final int BG = 0xF0101418;
    private static final int ROW_BG = 0x30FFFFFF;
    private static final int ROW_ALT = 0x18FFFFFF;
    private static final int BTN = 0x55FFFFFF;
    private static final int BTN_HOVER = 0x99FFFFFF;
    private static final int TEXT = 0xFFE8EEF5;
    private static final int TEXT_DIM = 0xFF9AA7B4;
    private static final int TEXT_HEAD = 0xFF8FD3FF;
    private static final int BAR_TRACK = 0x40FFFFFF;
    private static final int BAR_THUMB = 0xA0A8B6C4;
    private static final int BAR_THUMB_HOT = 0xE0C8D8E8;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();

    private int left;
    private int top;
    private int panelH;
    private int doneY;
    /** 面板实际宽度：窄屏时收到窗口里，免得整块跑到屏幕外。 */
    private int panelW = MAXW;
    /** 滚动视口：与区划树共用同一套滚轮 / 拖动条口径。 */
    private final ScrollViewport view = new ScrollViewport();

    /** 把文字裁到指定宽度以内，尾部补省略号，免得长标签压到右边的按钮上。 */
    private String fit(String text, int maxWidth) {
        if (text == null || maxWidth <= 0 || this.font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int budget = maxWidth - this.font.width(ellipsis);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (this.font.width(sb.toString() + c) > budget) {
                break;
            }
            sb.append(c);
        }
        return sb + ellipsis;
    }

    public ProvinceSettingsScreen(Screen parent) {
        super(Component.literal("方块政区 · 设置"));
        this.parent = parent;
    }

    public static void open(Screen parent) {
        Minecraft.getInstance().setScreen(new ProvinceSettingsScreen(parent));
    }

    /** 一行内容的种类：分组标题、可调设置、可拖的滑条、纯说明。 */
    private enum Kind {
        HEAD, SETTING, SLIDER, NOTE
    }

    /**
     * 滑条行携带的东西：两端、当前值怎么读、拖动时怎么落回设置。
     *
     * <p>当前值走 {@code current} 而不是存一份快照——设置界面每帧都会重建整张行表，存快照的话
     * 拖动时滑块会慢一帧才跟上。</p>
     */
    private record Slider(int min, int max, java.util.function.IntSupplier current,
            java.util.function.IntConsumer onChange) {
    }

    /**
     * 一行内容：标题 + 当前值 + 减 / 加。
     *
     * <p>{@code HEAD} 只画分组名，{@code NOTE} 只画灰色说明——都没有按钮，也不响应点击；
     * {@code SLIDER} 用滑条代替左右按钮，{@code slider} 只有它是非空的。</p>
     */
    private record Row(String label, String value, Runnable dec, Runnable inc, Kind kind, Slider slider) {
    }

    private static Row head(String label) {
        return new Row(label, "", null, null, Kind.HEAD, null);
    }

    /** 跟在一项设置下面的灰色说明。 */
    private static Row note(String label) {
        return new Row(label, "", null, null, Kind.NOTE, null);
    }

    private static Row toggle(String label, boolean value, Runnable flip) {
        return new Row(label, value ? "开" : "关", flip, flip, Kind.SETTING, null);
    }

    private static Row number(String label, int value, Runnable dec, Runnable inc) {
        return new Row(label, Integer.toString(value), dec, inc, Kind.SETTING, null);
    }

    /** 枚举式的设置：加减各挪一格，没有滑条。 */
    private static Row choice(String label, String value, Runnable dec, Runnable inc) {
        return new Row(label, value, dec, inc, Kind.SETTING, null);
    }

    /** 滑条行：没有加减按钮，只有一条能拖的轨道，值直接读设置。 */
    private static Row slider(String label, int min, int max,
            java.util.function.IntSupplier current, java.util.function.IntConsumer onChange) {
        return new Row(label, signed(current.getAsInt()), null, null, Kind.SLIDER, new Slider(min, max, current, onChange));
    }

    private static String signed(int value) {
        return (value > 0 ? "+" : "") + value;
    }

    private void buildRows() {
        ProvinceSettings s = ProvinceSettings.get();
        rows.clear();

        rows.add(head("显示"));
        rows.add(toggle("世界地图上显示区划", s.worldMapEnabled, () -> s.worldMapEnabled = !s.worldMapEnabled));
        rows.add(toggle("小地图上显示区划", s.minimapEnabled, () -> s.minimapEnabled = !s.minimapEnabled));
        // 换显示级别会顺手重算每块区划的显示状态（树状图里手动勾掉的那些一并清空），
        // 所以这里必须走编辑器那边的入口，不能直接改字段。
        rows.add(number("显示第几级", s.shownTier,
                () -> ProvinceEditor.setShownTier(s.shownTier - 1),
                () -> ProvinceEditor.setShownTier(s.shownTier + 1)));
        rows.add(toggle("显示区划名称", s.showNames, () -> s.showNames = !s.showNames));
        rows.add(slider("地名允许超出的宽度", ProvinceSettings.NAME_SLACK_MIN, ProvinceSettings.NAME_SLACK_MAX,
                () -> s.nameSlack, value -> s.nameSlack = value));
        rows.add(choice("名称格式", s.nameFormat.label(), this::prevNameFormat, this::nextNameFormat));
        rows.add(toggle("填充区划内部", s.showFilled, () -> s.showFilled = !s.showFilled));
        rows.add(choice("填充不透明度", s.fillOpacityPercent + "%",
                () -> s.fillOpacityPercent = clamp(s.fillOpacityPercent - 5, 0, 100),
                () -> s.fillOpacityPercent = clamp(s.fillOpacityPercent + 5, 0, 100)));
        rows.add(toggle("智能降低填充精度", s.smartFill, () -> s.smartFill = !s.smartFill));
        rows.add(toggle("智能降低边框精度", s.smartStroke, () -> s.smartStroke = !s.smartStroke));
        rows.add(note("大面积高精度渲染会明显卡顿，推荐放大倍率较小时开启。"));
        rows.add(toggle("启用平方千格", s.areaInKilo, () -> s.areaInKilo = !s.areaInKilo));

        rows.add(head("序号标记"));
        rows.add(toggle("启用序号标记", s.serialMarks, () -> s.serialMarks = !s.serialMarks));
        rows.add(choice("注释位置", s.serialCorner.label(), this::prevSerialCorner, this::nextSerialCorner));

        rows.add(head("自动外框"));
        rows.add(choice("边框样式", s.borderStyle.label(), this::prevBorderStyle, this::nextBorderStyle));
        rows.add(number("边框粗细（像素）", s.borderWidth,
                () -> s.borderWidth = clamp(s.borderWidth - 1, 1, 8),
                () -> s.borderWidth = clamp(s.borderWidth + 1, 1, 8)));

        rows.add(head("导出地图"));
        rows.add(toggle("导出时包含区划", s.exportWithProvinces,
                () -> s.exportWithProvinces = !s.exportWithProvinces));

        rows.add(head("进入区划提示"));
        rows.add(toggle("启用提示标题", s.titleEnabled, () -> s.titleEnabled = !s.titleEnabled));
        rows.add(toggle("启用提示副标题", s.titleSubtitle, () -> s.titleSubtitle = !s.titleSubtitle));
        rows.add(number("触发级别", s.titleTier,
                () -> s.titleTier = clamp(s.titleTier - 1, Province.MIN_TIER, Province.MAX_TIER),
                () -> s.titleTier = clamp(s.titleTier + 1, Province.MIN_TIER, Province.MAX_TIER)));
        rows.add(choice("提示格式", s.titleFormat.label(), this::prevTitleFormat, this::nextTitleFormat));
        rows.add(number("淡入时长（tick）", s.titleFadeIn,
                () -> s.titleFadeIn = clamp(s.titleFadeIn - 5, 0, 200),
                () -> s.titleFadeIn = clamp(s.titleFadeIn + 5, 0, 200)));
        rows.add(number("停留时长（tick）", s.titleStay,
                () -> s.titleStay = clamp(s.titleStay - 10, 10, 600),
                () -> s.titleStay = clamp(s.titleStay + 10, 10, 600)));
        rows.add(number("淡出时长（tick）", s.titleFadeOut,
                () -> s.titleFadeOut = clamp(s.titleFadeOut - 5, 0, 200),
                () -> s.titleFadeOut = clamp(s.titleFadeOut + 5, 0, 200)));
    }

    private void prevNameFormat() {
        ProvinceSettings s = ProvinceSettings.get();
        s.nameFormat = shift(s.nameFormat, -1);
    }

    private void nextNameFormat() {
        ProvinceSettings s = ProvinceSettings.get();
        s.nameFormat = shift(s.nameFormat, 1);
    }

    private void prevTitleFormat() {
        ProvinceSettings s = ProvinceSettings.get();
        s.titleFormat = shift(s.titleFormat, -1);
    }

    private void nextTitleFormat() {
        ProvinceSettings s = ProvinceSettings.get();
        s.titleFormat = shift(s.titleFormat, 1);
    }

    private void prevSerialCorner() {
        ProvinceSettings s = ProvinceSettings.get();
        s.serialCorner = s.serialCorner.shift(-1);
    }

    private void nextSerialCorner() {
        ProvinceSettings s = ProvinceSettings.get();
        s.serialCorner = s.serialCorner.shift(1);
    }

    private void prevBorderStyle() {
        ProvinceSettings s = ProvinceSettings.get();
        s.borderStyle = s.borderStyle.shift(-1);
    }

    private void nextBorderStyle() {
        ProvinceSettings s = ProvinceSettings.get();
        s.borderStyle = s.borderStyle.shift(1);
    }

    private static NameFormat shift(NameFormat current, int delta) {
        NameFormat[] values = NameFormat.values();
        int index = (current.ordinal() + delta + values.length) % values.length;
        return values[index];
    }

    private void layout() {
        buildRows();
        panelW = Math.max(240, Math.min(MAXW, this.width - 24));
        int contentH = rows.size() * ROW_STEP;
        // 行高固定，装不下就滚：以前是把行高压扁硬塞，字都叠在一起了。
        int maxPanelH = Math.max(120, this.height - 12);
        int wantH = PAD * 2 + HEAD_H + contentH + 26;
        panelH = Math.min(maxPanelH, wantH);
        left = (this.width - panelW) / 2;
        top = Math.max(6, (this.height - panelH) / 2);
        doneY = top + panelH - PAD - 20;
        int contentTop = top + PAD + HEAD_H;
        this.view.setStep(ROW_STEP);
        this.view.layout(barX(), contentTop, Math.max(20, doneY - 6 - contentTop), contentH);
    }

    private int rowY(int index) {
        return this.view.top() + index * ROW_STEP - this.view.scroll();
    }

    /** 行格子（含底色、按钮、色块）的上边——所有元素共用这一条基准，才不会各画各的。 */
    private int cellTop(int index) {
        return rowY(index) - 1;
    }

    private int cellBottom(int index) {
        return cellTop(index) + ROW_H;
    }

    /** 文字基线：在格子里垂直居中。 */
    private int textY(int index) {
        return cellTop(index) + Math.max(0, (ROW_H - this.font.lineHeight) / 2);
    }

    private int rowRight() {
        return left + panelW - PAD;
    }

    private int decX() {
        return rowRight() - 108;
    }

    private int incX() {
        return rowRight() - 30;
    }

    // ————————————————— 滚动条 —————————————————

    private int barX() {
        return left + panelW - 8;
    }

    // ————————————————— 滑条 —————————————————

    /** 正在被拖的那条滑条；没在拖的时候是 null。 */
    private Row sliding;

    /** 轨道左端。跟加减按钮那一栏的起点对齐，整张表看起来才齐整。 */
    private int sliderX0() {
        return decX();
    }

    /** 轨道右端。右边留出的空档刚好写当前值。 */
    private int sliderX1() {
        return rowRight() - 52;
    }

    private int sliderThumbX(int value, int min, int max) {
        int x0 = sliderX0() + 2;
        int x1 = sliderX1() - 2;
        double t = (value - min) / (double) Math.max(1, max - min);
        return (int) Math.round(x0 + t * (x1 - x0));
    }

    /** 鼠标横坐标换算成这一行的值，两端夹住。 */
    private int sliderValueAt(double mouseX, int min, int max) {
        int x0 = sliderX0() + 2;
        int x1 = sliderX1() - 2;
        double t = (mouseX - x0) / (double) Math.max(1, x1 - x0);
        return min + (int) Math.round(Math.max(0.0, Math.min(1.0, t)) * (max - min));
    }

    /** 把鼠标横坐标落回设置。按下和拖动走的是同一条路。 */
    private void slide(Row row, double mouseX) {
        Slider slider = row.slider();
        if (slider == null) {
            return;
        }
        slider.onChange().accept(sliderValueAt(mouseX, slider.min(), slider.max()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        layout();
        graphics.fill(left - 2, top - 2, left + panelW + 2, top + panelH + 2, BG);
        graphics.renderOutline(left - 2, top - 2, panelW + 4, panelH + 4, 0xFF3A4756);
        graphics.drawCenteredString(this.font, this.title.getString(), left + panelW / 2, top + 4, TEXT_HEAD);
        graphics.drawString(this.font, "自动保存于 config/mc-province/settings.nbt",
                left + 6, top + 4 + this.font.lineHeight, TEXT_DIM);

        graphics.enableScissor(left + 2, this.view.top(), left + panelW - 2,
                this.view.top() + this.view.viewH());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int cellTop = cellTop(i);
            int cellBottom = cellBottom(i);
            if (cellBottom <= this.view.top() || cellTop >= this.view.top() + this.view.viewH()) {
                continue;
            }
            int textY = textY(i);
            Kind kind = row.kind();
            // 说明行不铺底色，读起来才像附注而不是又一条设置。
            if (kind != Kind.NOTE) {
                graphics.fill(left + 4, cellTop, left + panelW - 4, cellBottom, (i % 2 == 0) ? ROW_BG : ROW_ALT);
            }
            if (kind == Kind.HEAD) {
                graphics.drawString(this.font, fit(row.label(), panelW - PAD * 2 - 8), left + 8, textY, TEXT_HEAD);
                continue;
            }
            if (kind == Kind.NOTE) {
                graphics.drawString(this.font, fit(row.label(), panelW - PAD * 2 - 12), left + 12, textY, TEXT_DIM);
                continue;
            }
            graphics.drawString(this.font, fit(row.label(), decX() - left - 16), left + 8, textY, TEXT);

            if (kind == Kind.SLIDER && row.slider() != null) {
                Slider slider = row.slider();
                int value = slider.current().getAsInt();
                // 行表每帧重建，this.sliding 记的是上一帧那个对象，所以按标题认人。
                boolean hot = (this.sliding != null && this.sliding.label().equals(row.label()))
                        || (mouseY >= cellTop && mouseY < cellBottom
                        && mouseX >= sliderX0() - 6 && mouseX <= sliderX1() + 6);
                int trackY = cellTop + ROW_H / 2 - 1;
                graphics.fill(sliderX0(), trackY - 2, sliderX1(), trackY + 3, BAR_TRACK);
                int thumbX = sliderThumbX(value, slider.min(), slider.max());
                graphics.fill(thumbX - 2, cellTop + 2, thumbX + 3, cellBottom - 2, hot ? BAR_THUMB_HOT : BAR_THUMB);
                graphics.drawCenteredString(this.font, row.value(), rowRight() - 28, textY, TEXT);
                continue;
            }

            int decX = decX();
            int incX = incX();
            boolean decHover = mouseX >= decX && mouseX < decX + 22 && mouseY >= cellTop && mouseY < cellBottom;
            boolean incHover = mouseX >= incX && mouseX < incX + 22 && mouseY >= cellTop && mouseY < cellBottom;
            graphics.fill(decX, cellTop, decX + 22, cellBottom, decHover ? BTN_HOVER : BTN);
            graphics.fill(incX, cellTop, incX + 22, cellBottom, incHover ? BTN_HOVER : BTN);
            graphics.drawCenteredString(this.font, "-", decX + 11, textY, TEXT);
            graphics.drawCenteredString(this.font, "+", incX + 11, textY, TEXT);

            graphics.drawCenteredString(this.font, row.value(), rowRight() - 58, textY, TEXT);
        }
        graphics.disableScissor();

        this.view.draw(graphics, mouseX, mouseY, BAR_TRACK, BAR_THUMB, BAR_THUMB_HOT);

        boolean doneHover = mouseX >= left + panelW / 2 - 40 && mouseX < left + panelW / 2 + 40
                && mouseY >= doneY && mouseY < doneY + 20;
        graphics.fill(left + panelW / 2 - 40, doneY, left + panelW / 2 + 40, doneY + 20, doneHover ? BTN_HOVER : BTN);
        graphics.drawCenteredString(this.font, "完成", left + panelW / 2, doneY + 6, TEXT);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (mx >= left + panelW / 2 - 40 && mx < left + panelW / 2 + 40 && my >= doneY && my < doneY + 20) {
            this.onClose();
            return true;
        }
        if (this.view.overBar(mx, my)) {
            this.view.beginDrag(my);
            return true;
        }
        if (!this.view.containsY(my)) {
            return super.mouseClicked(mx, my, button);
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.kind() != Kind.SETTING && row.kind() != Kind.SLIDER) {
                continue;
            }
            if (my < cellTop(i) || my >= cellBottom(i)) {
                continue;
            }
            if (row.kind() == Kind.SLIDER && row.slider() != null) {
                // 滑块行没有加减按钮，整条轨道都算命中区域（稍微放宽一点，好点）。
                if (mx >= sliderX0() - 6 && mx <= sliderX1() + 6) {
                    this.sliding = row;
                    slide(row, mx);
                    return true;
                }
                continue;
            }
            int decX = decX();
            int incX = incX();
            if (mx >= decX && mx < decX + 22) {
                if (row.dec() != null) {
                    row.dec().run();
                }
                return true;
            }
            if (mx >= incX && mx < incX + 22) {
                if (row.inc() != null) {
                    row.inc().run();
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (this.sliding != null && button == 0) {
            slide(this.sliding, mx);
            return true;
        }
        if (this.view.dragging() && button == 0) {
            this.view.dragTo(my);
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (this.sliding != null) {
            this.sliding = null;
            return true;
        }
        if (this.view.dragging()) {
            this.view.endDrag();
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.view.scrollBy(scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        ProvinceSettings.get().save();
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
