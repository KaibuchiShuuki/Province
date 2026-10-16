package com.mcprovince.client.gui;

import com.mcprovince.client.ProvinceEditor;
import com.mcprovince.client.ProvinceSettings;
import com.mcprovince.core.AdminLevels;
import com.mcprovince.core.AreaFormat;
import com.mcprovince.core.Province;
import com.mcprovince.core.ProvinceStore;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 树状预览：谁挂在谁下面一目了然，顺手还能在这里删掉一块区划。
 *
 * <p>整棵树按深度优先铺平成一页一页的表，缩进就是层级。点一行就跳到那一级并关掉本页，
 * 点右边的 ✕ 就连同下级一起删掉（删完立刻重建表格）。</p>
 *
 * <p>表格本身是滚动视口：滚轮和右侧拖动条都能滚，条目少时滚动条自己藏着。</p>
 */
public class ProvinceTreeScreen extends Screen {

    private static final int ROW_H = 18;
    private static final int ROW_GAP = 2;
    private static final int ROW_STEP = ROW_H + ROW_GAP;
    private static final int PAD = 10;
    private static final int SWATCH = 10;
    private static final int DEL_W = 16;
    private static final int SUB_W = 16;
    private static final int SET_W = 16;
    /** 三条杠（拖拽排序）按钮的宽度。 */
    private static final int GRIP_W = 16;
    /** 行首那个「收起 / 铺开下级」的小三角的宽度。 */
    private static final int TRI_W = 12;
    /** 显示勾选框的宽度。 */
    private static final int CHECK_W = 14;
    private static final int NEW_W = 110;
    private static final int INDENT = 14;
    private static final int MAX_INDENT = 8;

    private static final int COL_PANEL = 0xF0121218;
    private static final int COL_EDGE = 0xFF3C3C46;
    private static final int COL_ROW_HOVER = 0x30FFFFFF;
    private static final int COL_DEL = 0x60A03340;
    private static final int COL_DEL_HOVER = 0xE0D05060;
    private static final int COL_SUB = 0x60306040;
    private static final int COL_SUB_HOVER = 0xE060C080;
    private static final int COL_SET = 0x60404070;
    private static final int COL_SET_HOVER = 0xE080A0E0;
    private static final int COL_GRIP = 0x60404040;
    private static final int COL_GRIP_HOVER = 0xE0B0B0B0;
    private static final int COL_CHECK_ON = 0xE040A0A0;
    private static final int COL_CHECK_OFF = 0x60202028;
    private static final int COL_TRI_HOVER = 0xE090A0D0;
    /** 拖动中的那一行。 */
    private static final int COL_DRAG_ROW = 0x50FFD166;
    private static final int COL_BAR_TRACK = 0x40FFFFFF;
    private static final int COL_BAR_THUMB = 0xA0A8B6C4;
    private static final int COL_BAR_THUMB_HOT = 0xE0C8D8E8;
    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_TEXT_DIM = 0xFF9AA0AA;

    private final Screen parent;
    private List<Row> rows = List.of();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    /** 列表滚动视口。 */
    private final ScrollViewport view = new ScrollViewport();

    private record Row(Province province, int depth, boolean hasChildren, boolean collapsed) {
    }

    public ProvinceTreeScreen(Screen parent) {
        super(Component.literal("方块政区 · 区划结构"));
        this.parent = parent;
    }

    /** 从当前打开着的界面跳进树状图。 */
    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        Screen parent = minecraft.screen;
        minecraft.setScreen(new ProvinceTreeScreen(parent));
    }

    /** 正被 ≡ 拎着的那一行（rows 里的下标）；-1 表示手上没东西。 */
    private int dragIndex = -1;

    private static void collect(ProvinceStore store, int parentId, int depth, List<Row> out) {
        // 顺序直接用 store 里存的那个——拖动 ≡ 改的就是它，这里再按 id 排一遍就白拖了。
        List<Province> children = store.childrenOf(parentId);
        for (Province child : children) {
            boolean hasChildren = !store.childrenOf(child.id()).isEmpty();
            // 收起来只是不在树状图里铺下级：这一块自己照旧列着，地图上也照旧。
            boolean collapsed = hasChildren && ProvinceEditor.isCollapsed(child.id());
            out.add(new Row(child, depth, hasChildren, collapsed));
            if (!collapsed) {
                collect(store, child.id(), depth + 1, out);
            }
        }
    }

    private static List<Row> buildRows() {
        List<Row> out = new ArrayList<>();
        ProvinceStore store = ProvinceEditor.store();
        if (store != null) {
            collect(store, Province.NO_PARENT, 0, out);
        }
        return out;
    }

    @Override
    protected void init() {
        this.rows = buildRows();
        this.panelW = Math.min(this.width - 24, 620);
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = 28;
        int contentH = this.rows.size() * ROW_STEP;
        // 面板尽量长到装下整棵树，装不下就滚动（底部留出页脚那一行）。
        int room = Math.max(ROW_STEP + PAD * 2, this.height - this.panelY - 34);
        this.panelH = Math.min(room, Math.max(ROW_STEP + PAD * 2, PAD * 2 + contentH));
        this.view.setStep(ROW_STEP);
        this.view.layout(barX(), this.panelY + PAD, Math.max(ROW_STEP, this.panelH - PAD * 2), contentH);
    }

    private int rowY(int index) {
        return this.view.top() + index * ROW_STEP - this.view.scroll();
    }

    private int rowX(Row row) {
        return this.panelX + PAD + Math.min(MAX_INDENT, row.depth()) * INDENT;
    }

    private int rowW(Row row) {
        return this.panelX + this.panelW - PAD - rowX(row);
    }

    /** 「≡」拖动把手：贴在面板最右。按住它上下拖，就能把这一条在同级里挪位置。 */
    private int gripX() {
        return this.panelX + this.panelW - PAD - GRIP_W;
    }

    /** 「✕」按钮：连下级一起删掉。 */
    private int delX() {
        return gripX() - 4 - DEL_W;
    }

    /** 「＋子区」按钮的横坐标，紧挨在 ✕ 左边。 */
    private int subX() {
        return delX() - SUB_W - 4;
    }

    /** 「⚙」按钮：改名 / 换色 / 换级别。 */
    private int setX() {
        return subX() - SET_W - 4;
    }

    /** 勾选框：这一块区划在地图上显不显示。行首第一件东西，位置一直没动过。 */
    private int checkX(Row row) {
        return rowX(row);
    }

    /** 勾选框右边那个小三角：有下级的块按它就能把下级收起来 / 铺开。 */
    private int triX(Row row) {
        return rowX(row) + CHECK_W + 2;
    }

    /** 名字和色块从勾选框和小三角右边开始（有没有三角都留着位置，名字才对得齐）。 */
    private int contentX(Row row) {
        return rowX(row) + CHECK_W + 2 + TRI_W + 2;
    }

    /** 底部一排控件的横坐标：关闭 ＋新建。 */
    private int[] footerLayout() {
        int closeW = 56;
        int groupW = closeW + 8 + NEW_W;
        int groupX = Math.max(8, this.width / 2 - groupW / 2);
        return new int[] { groupX, closeW, groupX + closeW + 8 };
    }

    private int footerY() {
        return this.panelY + this.panelH + 6;
    }

    // ————————————————— 滚动条 —————————————————

    /** 拖动条的横坐标，贴着面板右缘。 */
    private int barX() {
        return this.panelX + this.panelW - 7;
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, this.width, this.height, 0xC0000000);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.rows = buildRows();
        this.view.setContentH(this.rows.size() * ROW_STEP);

        ProvinceSettings settings = ProvinceSettings.get();
        graphics.drawCenteredString(this.font, this.title.getString(), this.width / 2, 10, COL_TEXT);
        graphics.fill(this.panelX, this.panelY, this.panelX + this.panelW, this.panelY + this.panelH, COL_PANEL);
        graphics.renderOutline(this.panelX, this.panelY, this.panelW, this.panelH, COL_EDGE);

        if (this.rows.isEmpty()) {
            graphics.drawCenteredString(this.font, "还没有任何区划，先去世界地图上画一块", this.width / 2,
                    this.panelY + 18, COL_TEXT_DIM);
        }

        int dX = delX();
        int sX = subX();
        int cX = setX();
        int gX = gripX();
        graphics.enableScissor(this.panelX + 2, this.view.top(), this.panelX + this.panelW - 2,
                this.view.top() + this.view.viewH());
        for (int index = 0; index < this.rows.size(); index++) {
            Row row = this.rows.get(index);
            Province province = row.province();
            int y = rowY(index);
            if (y + ROW_H <= this.view.top() || y >= this.view.top() + this.view.viewH()) {
                continue;
            }
            int x = rowX(row);
            int w = rowW(row);
            boolean overRow = Ui.inside(mouseX, mouseY, x, y, w, ROW_H);
            if (overRow) {
                graphics.fill(x, y, x + w, y + ROW_H, COL_ROW_HOVER);
            }
            // 正被拎着的那一条整行点亮，一眼看得出手里拖的是哪块。
            if (index == this.dragIndex) {
                graphics.fill(x, y, x + w, y + ROW_H, COL_DRAG_ROW);
            }

            // 勾选框后面支个小三角，按一下就把下级收起来——只动树状图的样子。
            int tX = triX(row);
            if (row.hasChildren()) {
                // 光画那个小三角，不给它套方框、也不铺底色：行里本来就有勾选框和色块，
                // 再多一个框，看着比色块还大，喧宾夺主。
                boolean overTri = Ui.inside(mouseX, mouseY, tX, y + 1, TRI_W, ROW_H - 2);
                graphics.drawCenteredString(this.font, row.collapsed() ? "▶" : "▼", tX + TRI_W / 2, y + 5,
                        overTri ? COL_TRI_HOVER : COL_TEXT);
            }

            int ckX = checkX(row);
            graphics.fill(ckX, y + 1, ckX + CHECK_W, y + ROW_H - 1,
                    province.visible() ? COL_CHECK_ON : COL_CHECK_OFF);
            graphics.renderOutline(ckX, y + 1, CHECK_W, ROW_H - 2,
                    province.visible() ? 0xFF7FD4FF : COL_EDGE);
            if (province.visible()) {
                graphics.drawCenteredString(this.font, "✓", ckX + CHECK_W / 2, y + 5, COL_TEXT);
            }

            int bx = contentX(row);
            graphics.fill(bx, y + 4, bx + SWATCH, y + 4 + SWATCH, 0xFF000000 | province.color());
            graphics.renderOutline(bx, y + 4, SWATCH, SWATCH, COL_EDGE);

            // 没勾上的那块名字压暗，跟显示中的一眼分开。
            graphics.drawString(this.font, province.displayName(settings.nameFormat), bx + SWATCH + 6, y + 5,
                    province.visible() ? COL_TEXT : COL_TEXT_DIM);
            String meta = "L" + province.tier() + " · " + AdminLevels.labelOf(AdminLevels.byKey(province.levelKey()))
                    + " · " + AreaFormat.format(province.shape().measure(), settings.areaInKilo);
            int metaX = Math.max(bx + SWATCH + 48, cX - 8 - this.font.width(meta));
            graphics.drawString(this.font, meta, metaX, y + 5, COL_TEXT_DIM);

            boolean overSet = Ui.inside(mouseX, mouseY, cX, y + 1, SET_W, ROW_H - 2);
            graphics.fill(cX, y + 1, cX + SET_W, y + ROW_H - 1, overSet ? COL_SET_HOVER : COL_SET);
            graphics.renderOutline(cX, y + 1, SET_W, ROW_H - 2, COL_EDGE);
            graphics.drawCenteredString(this.font, "⚙", cX + SET_W / 2, y + 5, COL_TEXT);

            boolean overSub = Ui.inside(mouseX, mouseY, sX, y + 1, SUB_W, ROW_H - 2);
            graphics.fill(sX, y + 1, sX + SUB_W, y + ROW_H - 1, overSub ? COL_SUB_HOVER : COL_SUB);
            graphics.renderOutline(sX, y + 1, SUB_W, ROW_H - 2, COL_EDGE);
            graphics.drawCenteredString(this.font, "+", sX + SUB_W / 2, y + 5, COL_TEXT);

            boolean overDelete = Ui.inside(mouseX, mouseY, dX, y + 1, DEL_W, ROW_H - 2);
            graphics.fill(dX, y + 1, dX + DEL_W, y + ROW_H - 1, overDelete ? COL_DEL_HOVER : COL_DEL);
            graphics.renderOutline(dX, y + 1, DEL_W, ROW_H - 2, COL_EDGE);
            graphics.drawCenteredString(this.font, "✕", dX + DEL_W / 2, y + 5, COL_TEXT);

            boolean overGrip = Ui.inside(mouseX, mouseY, gX, y + 1, GRIP_W, ROW_H - 2);
            graphics.fill(gX, y + 1, gX + GRIP_W, y + ROW_H - 1, overGrip ? COL_GRIP_HOVER : COL_GRIP);
            graphics.renderOutline(gX, y + 1, GRIP_W, ROW_H - 2, COL_EDGE);
            graphics.drawCenteredString(this.font, "≡", gX + GRIP_W / 2, y + 5, COL_TEXT);
        }
        graphics.disableScissor();

        this.view.draw(graphics, mouseX, mouseY, COL_BAR_TRACK, COL_BAR_THUMB, COL_BAR_THUMB_HOT);

        int[] footer = footerLayout();
        int fy = footerY();
        Ui.button(graphics, this.font, mouseX, mouseY, footer[0], fy, footer[1], 16, "关闭", false);
        Ui.button(graphics, this.font, mouseX, mouseY, footer[2], fy, NEW_W, 16, "＋ 新建顶级区划", true);
        graphics.drawString(this.font, "共 " + this.rows.size() + " 块", footer[2] + NEW_W + 12, fy + 4, COL_TEXT_DIM);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return super.mouseClicked(mx, my, button);
        }
        double mouseX = mx;
        double mouseY = my;

        if (this.view.overBar(mouseX, mouseY)) {
            this.view.beginDrag(mouseY);
            return true;
        }

        int dX = delX();
        if (this.view.containsY(mouseY)) {
            for (int index = 0; index < this.rows.size(); index++) {
                Row row = this.rows.get(index);
                int y = rowY(index);
                if (mouseY < y || mouseY >= y + ROW_H) {
                    continue;
                }
                if (row.hasChildren() && Ui.inside(mouseX, mouseY, triX(row), y + 1, TRI_W, ROW_H - 2)) {
                    // 收起 / 铺开下级：纯树状图的外观，地图上什么都不动。改一次记一次。
                    ProvinceEditor.toggleCollapsed(row.province().id());
                    this.rows = buildRows();
                    this.view.setContentH(this.rows.size() * ROW_STEP);
                    return true;
                }
                if (Ui.inside(mouseX, mouseY, checkX(row), y + 1, CHECK_W, ROW_H - 2)) {
                    // 单独决定这一块显不显示；下次在设置里换显示级别时会整批重算回去。
                    ProvinceEditor.toggleVisible(row.province().id());
                    return true;
                }
                if (Ui.inside(mouseX, mouseY, gripX(), y + 1, GRIP_W, ROW_H - 2)) {
                    // 拎起来：按住 ≡ 上下拖，把这一条在同级里挪个位置。
                    this.dragIndex = index;
                    return true;
                }
                if (Ui.inside(mouseX, mouseY, dX, y + 1, DEL_W, ROW_H - 2)) {
                    ProvinceEditor.delete(row.province().id());
                    this.rows = buildRows();
                    this.view.setContentH(this.rows.size() * ROW_STEP);
                    return true;
                }
                if (Ui.inside(mouseX, mouseY, subX(), y + 1, SUB_W, ROW_H - 2)) {
                    createUnder(row.province());
                    return true;
                }
                if (Ui.inside(mouseX, mouseY, setX(), y + 1, SET_W, ROW_H - 2)) {
                    // 直接改这一块：改名、换色、换级别。
                    ProvinceNameScreen.open(this, row.province().id());
                    return true;
                }
                int x = rowX(row);
                if (Ui.inside(mouseX, mouseY, x, y, rowW(row), ROW_H)) {
                    // 选中这一块，回到地图上画它的范围。
                    ProvinceEditor.startEdit(row.province().id());
                    onClose();
                    return true;
                }
                break;
            }
        }

        int[] footer = footerLayout();
        int fy = footerY();
        if (Ui.inside(mouseX, mouseY, footer[0], fy, footer[1], 16)) {
            onClose();
            return true;
        }
        if (Ui.inside(mouseX, mouseY, footer[2], fy, NEW_W, 16)) {
            createUnder(null);
            return true;
        }

        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
        if (this.dragIndex >= 0 && button == 0) {
            dragTo(y);
            return true;
        }
        if (this.view.dragging() && button == 0) {
            this.view.dragTo(y);
            return true;
        }
        return super.mouseDragged(x, y, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (this.dragIndex >= 0) {
            this.dragIndex = -1;
            return true;
        }
        if (this.view.dragging()) {
            this.view.endDrag();
            return true;
        }
        return super.mouseReleased(x, y, button);
    }

    /**
     * 把手里那条拖到鼠标所在的位置。
     *
     * <p>只管同级之间的先后：跨到别的父区划底下是不认的——那等于换了个爹，是另一码事。
     * 换完位置要重建行表，因为行是从 store 里现铺出来的，连子区一起跟着挪。</p>
     */
    private void dragTo(double mouseY) {
        if (this.dragIndex < 0 || this.dragIndex >= this.rows.size()) {
            return;
        }
        int target = rowIndexAt(mouseY);
        if (target < 0 || target == this.dragIndex) {
            return;
        }
        Row from = this.rows.get(this.dragIndex);
        Row to = this.rows.get(target);
        if (from.province().parentId() != to.province().parentId()) {
            return;
        }
        int order = siblingIndexOf(to.province());
        if (order < 0 || !ProvinceEditor.moveProvince(from.province().id(), order)) {
            return;
        }
        this.rows = buildRows();
        this.view.setContentH(this.rows.size() * ROW_STEP);
        // 拎着的那条换了个下标，跟着更新，手感才不会断。
        this.dragIndex = indexOfProvince(from.province().id());
    }

    /** 鼠标落在第几行上；两行之间的空隙也算最近的那行，不在表里就是 -1。 */
    private int rowIndexAt(double mouseY) {
        for (int index = 0; index < this.rows.size(); index++) {
            int y = rowY(index);
            if (mouseY >= y && mouseY < y + ROW_H) {
                return index;
            }
        }
        return -1;
    }

    private int indexOfProvince(int provinceId) {
        for (int index = 0; index < this.rows.size(); index++) {
            if (this.rows.get(index).province().id() == provinceId) {
                return index;
            }
        }
        return -1;
    }

    /** 某一块在它自己那批同级里的序号——拖动排序报给 store 的就是这个数。 */
    private static int siblingIndexOf(Province province) {
        ProvinceStore store = ProvinceEditor.store();
        if (store == null) {
            return -1;
        }
        List<Province> siblings = store.childrenOf(province.parentId());
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).id() == province.id()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (this.view.scrollBy(scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollY);
    }

    /** 在某个父区划下面新建一块，然后直接进命名界面。传 {@code null} 就是新建顶级。 */
    private void createUnder(Province parent) {
        if (parent != null && parent.tier() >= Province.MAX_TIER) {
            return;
        }
        int parentId = parent == null ? Province.NO_PARENT : parent.id();
        // 随机给一个代表色，免得满地图都是同一个蓝；名字一律留空，等玩家自己填（填不填都行）。
        Province created = ProvinceEditor.createAndSelect(parentId, "", "", AdminLevels.DEFAULT_KEY,
                ProvinceEditor.randomColor());
        if (created == null) {
            return;
        }
        this.rows = buildRows();
        this.view.setContentH(this.rows.size() * ROW_STEP);
        // 直接改刚建出来的这一块：取名和换色要马上生效，不能等回到树状图再补。
        ProvinceNameScreen.open(this, created.id());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
