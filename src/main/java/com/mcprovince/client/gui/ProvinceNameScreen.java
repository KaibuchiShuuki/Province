package com.mcprovince.client.gui;

import com.mcprovince.client.ProvinceEditor;
import com.mcprovince.core.AdminLevels;
import com.mcprovince.core.Lang;
import com.mcprovince.core.Province;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 给一块行政区划起名字：中文名、英文名、级别，外带换个颜色。 */
public class ProvinceNameScreen extends Screen {

    private static final int COLS = 6;
    private static final int ROWS = 4;
    private static final int PER_PAGE = COLS * ROWS;
    private static final int CELL_H = 18;
    private static final int CELL_GAP = 2;
    private static final int SWATCH = 16;

    private static final int COL_PANEL = 0xF0121218;
    private static final int COL_EDGE = 0xFF3C3C46;
    private static final int COL_TEXT = 0xFFFFFFFF;
    /** 自己加的级别名用这个边框色，跟内置的分开。 */
    private static final int COL_USER_EDGE = 0xFFFFC46B;

    private static final int TOOL_W = 18;
    private static final int TOOL_H = 16;

    /** 名字在格子里放不下时：先停一拍，再慢慢往左滚，滚完一整轮从头再来。 */
    private static final int MARQUEE_GAP = 24;
    private static final double MARQUEE_SPEED = 30.0;
    private static final long MARQUEE_HOLD_MS = 600L;

    /** 换色用的色块：我的世界默认的 16 种染料色。 */
    private static final int[] PALETTE = ProvinceEditor.DYE_PALETTE;

    private final Screen parent;
    /** 要改的是哪一块区划；{@link Province#NO_PARENT} 表示跟着编辑器当前选中的那块走。 */
    private final int targetId;
    private EditBox zhBox;
    private EditBox enBox;
    private String levelKey;
    private int color;
    private int page;
    /** 级别格子里的名字显示英文还是中文；只影响这一页怎么显示，不动任何数据。 */
    private boolean showEn;
    /** 底部那一行提示，比如「内置级别不能删」。点别的地方就清掉。 */
    private String hint = "";
    /** 正在滚动的是哪一格；鼠标挪开就归零，再回来重新计时。 */
    private String marqueeKey;
    private long marqueeStart;

    private int gridX;
    private int gridY;
    private int cellW;
    private int gridW;
    /** 两个字段标签的左边界（和输入框同一行，贴在框左边）。 */
    private int labelX;

    public ProvinceNameScreen(Screen parent) {
        this(parent, Province.NO_PARENT);
    }

    public ProvinceNameScreen(Screen parent, int targetId) {
        super(Component.literal(Lang.t("province.name.title")));
        this.parent = parent;
        this.targetId = targetId;
        Province target = targetOf();
        if (target != null) {
            this.levelKey = target.levelKey();
            this.color = target.color();
        } else {
            this.levelKey = ProvinceEditor.newLevelKey();
            this.color = ProvinceEditor.newColor();
        }
    }

    private Province targetOf() {
        return this.targetId == Province.NO_PARENT ? null : ProvinceEditor.store().byId(this.targetId);
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        Screen parent = minecraft.screen;
        minecraft.setScreen(new ProvinceNameScreen(parent, Province.NO_PARENT));
    }

    /** 从指定界面跳进来（树状图里刚新建完就直接来命名）。 */
    public static void open(Screen parent) {
        Minecraft.getInstance().setScreen(new ProvinceNameScreen(parent, Province.NO_PARENT));
    }

    /** 直接改指定的那一块区划，不用先在地图上选中它。 */
    public static void open(Screen parent, int provinceId) {
        Minecraft.getInstance().setScreen(new ProvinceNameScreen(parent, provinceId));
    }

    @Override
    protected void init() {
        Province target = targetOf();
        String zh = target == null ? ProvinceEditor.newNameZh() : target.nameZh();
        String en = target == null ? ProvinceEditor.newNameEn() : target.nameEn();

        // 标签和输入框同一行：标签宽度按这语言自己的两个字算，框宽跟着留够，两个框一样长。
        String nativeLabel = Lang.t("province.name.native");
        String englishLabel = Lang.t("province.name.english");
        int labelW = Math.max(this.font.width(nativeLabel), this.font.width(englishLabel));
        int gap = 8;
        int boxW = Math.max(80, Math.min(240, this.width - 40 - labelW - gap));
        int totalW = labelW + gap + boxW;
        this.labelX = (this.width - totalW) / 2;
        int boxX = this.labelX + labelW + gap;
        this.zhBox = new EditBox(this.font, boxX, 36, boxW, 18, Component.empty());
        Ui.takeOverText(this.zhBox);
        this.zhBox.setValue(zh);
        addRenderableWidget(this.zhBox);

        this.enBox = new EditBox(this.font, boxX, 60, boxW, 18, Component.empty());
        Ui.takeOverText(this.enBox);
        this.enBox.setValue(en);
        addRenderableWidget(this.enBox);

        this.gridW = Math.min(this.width - 60, 720);
        this.gridX = (this.width - this.gridW) / 2;
        this.gridY = 100;
        this.cellW = (this.gridW - (COLS - 1) * CELL_GAP) / COLS;
    }

    private static String textOf(EditBox box) {
        return box == null ? "" : box.getValue();
    }

    /** 级别格子里第 {@code slot} 格的左上角。渲染和点击共用这一处算法，免得两边各算一遍走偏。 */
    private int levelCellX(int slot) {
        return this.gridX + (slot % COLS) * (this.cellW + CELL_GAP);
    }

    private int levelCellY(int slot) {
        return this.gridY + (slot / COLS) * (CELL_H + CELL_GAP);
    }

    /** 翻页那一行；「添加 / 删除」两个按钮也在这行上。 */
    private int footerY() {
        return this.gridY + ROWS * (CELL_H + CELL_GAP) + 4;
    }

    private int colorY() {
        return footerY() + 24;
    }

    private int buttonY() {
        return colorY() + 24;
    }

    /** 增删两个小按钮贴在级别面板右缘内侧，窄窗口下也不会探到面板外面去。 */
    private int addX() {
        return delX() - TOOL_W - 4;
    }

    private int delX() {
        return this.gridX + this.gridW + 8 - TOOL_W - 4;
    }

    private static List<AdminLevels.Name> levels() {
        return AdminLevels.all();
    }

    private static int pageCount() {
        // 末尾留一格给「自定义…」：自建级别名的入口得看得见，不能只靠页脚那个小加号。
        return Math.max(1, (levels().size() + 1 + PER_PAGE - 1) / PER_PAGE);
    }

    /** 级别格子里显示的名字：切到英文写法则用英文名，缺英文名就用占位，免得格子空着。 */
    private String levelLabel(AdminLevels.Name name) {
        return this.showEn ? AdminLevels.labelEn(name) : AdminLevels.labelOf(name);
    }

    /**
     * 画一格级别名。放不下的时候（切到英文之后尤其常见）不缩排也不打省略号：
     * 平时只露出开头那一段、多出来的裁掉；鼠标停上去名字才慢慢地往左滚，滚完一整轮从头再来。
     *
     * @return 这一格正在滚动就返回它的键，否则 {@code null}
     */
    private String drawCellLabel(GuiGraphics graphics, AdminLevels.Name name, int cx, int cy,
            boolean hover) {
        String label = levelLabel(name);
        int textW = this.font.width(label);
        int baseline = cy + 5;
        if (textW <= this.cellW - 6) {
            graphics.drawCenteredString(this.font, label, cx + this.cellW / 2, baseline, COL_TEXT);
            return null;
        }
        if (!hover) {
            // 平时就露开头那一段，剩下的裁在格子外——想看全名把鼠标停上去。
            graphics.enableScissor(cx + 2, cy + 1, cx + this.cellW - 2, cy + CELL_H - 1);
            graphics.drawString(this.font, label, cx + 3, baseline, COL_TEXT);
            graphics.disableScissor();
            return null;
        }
        long now = System.currentTimeMillis();
        if (!name.key().equals(this.marqueeKey)) {
            this.marqueeKey = name.key();
            this.marqueeStart = now;
        }
        double span = textW + MARQUEE_GAP;
        double travelled = Math.max(0L, now - this.marqueeStart - MARQUEE_HOLD_MS) / 1000.0 * MARQUEE_SPEED;
        int offset = (int) Math.round(travelled % span);
        int x = cx + 3 - offset;
        graphics.enableScissor(cx + 2, cy + 1, cx + this.cellW - 2, cy + CELL_H - 1);
        graphics.drawString(this.font, label, x, baseline, COL_TEXT);
        // 后一份紧跟在前一份后面：滚出去的同时下一份从右边接上，看着是连着的。
        graphics.drawString(this.font, label, x + (int) Math.round(span), baseline, COL_TEXT);
        graphics.disableScissor();
        return name.key();
    }

    /** 「中 / EN」那枚小按钮：就贴在翻页组左边。 */
    private static int langX(int groupX) {
        return groupX - 4 - TOOL_W;
    }

    /** 「中 / EN」那枚小按钮上的字：点了会切到哪种写法。当前语言是英语时两边内容一样，按钮就没效果。 */
    private String toggleLabel() {
        return this.showEn ? Lang.current().shortLabel() : "EN";
    }

    /** 当前选中的那个级别名能不能删——只有自己加的能删。 */
    private boolean canDelete() {
        return AdminLevels.isUser(this.levelKey);
    }

    /** 刚加了一条级别名时，把它选中并翻到它所在的那一页。 */
    void selectLevel(String key) {
        this.levelKey = key;
        this.hint = "";
        List<AdminLevels.Name> all = levels();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).key().equals(key)) {
                this.page = i / PER_PAGE;
                return;
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, this.width, this.height, 0xB0000000);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        List<AdminLevels.Name> all = levels();
        int total = all.size();
        int from = this.page * PER_PAGE;
        int to = Math.min(total + 1, from + PER_PAGE);

        int footerY = footerY();
        int colorY = colorY();
        int buttonY = buttonY();
        int panelTop = 24;
        int panelBottom = buttonY + 22;

        graphics.fill(this.gridX - 8, panelTop, this.gridX + this.gridW + 8, panelBottom, COL_PANEL);
        graphics.renderOutline(this.gridX - 8, panelTop, this.gridW + 16, panelBottom - panelTop, COL_EDGE);

        int titleW = this.gridW + 16;
        Ui.scrollingText(graphics, this.font, "title:" + title(), title(), mouseX, mouseY,
                (this.width - titleW) / 2, 8, titleW, 12, COL_TEXT, true);
        // 标签和输入框同一行：标签宽度按这语言自己的字量过，框宽跟着让出来，不会叠上去。
        graphics.drawString(this.font, Lang.t("province.name.native"), this.labelX, this.zhBox.getY() + 5, COL_TEXT);
        graphics.drawString(this.font, Lang.t("province.name.english"), this.labelX, this.enBox.getY() + 5, COL_TEXT);
        Ui.boxValue(graphics, this.font, this.zhBox);
        Ui.boxValue(graphics, this.font, this.enBox);

        AdminLevels.Name current = AdminLevels.byKey(this.levelKey);
        String levelLine = Lang.t("province.name.level", AdminLevels.labelOf(current));
        Ui.scrollingText(graphics, this.font, "level:" + levelLine, levelLine, mouseX, mouseY,
                this.gridX, 88, this.gridW, 10, COL_TEXT, true);

        String rolling = null;
        for (int index = from; index < to; index++) {
            int slot = index - from;
            int cx = levelCellX(slot);
            int cy = levelCellY(slot);
            if (index == total) {
                // 最后一格留给「自定义」：以前只有页脚一个 18×16 的加号，很容易被当成装饰。
                boolean hover = Ui.inside(mouseX, mouseY, cx, cy, this.cellW, CELL_H);
                graphics.fill(cx, cy, cx + this.cellW, cy + CELL_H, hover ? Ui.BTN_HOVER : Ui.BTN);
                graphics.renderOutline(cx, cy, this.cellW, CELL_H, COL_USER_EDGE);
                Ui.scrollingText(graphics, this.font, "custom", Lang.t("province.name.custom"), mouseX, mouseY,
                        cx + 2, cy + 1, this.cellW - 4, CELL_H - 2, COL_USER_EDGE, false);
                continue;
            }
            AdminLevels.Name name = all.get(index);
            boolean selected = name.key().equals(this.levelKey);
            boolean hover = Ui.inside(mouseX, mouseY, cx, cy, this.cellW, CELL_H);
            graphics.fill(cx, cy, cx + this.cellW, cy + CELL_H,
                    selected ? Ui.BTN_SELECTED : hover ? Ui.BTN_HOVER : Ui.BTN);
            boolean user = AdminLevels.isUser(name.key());
            graphics.renderOutline(cx, cy, this.cellW, CELL_H,
                    selected ? 0xFF7FD4FF : user ? COL_USER_EDGE : COL_EDGE);
            String marquee = drawCellLabel(graphics, name, cx, cy, hover);
            if (marquee != null) {
                rolling = marquee;
            }
        }
        // 这一页没有谁在滚就归零：鼠标挪回来是重新开始，不是接着上次的进度。
        this.marqueeKey = rolling;

        int pageW = 18;
        int labelW = this.font.width(Lang.t("province.name.page", 8, 8)) + 20;
        int groupW = pageW * 2 + labelW + 8;
        int groupX = this.width / 2 - groupW / 2;
        Ui.button(graphics, this.font, mouseX, mouseY, langX(groupX), footerY, TOOL_W, TOOL_H,
                toggleLabel(), false);
        Ui.button(graphics, this.font, mouseX, mouseY, groupX, footerY, pageW, TOOL_H, "◀", false);
        graphics.drawCenteredString(this.font, Lang.t("province.name.page", this.page + 1, pageCount()),
                groupX + pageW + labelW / 2 + 4, footerY + 4, COL_TEXT);
        Ui.button(graphics, this.font, mouseX, mouseY, groupX + pageW + labelW + 8, footerY, pageW, TOOL_H, "▶", false);

        Ui.button(graphics, this.font, mouseX, mouseY, addX(), footerY, TOOL_W, TOOL_H, "+", false);
        boolean delHot = canDelete() && Ui.inside(mouseX, mouseY, delX(), footerY, TOOL_W, TOOL_H);
        graphics.fill(delX(), footerY, delX() + TOOL_W, footerY + TOOL_H, delHot ? Ui.BTN_HOVER : Ui.BTN);
        graphics.renderOutline(delX(), footerY, TOOL_W, TOOL_H, canDelete() ? COL_USER_EDGE : COL_EDGE);
        graphics.drawCenteredString(this.font, "-", delX() + TOOL_W / 2, footerY + 5, COL_TEXT);

        int step = colorStep();
        int cell = colorCell();
        int paletteX = paletteLeft();
        for (int i = 0; i < PALETTE.length; i++) {
            int cx = paletteX + i * step;
            boolean selected = PALETTE[i] == this.color;
            boolean hover = Ui.inside(mouseX, mouseY, cx, colorY, cell, cell);
            graphics.fill(cx, colorY, cx + cell, colorY + cell, 0xFF000000 | PALETTE[i]);
            graphics.renderOutline(cx, colorY, cell, cell,
                    selected ? 0xFFFFFFFF : hover ? 0xFFAAAAAA : COL_EDGE);
        }

        int okW = 80;
        Ui.button(graphics, this.font, mouseX, mouseY, this.width / 2 - okW - 4, buttonY, okW, 20, Lang.t("province.gui.ok"), false);
        Ui.button(graphics, this.font, mouseX, mouseY, this.width / 2 + 4, buttonY, okW, 20, Lang.t("province.gui.cancel"), false);

        String footNote = !this.hint.isEmpty() ? this.hint
                : Lang.t("province.name.foot", AdminLevels.noneLabel());
        Ui.scrollingText(graphics, this.font, "foot:" + footNote, footNote, mouseX, mouseY,
                10, buttonY + 26, this.width - 20, 10,
                this.hint.isEmpty() ? COL_TEXT : 0xFFFF8080, true);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        List<AdminLevels.Name> all = levels();
        int from = this.page * PER_PAGE;
        int to = Math.min(all.size() + 1, from + PER_PAGE);
        for (int index = from; index < to; index++) {
            int slot = index - from;
            int cx = levelCellX(slot);
            int cy = levelCellY(slot);
            if (Ui.inside(mouseX, mouseY, cx, cy, this.cellW, CELL_H)) {
                if (index == all.size()) {
                    ProvinceLevelScreen.open(this);
                    return true;
                }
                this.levelKey = all.get(index).key();
                this.hint = "";
                return true;
            }
        }

        int footerY = footerY();
        int colorY = colorY();
        int buttonY = buttonY();

        int pageW = 18;
        int labelW = this.font.width(Lang.t("province.name.page", 8, 8)) + 20;
        int groupW = pageW * 2 + labelW + 8;
        int groupX = this.width / 2 - groupW / 2;
        // 中 / EN：只切这一页显示的名字，不动任何数据。
        if (Ui.inside(mouseX, mouseY, langX(groupX), footerY, TOOL_W, TOOL_H)) {
            this.showEn = !this.showEn;
            this.hint = "";
            return true;
        }
        if (Ui.inside(mouseX, mouseY, groupX, footerY, pageW, TOOL_H)) {
            this.page = (this.page + pageCount() - 1) % pageCount();
            this.hint = "";
            return true;
        }
        if (Ui.inside(mouseX, mouseY, groupX + pageW + labelW + 8, footerY, pageW, TOOL_H)) {
            this.page = (this.page + 1) % pageCount();
            this.hint = "";
            return true;
        }

        if (Ui.inside(mouseX, mouseY, addX(), footerY, TOOL_W, TOOL_H)) {
            ProvinceLevelScreen.open(this);
            return true;
        }
        if (Ui.inside(mouseX, mouseY, delX(), footerY, TOOL_W, TOOL_H)) {
            if (canDelete()) {
                String label = AdminLevels.labelOf(AdminLevels.byKey(this.levelKey));
                AdminLevels.removeUser(this.levelKey);
                // 删的就是当前选中那个，那就退回默认，别让界面举着一个不存在的键。
                this.levelKey = AdminLevels.DEFAULT_KEY;
                this.page = Math.min(this.page, pageCount() - 1);
                this.hint = Lang.t("province.name.hint.deleted", label,
                        AdminLevels.labelOf(AdminLevels.byKey(AdminLevels.DEFAULT_KEY)));
            } else {
                this.hint = Lang.t("province.name.hint.builtin");
            }
            return true;
        }

        int step = colorStep();
        int cell = colorCell();
        int paletteX = paletteLeft();
        for (int i = 0; i < PALETTE.length; i++) {
            if (Ui.inside(mouseX, mouseY, paletteX + i * step, colorY, cell, cell)) {
                this.color = PALETTE[i];
                this.hint = "";
                return true;
            }
        }

        int okW = 80;
        if (Ui.inside(mouseX, mouseY, this.width / 2 - okW - 4, buttonY, okW, 20)) {
            apply();
            onClose();
            return true;
        }
        if (Ui.inside(mouseX, mouseY, this.width / 2 + 4, buttonY, okW, 20)) {
            onClose();
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            apply();
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void apply() {
        String zh = textOf(this.zhBox);
        String en = textOf(this.enBox);
        if (this.targetId == Province.NO_PARENT) {
            ProvinceEditor.setNames(zh, en);
            ProvinceEditor.setNewLevelKey(this.levelKey);
            ProvinceEditor.setNewColor(this.color);
            return;
        }
        ProvinceEditor.rename(this.targetId, zh, en, this.levelKey, this.color);
    }

    private String title() {
        Province target = targetOf();
        if (target == null) {
            return Lang.t("province.name.header");
        }
        // 名字允许留空，空着的时候标题里总得有个字，不然只剩一个斜杠。
        String zh = target.nameZh().isEmpty() ? Lang.t("province.name.unnamed") : target.nameZh();
        String en = target.nameEn().isEmpty() ? Lang.t("province.name.unnamed") : target.nameEn();
        return Lang.t("province.name.current", zh, en);
    }

    @Override
    public void onClose() {
        if (this.parent != null) {
            this.minecraft.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    /** 16 个色块横着排一行：宽屏一个格子 18px，窗口窄了就自己收，别溢出屏幕。 */
    private int colorStep() {
        return Math.max(9, Math.min(SWATCH + 2, (this.width - 20) / PALETTE.length));
    }

    private int colorCell() {
        return Math.max(6, colorStep() - 2);
    }

    private int paletteLeft() {
        int total = PALETTE.length * colorStep() - 2;
        return Math.max(4, this.width / 2 - total / 2);
    }
}
