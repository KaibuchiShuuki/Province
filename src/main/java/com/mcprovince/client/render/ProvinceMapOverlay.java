package com.mcprovince.client.render;

import com.mcprovince.client.MapExporter;
import com.mcprovince.client.ProvinceClient;
import com.mcprovince.client.ProvinceEditor;
import com.mcprovince.client.ProvinceSettings;
import com.mcprovince.client.SerialCorner;
import com.mcprovince.client.gui.ProvinceSettingsScreen;
import com.mcprovince.client.gui.ProvinceTreeScreen;
import com.mcprovince.core.Province;
import com.mcprovince.core.ProvinceStore;
import com.mcprovince.core.VectorShape;
import com.mcprovince.edit.EditSession;
import com.mcprovince.edit.EditTool;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 世界地图上的行政区划浮层：平时只叠一层矢量色块，按 R 之后多出一个编辑面板。
 * 地图本体仍然交给 Xaero 画，这里只负责往上面写字画画。
 */
public final class ProvinceMapOverlay {

    private static final int PANEL_X = 4;
    private static final int PANEL_Y = 4;
    private static final int PANEL_W = 176;
    private static final int PAD = 4;
    private static final int ROW = 16;
    private static final int GAP = 2;
    private static final int STATUS_LINES = 3;

    private static final int COL_PANEL = 0xC8101016;
    private static final int COL_EDGE = 0xFF3C3C46;
    private static final int COL_BTN = 0xC0222230;
    private static final int COL_BTN_HOVER = 0xE0343448;
    private static final int COL_BTN_SEL = 0xE0285C8C;
    private static final int COL_BTN_OFF = 0x80181A20;
    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_TEXT_DIM = 0xFF9AA0AA;
    private static final int COL_TEXT_OFF = 0xFF66666E;


    private static final int COLOR_WORKING = 0xFFFFD166;
    private static final int COLOR_ADD = 0xFF66E08A;
    private static final int COLOR_REMOVE = 0xFFE06666;
    private static final int COLOR_PARENT = 0x809BE7FF;
    private static final int FILL_WORKING = 0x5548C8FF;
    private static final int FILL_ADD = 0x6666E08A;
    private static final int FILL_REMOVE = 0x66E06666;
    /** 正在编辑时，父级以半透明铺一层；同级邻居则是另一种更淡的色。 */
    private static final int FILL_PARENT = 0x3A9BE7FF;
    private static final int FILL_SIBLING = 0x30FF9AA2;

    /** 编辑预览单帧最多描多少条线段，和正式边界一样，超了就只靠填充看形状。 */
    private static final int PREVIEW_STROKE_LIMIT = 4_000;

    /** 序号标记用的带圈数字，最多列到 20 块。 */
    private static final String SERIAL_CHARS = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳";
    private static final int SERIAL_LIMIT = 20;
    /** 角落注释面板的行高、内边距与底色。 */
    private static final int LEGEND_LINE_H = 10;
    private static final int LEGEND_PAD = 5;
    private static final int COL_LEGEND_BG = 0xC0101016;

    private ProvinceMapOverlay() {
    }

    // ————————————————— 绘制 —————————————————

    public static void render(GuiGraphicsExtractor graphics, Font font, int guiWidth, int guiHeight,
            double mouseX, double mouseY, double cameraX, double cameraZ, double scale, double screenScale,
            double windowWidth, double windowHeight) {
        ProvinceSettings settings = ProvinceSettings.get();
        ProvinceStore store = ProvinceClient.data().store();
        XaeroMapProjection projection = new XaeroMapProjection(cameraX, cameraZ, scale, screenScale, windowWidth, windowHeight);
        boolean editing = ProvinceEditor.isActive();
        // 笔宽按「每方块多少像素」换算，缩放地图时手感才一致。
        ProvinceEditor.setPixelScale(projection.pixelsPerBlock());

        if (settings.worldMapEnabled || editing) {
            // 编辑模式下只画当前选中的那一级：选 L2 就只看得到 L2，免得画面糊成一团。
            ProvinceRenderer.draw(graphics, font, store, settings, projection,
                    0.0, 0.0, guiWidth, guiHeight, projection.pixelsPerBlock(),
                    editing ? ProvinceEditor.viewTier() : 0);
            // 名字放不下的那几块改成序号（渲染那边已经把它们记下来了）。
            drawSerialMarks(graphics, font, store, settings, projection, guiWidth, guiHeight);
        }

        if (editing) {
            sampleDrag(mouseX, mouseY, projection);
            drawEditPreview(graphics, projection, guiWidth, guiHeight);
            drawPanel(graphics, font, (int) mouseX, (int) mouseY);
        }

        // 导出是同步阻塞的：先让「正在导出…」上屏一帧，下一帧才真的开跑。
        pumpExport();
        drawExportNotice(graphics, font, guiWidth, guiHeight);
    }

    /**
     * 名称在图上实在放不下的那些区划，缩成一个序号画在自己身上，并在屏幕角落列出对照表。
     *
     * <p>「放不下」是渲染那边如实记下来的：一块区划的名牌一个都没画出来时，
     * {@code nameDrawn()} 就是 false（见 {@code ProvinceRenderer.drawNames}）。只有在开着
     * 「显示区划名称」的时候才谈得上放不下——名称压根不画的时候，给每块地都挂个序号
     * 反而是添乱。</p>
     */
    private static void drawSerialMarks(GuiGraphicsExtractor graphics, Font font, ProvinceStore store,
            ProvinceSettings settings, XaeroMapProjection projection, int guiWidth, int guiHeight) {
        if (!settings.serialMarks || !settings.showNames) {
            return;
        }
        List<Province> marks = new ArrayList<>();
        List<int[]> spots = new ArrayList<>();
        // 跟 ProvinceRenderer 同一套过滤：编辑的时候屏幕上只有正在改的那一级，
        // 别的块压根没被画过，nameDrawn 自然是 false——不能因为这就给它们发号。
        int onlyTier = ProvinceEditor.isActive() ? ProvinceEditor.viewTier() : 0;
        for (Province province : store.allSorted()) {
            if (marks.size() >= SERIAL_LIMIT) {
                break;
            }
            if (province.shape().isEmpty() || province.nameDrawn()) {
                continue;
            }
            if (onlyTier > 0) {
                if (province.tier() != onlyTier) {
                    continue;
                }
            } else if (!province.visible()) {
                continue;
            }
            double[] anchor = anchorInside(province);
            if (anchor == null) {
                continue;
            }
            int screenX = (int) Math.round(projection.screenX(anchor[0], anchor[1]));
            int screenY = (int) Math.round(projection.screenY(anchor[0], anchor[1]));
            // 只有落在画面里的地块才占号：不然角落那张对照表会被屏幕外、根本看不见的
            // 地名塞满，序号也白白用光。
            if (screenX < 0 || screenY < 0 || screenX > guiWidth || screenY > guiHeight) {
                continue;
            }
            marks.add(province);
            spots.add(new int[] { screenX, screenY });
        }
        if (marks.isEmpty()) {
            return;
        }
        List<String> legend = new ArrayList<>(marks.size());
        for (int i = 0; i < marks.size(); i++) {
            String mark = String.valueOf(SERIAL_CHARS.charAt(i));
            legend.add(mark + " " + marks.get(i).displayName(settings.nameFormat));
            int screenX = spots.get(i)[0];
            int screenY = spots.get(i)[1];
            // 不套框、也不垫影子：图上别的地名长什么样，序号就长什么样。
            int boxH = font.lineHeight + 2;
            int boxY = screenY - boxH / 2;
            graphics.centeredText(font, mark, screenX, boxY + 2, COL_TEXT);
        }
        drawSerialLegend(graphics, font, legend, settings, guiWidth, guiHeight);
    }

    /** 序号画在区划内部的哪个点上；找不到落点就返回 {@code null}。 */
    private static double[] anchorInside(Province province) {
        VectorShape shape = province.shape();
        VectorShape.Bounds bounds = shape.bounds();
        if (bounds == null) {
            return null;
        }
        // 包围盒中心在里面就用它，省掉一次网格采样。
        if (shape.contains(bounds.centerX(), bounds.centerZ())) {
            return new double[] { bounds.centerX(), bounds.centerZ() };
        }
        return ProvinceRenderer.nameAnchorForText(shape, bounds, 0.0, 0.0, shape);
    }

    /** 屏幕角落那张「① 是哪块地」的对照表；位置由设置里的角落决定。 */
    private static void drawSerialLegend(GuiGraphicsExtractor graphics, Font font, List<String> lines,
            ProvinceSettings settings, int guiWidth, int guiHeight) {
        int textW = 0;
        for (String line : lines) {
            textW = Math.max(textW, font.width(line));
        }
        int boxW = textW + LEGEND_PAD * 2;
        int boxH = lines.size() * LEGEND_LINE_H + LEGEND_PAD * 2;
        SerialCorner corner = settings.serialCorner;
        int x = corner == SerialCorner.TOP_LEFT || corner == SerialCorner.BOTTOM_LEFT ? 4 : guiWidth - boxW - 4;
        int y = corner == SerialCorner.TOP_LEFT || corner == SerialCorner.TOP_RIGHT ? 4 : guiHeight - boxH - 4;
        // 编辑面板就钉在左上角，注释别压上去。
        if (x == 4 && y == 4 && ProvinceEditor.isActive()) {
            y = panelBottom() + 4;
        }
        graphics.fill(x, y, x + boxW, y + boxH, COL_LEGEND_BG);
        graphics.outline(x, y, boxW, boxH, COL_EDGE);
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(font, lines.get(i), x + LEGEND_PAD, y + LEGEND_PAD + i * LEGEND_LINE_H, COL_TEXT);
        }
    }

    private static void drawEditPreview(GuiGraphicsExtractor graphics, XaeroMapProjection projection,
            double clipW, double clipH) {
        EditSession session = ProvinceEditor.session();
        if (session == null) {
            return;
        }

        // 父级与同级邻居先铺一层淡色：一眼就能看出「画得出去的地方」到哪儿为止。
        Province parent = session.parentProvince();
        if (parent != null) {
            ProvinceRenderer.fillShape(graphics::fill, projection, parent.shape(), FILL_PARENT, 0.0, 0.0, clipW, clipH);
            ProvinceRenderer.strokeShape(graphics::fill, projection, parent.shape(), COLOR_PARENT, 1.0, PREVIEW_STROKE_LIMIT);
        }
        for (Province sibling : ProvinceEditor.store().atTier(session.tier())) {
            if (sibling.id() == session.targetId()) {
                continue;
            }
            ProvinceRenderer.fillShape(graphics::fill, projection, sibling.shape(), FILL_SIBLING, 0.0, 0.0, clipW, clipH);
        }

        VectorShape working = session.working();
        if (working != null && !working.isEmpty()) {
            ProvinceRenderer.fillShape(graphics::fill, projection, working, FILL_WORKING, 0.0, 0.0, clipW, clipH);
            ProvinceRenderer.strokeShape(graphics::fill, projection, working, COLOR_WORKING, 1.0, PREVIEW_STROKE_LIMIT);
        }
        VectorShape preview = session.preview();
        if (preview != null && !preview.isEmpty()) {
            EditTool tool = session.tool();
            boolean removing = tool != null && tool.isErasing();
            ProvinceRenderer.fillShape(graphics::fill, projection, preview, removing ? FILL_REMOVE : FILL_ADD,
                    0.0, 0.0, clipW, clipH);
            ProvinceRenderer.strokeShape(graphics::fill, projection, preview,
                    removing ? COLOR_REMOVE : COLOR_ADD, 1.0, PREVIEW_STROKE_LIMIT);
        }
    }

    private static void drawPanel(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        List<Widget> widgets = buildWidgets();
        int statusTop = statusTop(widgets);
        int panelBottom = statusTop + STATUS_LINES * 10 + PAD;

        graphics.fill(PANEL_X, PANEL_Y, PANEL_X + PANEL_W, panelBottom, COL_PANEL);
        graphics.outline(PANEL_X, PANEL_Y, PANEL_W, panelBottom - PANEL_Y, COL_EDGE);

        for (Widget widget : widgets) {
            drawWidget(graphics, font, widget, mouseX, mouseY);
        }

        int textY = statusTop;
        for (String line : wrap(ProvinceEditor.status(), font, PANEL_W - 2 * PAD, STATUS_LINES)) {
            graphics.text(font, line, PANEL_X + PAD, textY, COL_TEXT_DIM);
            textY += 10;
        }
    }

    private static void drawWidget(GuiGraphicsExtractor graphics, Font font, Widget widget, int mouseX, int mouseY) {
        boolean hover = widget.action != null && widget.enabled
                && mouseX >= widget.x && mouseX < widget.x + widget.w
                && mouseY >= widget.y && mouseY < widget.y + widget.h;
        boolean selected = widget.selected != null && widget.selected.getAsBoolean();

        int background;
        if (!widget.enabled) {
            background = COL_BTN_OFF;
        } else if (selected) {
            background = COL_BTN_SEL;
        } else if (hover) {
            background = COL_BTN_HOVER;
        } else {
            background = COL_BTN;
        }
        graphics.fill(widget.x, widget.y, widget.x + widget.w, widget.y + widget.h, background);
        graphics.outline(widget.x, widget.y, widget.w, widget.h,
                selected ? 0xFF7FD4FF : COL_EDGE);

        String label = widget.label.get();
        if (label == null || label.isEmpty()) {
            return;
        }
        int color = !widget.enabled ? COL_TEXT_OFF : COL_TEXT;
        graphics.centeredText(font, label, widget.x + widget.w / 2, widget.y + (widget.h - 8) / 2, color);
    }

    // ————————————————— 控件布局 —————————————————

    private static List<Widget> buildWidgets() {
        List<Widget> out = new ArrayList<>();
        int x = PANEL_X + PAD;
        int innerW = PANEL_W - 2 * PAD;
        int colW = (innerW - GAP) / 2;
        int y = PANEL_Y + PAD;

        EditTool[] tools = EditTool.values();
        for (int i = 0; i < tools.length; i++) {
            EditTool value = tools[i];
            int cx = x + (i % 2) * (colW + GAP);
            int cy = y + (i / 2) * (ROW + GAP);
            out.add(new Widget(cx, cy, colW, ROW,
                    value::label, () -> ProvinceEditor.setTool(value),
                    () -> ProvinceEditor.tool() == value, true));
        }
        y += ((tools.length + 1) / 2) * (ROW + GAP);

        out.add(new Widget(x, y, innerW, ROW, () -> "层级 L" + ProvinceEditor.viewTier() + "：只看、只改这一级",
                null, null, false));
        y += ROW + GAP;

        int tierW = (innerW - 4 * GAP) / 5;
        for (int tier = Province.MIN_TIER; tier <= Province.MAX_TIER; tier++) {
            int value = tier;
            int cx = x + (tier - 1) * (tierW + GAP);
            out.add(new Widget(cx, y, tierW, ROW,
                    () -> "L" + value, () -> ProvinceEditor.setViewTier(value),
                    () -> ProvinceEditor.viewTier() == value, true));
        }
        y += ROW + GAP;

        // 粗细条：一格一像素，点哪格就多粗。只作用于选区笔和选区擦。
        out.add(new Widget(x, y, innerW, ROW,
                () -> "笔粗 " + ProvinceEditor.brushPixels() + " px（只影响选区笔 / 选区擦）",
                null, null, false));
        y += ROW + GAP;

        int cells = ProvinceEditor.MAX_BRUSH_PIXELS - ProvinceEditor.MIN_BRUSH_PIXELS + 1;
        int cellW = Math.max(2, innerW / cells);
        for (int i = 0; i < cells; i++) {
            int value = ProvinceEditor.MIN_BRUSH_PIXELS + i;
            out.add(new Widget(x + i * cellW, y, Math.max(1, cellW - 1), ROW, () -> "",
                    () -> ProvinceEditor.setBrushPixels(value),
                    () -> ProvinceEditor.brushPixels() >= value, true));
        }
        y += ROW + GAP;

        out.add(new Widget(x, y, colW, ROW, () -> "保存",
                ProvinceEditor::commit, null, ProvinceEditor.session() != null));
        out.add(new Widget(x + colW + GAP, y, colW, ROW, () -> "取消",
                ProvinceEditor::cancelSession, null, ProvinceEditor.session() != null));
        y += ROW + GAP;

        out.add(new Widget(x, y, innerW, ROW, () -> "区划树（看结构 / 删区划）",
                ProvinceTreeScreen::open, null, true));
        y += ROW + GAP;

        out.add(new Widget(x, y, colW, ROW, () -> "导出当前地图",
                () -> MapExporter.requestExport(MapExporter.ExportMode.SCREEN), null, true));
        out.add(new Widget(x + colW + GAP, y, colW, ROW, () -> "导出世界全图",
                () -> MapExporter.requestExport(MapExporter.ExportMode.WORLD), null, true));
        y += ROW + GAP;

        out.add(new Widget(x, y, colW, ROW, () -> "设置",
                ProvinceMapOverlay::openSettings, null, true));
        out.add(new Widget(x + colW + GAP, y, colW, ROW, () -> "退出编辑",
                () -> ProvinceEditor.setActive(false), null, true));

        return out;
    }

    private static void openSettings() {
        ProvinceSettingsScreen.open(Minecraft.getInstance().gui.screen());
    }

    /** 每帧推一下导出请求；跑完的那一次顺手把结果写进编辑面板的状态栏。 */
    private static void pumpExport() {
        String result = MapExporter.tickExport(Minecraft.getInstance());
        if (result != null) {
            ProvinceEditor.setStatus("导出地图：" + result);
        }
    }

    /** 屏幕底部居中挂一条提示：正在导出 / 导出成功 / 导出失败。 */
    private static void drawExportNotice(GuiGraphicsExtractor graphics, Font font, int guiWidth, int guiHeight) {
        String text = MapExporter.notice();
        if (text.isEmpty()) {
            return;
        }
        int kind = MapExporter.noticeKind();
        int width = font.width(text) + 18;
        int height = 16;
        int x = Math.max(4, (guiWidth - width) / 2);
        int y = Math.max(4, guiHeight - height - 12);
        int background = switch (kind) {
            case MapExporter.NOTICE_OK -> 0xD01B3A26;
            case MapExporter.NOTICE_ERROR -> 0xD05A1E24;
            default -> 0xD01A2733;
        };
        int edge = switch (kind) {
            case MapExporter.NOTICE_OK -> 0xFF66E08A;
            case MapExporter.NOTICE_ERROR -> 0xFFE06666;
            default -> 0xFF7FD4FF;
        };
        graphics.fill(x, y, x + width, y + height, background);
        graphics.outline(x, y, width, height, edge);
        graphics.centeredText(font, text, guiWidth / 2, y + 4, COL_TEXT);
    }

    // ————————————————— 输入 —————————————————

    public static boolean onMousePressed(double mouseX, double mouseY, int button, int guiWidth, int guiHeight,
            double cameraX, double cameraZ, double scale, double screenScale,
            double windowWidth, double windowHeight) {
        if (button != 0) {
            return false;
        }
        // 编辑模式只剩快捷键一个入口（默认 P，见 ProvinceKeys），地图上不放按钮了。
        if (!ProvinceEditor.isActive()) {
            return false;
        }
        for (Widget widget : buildWidgets()) {
            if (widget.action != null && widget.enabled
                    && mouseX >= widget.x && mouseX < widget.x + widget.w
                    && mouseY >= widget.y && mouseY < widget.y + widget.h) {
                widget.action.run();
                return true;
            }
        }
        XaeroMapProjection projection = new XaeroMapProjection(cameraX, cameraZ, scale, screenScale, windowWidth, windowHeight);
        ProvinceEditor.onPress(projection.worldX(mouseX, mouseY), projection.worldZ(mouseX, mouseY),
                shiftDown());
        return true;
    }

    public static boolean onMouseReleased(int button) {
        if (button != 0 || !ProvinceEditor.isActive()) {
            return false;
        }
        ProvinceEditor.onRelease();
        return true;
    }

    private static void sampleDrag(double mouseX, double mouseY, XaeroMapProjection projection) {
        if (!ProvinceEditor.isDragging()) {
            return;
        }
        if (isOverPanel(mouseX, mouseY)) {
            return;
        }
        ProvinceEditor.onDrag(projection.worldX(mouseX, mouseY), projection.worldZ(mouseX, mouseY),
                shiftDown());
    }

    /**
     * 左右 Shift 有没有按住。26.2 的 {@code Screen} 已经没有 {@code hasShiftDown()} 这个静态方法了，
     * 直接问 GLFW 的状态最省事 —— 拖拽途中没有键盘事件可循。
     */
    private static boolean shiftDown() {
        Window window = Minecraft.getInstance().getWindow();
        return window != null
                && (InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                        || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT));
    }

    private static boolean isOverPanel(double mouseX, double mouseY) {
        return mouseX >= PANEL_X && mouseX < PANEL_X + PANEL_W
                && mouseY >= PANEL_Y && mouseY < panelBottom();
    }

    /**
     * 控件区下面那行状态文字的顶边。面板高度只有这一个来源：以前点击判定里写死了
     * {@code PANEL_Y + 320}，面板一旦增删一行，判定就会和画出来的面板对不上。
     */
    private static int statusTop(List<Widget> widgets) {
        int bottom = PANEL_Y + PAD;
        for (Widget widget : widgets) {
            bottom = Math.max(bottom, widget.y + widget.h + GAP);
        }
        return bottom + 2;
    }

    /** 面板真实的下边缘（含状态文字区）。 */
    private static int panelBottom() {
        return statusTop(buildWidgets()) + STATUS_LINES * 10 + PAD;
    }

    // ————————————————— 小工具 —————————————————

    private static List<String> wrap(String text, Font font, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        String rest = text;
        while (!rest.isEmpty() && lines.size() < maxLines) {
            if (font.width(rest) <= maxWidth) {
                lines.add(rest);
                return lines;
            }
            int cut = rest.length();
            while (cut > 1 && font.width(rest.substring(0, cut)) > maxWidth) {
                cut--;
            }
            lines.add(rest.substring(0, cut));
            rest = rest.substring(cut);
        }
        if (!rest.isEmpty() && !lines.isEmpty()) {
            int last = lines.size() - 1;
            String tail = lines.get(last);
            while (!tail.isEmpty() && font.width(tail + "…") > maxWidth) {
                tail = tail.substring(0, tail.length() - 1);
            }
            lines.set(last, tail + "…");
        }
        return lines;
    }

    private static final class Widget {
        final int x;
        final int y;
        final int w;
        final int h;
        final Supplier<String> label;
        final Runnable action;
        final BooleanSupplier selected;
        final boolean enabled;

        Widget(int x, int y, int w, int h, Supplier<String> label, Runnable action,
                BooleanSupplier selected, boolean enabled) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.label = label;
            this.action = action;
            this.selected = selected;
            this.enabled = enabled;
        }
    }
}
