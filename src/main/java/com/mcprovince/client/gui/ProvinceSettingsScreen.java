package com.mcprovince.client.gui;

import com.mcprovince.client.ProvinceEditor;
import com.mcprovince.client.ProvinceSettings;

import static com.mcprovince.client.ProvinceSettings.clamp;
import com.mcprovince.core.Lang;
import com.mcprovince.core.Language;
import com.mcprovince.core.NameFormat;
import com.mcprovince.core.Province;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
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

    /** 翻译模组的下载地址；仓库还没公开，先占位成同一个账号下的预计地址。 */
    private static final String TRANSLATION_URL = "https://github.com/KaibuchiShuuki/Province-Translation";
    private static final int TOOLTIP_BG = 0xF0060A0E;
    private static final int TOOLTIP_EDGE = 0xFF3A4756;
    private static final int TEXT_ACCENT = 0xFF7FD4FF;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();

    /** 语言行在 {@link #rows} 里的下标；行表每次重建都会重新记一遍。 */
    private int languageRow = -1;
    /** 问号提示被点住了。悬停只是临时显示，点一下才留住。 */
    private boolean languageHelpPinned;

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
        super(Component.literal(Lang.t("province.settings.title")));
        this.parent = parent;
    }

    public static void open(Screen parent) {
        Minecraft.getInstance().setScreenAndShow(new ProvinceSettingsScreen(parent));
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
        return new Row(label, Lang.t(value ? "province.settings.on" : "province.settings.off"), flip, flip, Kind.SETTING, null);
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

        rows.add(head(Lang.t("province.settings.section.interface")));
        // 🗣 后面别带 VS16：默认字体不认那个变体选择符，会多画一个方框。
        this.languageRow = rows.size();
        rows.add(choice("\uD83D\uDDE3 " + Lang.t("province.settings.language"), languageLabel(),
                this::prevLanguage, this::nextLanguage));

        rows.add(head(Lang.t("province.settings.section.display")));
        rows.add(toggle(Lang.t("province.settings.world_map"), s.worldMapEnabled, () -> s.worldMapEnabled = !s.worldMapEnabled));
        rows.add(toggle(Lang.t("province.settings.minimap"), s.minimapEnabled, () -> s.minimapEnabled = !s.minimapEnabled));
        // 换显示级别会顺手重算每块区划的显示状态（树状图里手动勾掉的那些一并清空），
        // 所以这里必须走编辑器那边的入口，不能直接改字段。
        rows.add(number(Lang.t("province.settings.shown_tier"), s.shownTier,
                () -> ProvinceEditor.setShownTier(s.shownTier - 1),
                () -> ProvinceEditor.setShownTier(s.shownTier + 1)));
        rows.add(toggle(Lang.t("province.settings.show_names"), s.showNames, () -> s.showNames = !s.showNames));
        rows.add(slider(Lang.t("province.settings.name_slack"), ProvinceSettings.NAME_SLACK_MIN, ProvinceSettings.NAME_SLACK_MAX,
                () -> s.nameSlack, value -> s.nameSlack = value));
        rows.add(choice(Lang.t("province.settings.name_format"), s.nameFormat.label(), this::prevNameFormat, this::nextNameFormat));
        rows.add(toggle(Lang.t("province.settings.fill"), s.showFilled, () -> s.showFilled = !s.showFilled));
        rows.add(choice(Lang.t("province.settings.fill_opacity"), s.fillOpacityPercent + "%",
                () -> s.fillOpacityPercent = clamp(s.fillOpacityPercent - 5, 0, 100),
                () -> s.fillOpacityPercent = clamp(s.fillOpacityPercent + 5, 0, 100)));
        rows.add(toggle(Lang.t("province.settings.smart_fill"), s.smartFill, () -> s.smartFill = !s.smartFill));
        rows.add(toggle(Lang.t("province.settings.smart_stroke"), s.smartStroke, () -> s.smartStroke = !s.smartStroke));
        rows.add(note(Lang.t("province.settings.smart_note")));
        rows.add(toggle(Lang.t("province.settings.area_kilo"), s.areaInKilo, () -> s.areaInKilo = !s.areaInKilo));

        rows.add(head(Lang.t("province.settings.section.serial")));
        rows.add(toggle(Lang.t("province.settings.serial_marks"), s.serialMarks, () -> s.serialMarks = !s.serialMarks));
        rows.add(choice(Lang.t("province.settings.serial_corner"), s.serialCorner.label(), this::prevSerialCorner, this::nextSerialCorner));

        rows.add(head(Lang.t("province.settings.section.border")));
        rows.add(choice(Lang.t("province.settings.border_style"), s.borderStyle.label(), this::prevBorderStyle, this::nextBorderStyle));
        rows.add(number(Lang.t("province.settings.border_width"), s.borderWidth,
                () -> s.borderWidth = clamp(s.borderWidth - 1, 1, 8),
                () -> s.borderWidth = clamp(s.borderWidth + 1, 1, 8)));

        rows.add(head(Lang.t("province.settings.section.export")));
        rows.add(toggle(Lang.t("province.settings.export_provinces"), s.exportWithProvinces,
                () -> s.exportWithProvinces = !s.exportWithProvinces));

        rows.add(head(Lang.t("province.settings.section.title")));
        rows.add(toggle(Lang.t("province.settings.title_enabled"), s.titleEnabled, () -> s.titleEnabled = !s.titleEnabled));
        rows.add(toggle(Lang.t("province.settings.title_subtitle"), s.titleSubtitle, () -> s.titleSubtitle = !s.titleSubtitle));
        rows.add(number(Lang.t("province.settings.title_tier"), s.titleTier,
                () -> s.titleTier = clamp(s.titleTier - 1, Province.MIN_TIER, Province.MAX_TIER),
                () -> s.titleTier = clamp(s.titleTier + 1, Province.MIN_TIER, Province.MAX_TIER)));
        rows.add(choice(Lang.t("province.settings.title_format"), s.titleFormat.label(), this::prevTitleFormat, this::nextTitleFormat));
        rows.add(number(Lang.t("province.settings.title_fade_in"), s.titleFadeIn,
                () -> s.titleFadeIn = clamp(s.titleFadeIn - 5, 0, 200),
                () -> s.titleFadeIn = clamp(s.titleFadeIn + 5, 0, 200)));
        rows.add(number(Lang.t("province.settings.title_stay"), s.titleStay,
                () -> s.titleStay = clamp(s.titleStay - 10, 10, 600),
                () -> s.titleStay = clamp(s.titleStay + 10, 10, 600)));
        rows.add(number(Lang.t("province.settings.title_fade_out"), s.titleFadeOut,
                () -> s.titleFadeOut = clamp(s.titleFadeOut - 5, 0, 200),
                () -> s.titleFadeOut = clamp(s.titleFadeOut + 5, 0, 200)));
    }

    private void prevLanguage() {
        shiftLanguage(-1);
    }

    private void nextLanguage() {
        shiftLanguage(1);
    }

    /**
     * 在「跟随系统」和可用语言的列表里循环；切完立刻重读文案表，不用退出界面。
     *
     * <p>只有装了翻译模组才有一项「跟随系统」——只装本体时语言就两种，
     * 跟随系统要么落在这两种里、要么没有意义，不如干脆不出现。</p>
     */
    private void shiftLanguage(int delta) {
        ProvinceSettings s = ProvinceSettings.get();
        List<Language> choices = new ArrayList<>();
        if (Lang.translationPackInstalled()) {
            choices.add(null); // 跟随游戏语言
        }
        for (Language language : Language.values()) {
            if (Lang.available(language)) {
                choices.add(language);
            }
        }
        if (choices.isEmpty()) {
            return;
        }
        Language fixed = Language.byCode(s.languageCode);
        int index = choices.indexOf(fixed);
        if (index < 0) {
            // 当前是「跟随系统」，或者那门语言已经不在了：从实际落点开始挪。
            index = choices.indexOf(s.effectiveLanguage());
            if (index < 0) {
                index = 0;
            }
        }
        index = (index + delta + choices.size()) % choices.size();
        Language next = choices.get(index);
        s.languageCode = next == null ? Language.AUTO : next.code();
        Lang.setLanguage(s.effectiveLanguage());
        // 每一行的文字都是构造时取的，换语言后要整表重建；行数不变，滚动位置继续沿用。
        buildRows();
    }

    /**
     * 语言行右边那一段字。
     *
     * <p>只装本体时没有「跟随系统」：系统语言是中文就显示简体中文，否则显示英文（默认）。
     * 装了翻译模组、并且系统语言真的能对上一种有文案的语言，才显示「跟随系统」。</p>
     */
    private String languageLabel() {
        ProvinceSettings s = ProvinceSettings.get();
        Language fixed = Language.byCode(s.languageCode);
        if (fixed != null && Lang.available(fixed)) {
            return Lang.t(fixed.nameKey());
        }
        if (!Lang.translationPackInstalled()) {
            // 本体只有两种语言，没有「跟随系统」这一项：直接显示实际落点。
            return Lang.t(s.effectiveLanguage().nameKey());
        }
        Language resolved = s.effectiveLanguage();
        if (Lang.available(resolved)) {
            return Lang.t("province.settings.language.auto");
        }
        return Lang.t("province.language.en_us.default");
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
        String head = this.title.getString();
        Ui.scrollingText(graphics, this.font, "head:" + head, head, mouseX, mouseY,
                left + PAD, top + 4, panelW - PAD * 2, this.font.lineHeight, TEXT_HEAD, true);
        String footer = Lang.t("province.settings.footer", "config/mc-province/settings.nbt");
        Ui.scrollingText(graphics, this.font, "footer:" + footer, footer, mouseX, mouseY,
                left + 6, top + 4 + this.font.lineHeight, panelW - 12, this.font.lineHeight, TEXT_DIM, false);

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
                Ui.scrollingText(graphics, this.font, "row:" + row.label(), row.label(), mouseX, mouseY,
                        left + 8, textY, panelW - PAD * 2 - 8, this.font.lineHeight, TEXT_HEAD, false);
                continue;
            }
            if (kind == Kind.NOTE) {
                Ui.scrollingText(graphics, this.font, "row:" + row.label(), row.label(), mouseX, mouseY,
                        left + 12, textY, panelW - PAD * 2 - 12, this.font.lineHeight, TEXT_DIM, false);
                continue;
            }
            boolean showHelp = i == this.languageRow && showLanguageHelp();
            if (showHelp) {
                // 问号占住行首那一小格，标签整体右移，免得压在一起。
                drawHelpButton(graphics, mouseX, mouseY, i);
            }
            int labelX = showHelp ? left + 23 : left + 8;
            int labelW = showHelp ? decX() - left - 31 : decX() - left - 16;
            Ui.scrollingText(graphics, this.font, "row:" + row.label(), row.label(), mouseX, mouseY,
                    labelX, textY, labelW, this.font.lineHeight, TEXT, false);

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
                Ui.scrollingText(graphics, this.font, "val:" + row.label(), row.value(), mouseX, mouseY,
                        sliderX1() + 2, textY, rowRight() - sliderX1() - 2, this.font.lineHeight, TEXT, true);
                continue;
            }

            int decX = decX();
            int incX = incX();
            boolean decHover = mouseX >= decX && mouseX < decX + 22 && mouseY >= cellTop && mouseY < cellBottom;
            boolean incHover = mouseX >= incX && mouseX < incX + 22 && mouseY >= cellTop && mouseY < cellBottom;
            graphics.fill(decX, cellTop, decX + 22, cellBottom, decHover ? BTN_HOVER : BTN);
            graphics.fill(incX, cellTop, incX + 22, cellBottom, incHover ? BTN_HOVER : BTN);
            graphics.drawCenteredString(this.font, "◀", decX + 11, textY, TEXT);
            graphics.drawCenteredString(this.font, "▶", incX + 11, textY, TEXT);

            Ui.scrollingText(graphics, this.font, "val:" + row.label(), row.value(), mouseX, mouseY,
                    decX + 24, textY, incX - decX - 26, this.font.lineHeight, TEXT, true);
        }
        graphics.disableScissor();

        this.view.draw(graphics, mouseX, mouseY, BAR_TRACK, BAR_THUMB, BAR_THUMB_HOT);

        boolean doneHover = mouseX >= left + panelW / 2 - 40 && mouseX < left + panelW / 2 + 40
                && mouseY >= doneY && mouseY < doneY + 20;
        graphics.fill(left + panelW / 2 - 40, doneY, left + panelW / 2 + 40, doneY + 20, doneHover ? BTN_HOVER : BTN);
        String doneLabel = Lang.t("province.settings.done");
        Ui.scrollingText(graphics, this.font, "done:" + doneLabel, doneLabel, mouseX, mouseY,
                left + panelW / 2 - 40, doneY, 80, 20, TEXT, true);
        drawLanguageHelp(graphics, mouseX, mouseY);
    }

    /** 没装翻译模组时语言行首那个问号。 */
    private boolean showLanguageHelp() {
        return !Lang.translationPackInstalled();
    }

    private int helpX() {
        return left + 5;
    }

    private void drawHelpButton(GuiGraphics graphics, int mouseX, int mouseY, int index) {
        int y = cellTop(index) + 2;
        boolean hot = Ui.inside(mouseX, mouseY, helpX(), y, 12, 12);
        boolean lit = hot || this.languageHelpPinned;
        graphics.fill(helpX(), y, helpX() + 12, y + 12, lit ? BTN_HOVER : BTN);
        graphics.renderOutline(helpX(), y, 12, 12, lit ? Ui.EDGE_SELECTED : BTN);
        graphics.drawCenteredString(this.font, "?", helpX() + 6, y + 2, TEXT);
    }

    /**
     * 问号的气泡：中英双语各一句，加下载地址。
     *
     * <p>不挂原版 tooltip——它会跑到鼠标底下挡住按钮，而且这里要的是固定几行、
     * 两种语言都露出来的说明，自绘反而干净。</p>
     */
    private void drawLanguageHelp(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!showLanguageHelp() || this.languageRow < 0) {
            return;
        }
        int index = this.languageRow;
        int rowTop = cellTop(index);
        int rowBottom = cellBottom(index);
        if (rowBottom <= this.view.top() || rowTop >= this.view.top() + this.view.viewH()) {
            return;
        }
        boolean hot = Ui.inside(mouseX, mouseY, helpX(), rowTop + 2, 12, 12);
        if (!hot && !this.languageHelpPinned) {
            return;
        }

        int boxW = Math.max(120, Math.min(340, panelW - PAD * 2));
        List<String> zh = wrapText(Lang.t("province.settings.language.help.zh"), boxW - PAD * 2);
        List<String> en = wrapText(Lang.t("province.settings.language.help.en"), boxW - PAD * 2);
        List<String> url = wrapText(TRANSLATION_URL, boxW - PAD * 2);
        int lineH = this.font.lineHeight + 1;
        int boxH = PAD * 2 + (zh.size() + en.size() + url.size()) * lineH - 1;

        int x = Math.max(4, left + panelW - PAD - boxW);
        int y = rowBottom + 3;
        if (y + boxH > this.height - 4) {
            // 下面放不下就翻到行上方，仍然贴着问号。
            y = Math.max(4, rowTop - 3 - boxH);
        }
        graphics.fill(x, y, x + boxW, y + boxH, TOOLTIP_BG);
        graphics.renderOutline(x, y, boxW, boxH, TOOLTIP_EDGE);

        int textY = y + PAD - 1;
        for (String line : zh) {
            graphics.drawString(this.font, line, x + PAD, textY, TEXT);
            textY += lineH;
        }
        for (String line : en) {
            graphics.drawString(this.font, line, x + PAD, textY, TEXT);
            textY += lineH;
        }
        for (String line : url) {
            graphics.drawString(this.font, line, x + PAD, textY, TEXT_ACCENT);
            textY += lineH;
        }
    }

    /** 按像素宽度硬折行，只够画说明气泡用；空格不在折行点上也不管。 */
    private List<String> wrapText(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.add(line.toString());
                line.setLength(0);
                continue;
            }
            if (line.length() > 0 && this.font.width(line.toString() + c) > maxWidth) {
                out.add(line.toString());
                line.setLength(0);
            }
            line.append(c);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (mx >= left + panelW / 2 - 40 && mx < left + panelW / 2 + 40 && my >= doneY && my < doneY + 20) {
            this.onClose();
            return true;
        }
        if (this.languageHelpPinned && this.languageRow >= 0
                && !(mx >= helpX() && mx < helpX() + 12
                && my >= cellTop(this.languageRow) && my < cellBottom(this.languageRow))) {
            this.languageHelpPinned = false;
        }
        if (this.view.overBar(mx, my)) {
            this.view.beginDrag(my);
            return true;
        }
        if (!this.view.containsY(my)) {
            return super.mouseClicked(event, doubleClick);
        }
        if (showLanguageHelp() && this.languageRow >= 0
                && my >= cellTop(this.languageRow) && my < cellBottom(this.languageRow)
                && mx >= helpX() && mx < helpX() + 12) {
            this.languageHelpPinned = !this.languageHelpPinned;
            return true;
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
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.sliding != null && event.button() == 0) {
            slide(this.sliding, event.x());
            return true;
        }
        if (this.view.dragging() && event.button() == 0) {
            this.view.dragTo(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.sliding != null) {
            this.sliding = null;
            return true;
        }
        if (this.view.dragging()) {
            this.view.endDrag();
            return true;
        }
        return super.mouseReleased(event);
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
        Minecraft.getInstance().setScreenAndShow(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
