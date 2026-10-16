package com.mcprovince.client;

import com.mcprovince.core.AdminLevels;
import com.mcprovince.core.Province;
import com.mcprovince.core.ProvinceStore;
import com.mcprovince.edit.EditHistory;
import com.mcprovince.edit.EditSession;
import com.mcprovince.edit.EditTool;
import net.minecraft.nbt.CompoundTag;

/**
 * 编辑模式的状态机：当前在画哪一档区划、能不能撤销。
 * 不碰渲染也不碰输入事件，那些交给上层。
 *
 * <p>流程是「先在区划树里新建 / 选中一块，再去地图上把它画出来」——所以任何时刻只有
 * 一档区划在被编辑，旁边那些区划根本轮不到被碰。</p>
 */
public final class ProvinceEditor {

    public static final int DEFAULT_COLOR = 0x4FA3FF;
    public static final int HISTORY_LIMIT = 64;

    /** 选区笔 / 选区擦在屏幕上的粗细范围（像素）。 */
    public static final int MIN_BRUSH_PIXELS = 2;
    public static final int MAX_BRUSH_PIXELS = 48;

    private static final EditHistory<CompoundTag> HISTORY = new EditHistory<>(HISTORY_LIMIT);

    private static boolean active;
    private static EditSession session;
    private static EditTool tool = EditTool.SELECT_BRUSH;
    /** 界面上选中的层级：只显示这一级。 */
    private static int viewTier = Province.MIN_TIER;
    private static int newColor = DEFAULT_COLOR;
    private static String newNameZh = "";
    private static String newNameEn = "";
    private static String newLevelKey = AdminLevels.DEFAULT_KEY;
    private static String status = "";
    private static boolean dragging;
    /** 地图当前每方块多少像素；笔宽按它换算，缩放时手感才一致。 */
    private static double pixelScale = 1.0;

    private ProvinceEditor() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
        if (!value) {
            dragging = false;
        }
    }

    public static void toggleActive() {
        setActive(!active);
    }

    public static ProvinceStore store() {
        return ProvinceClient.data().store();
    }

    /**
     * 选色用的调色板：我的世界那 16 种染料色，顺序也照染料来。
     * 命名界面按它排色块，新建时从这里随机挑一个代表色——满地图都是同一个蓝太难认了。
     */
    public static final int[] DYE_PALETTE = {
        0xFFFFFF, 0xF9801D, 0xC74EBD, 0x3AB3DA,
        0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
        0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA,
        0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21
    };

    public static int randomColor() {
        return DYE_PALETTE[(int) (Math.random() * DYE_PALETTE.length) % DYE_PALETTE.length];
    }

    /**
     * 给任意一块区划改名 / 换色 / 换级别。
     * 树状图里直接改，不必先选中它跑去地图上画一圈。
     */
    public static boolean rename(int provinceId, String nameZh, String nameEn, String levelKey, int color) {
        ProvinceStore store = store();
        Province target = store.byId(provinceId);
        if (target == null) {
            status = "找不到这块区划";
            return false;
        }
        CompoundTag before = ProvinceClient.data().snapshot();
        target.setNameZh(nameZh == null ? "" : nameZh.trim());
        target.setNameEn(nameEn == null ? "" : nameEn.trim());
        target.setLevelKey(levelKey);
        target.setColor(color & 0xFFFFFF);
        push(before, "改名 " + target.displayName(ProvinceSettings.get().nameFormat));
        ProvinceClient.data().markDirty();
        if (session != null && session.targetId() == provinceId) {
            setNames(target.nameZh(), target.nameEn());
            setNewLevelKey(target.levelKey());
            setNewColor(target.color());
        }
        status = "已更新「" + target.displayName(ProvinceSettings.get().nameFormat) + "」";
        return true;
    }

    // ————————————————— 会话 —————————————————

    public static EditSession session() {
        return session;
    }

    public static EditTool tool() {
        return tool;
    }

    public static void setTool(EditTool value) {
        tool = value;
        if (session != null) {
            session.setTool(value);
        }
    }

    /** 地图当前的缩放（每方块多少像素）。 */
    public static void setPixelScale(double pixelsPerBlock) {
        if (pixelsPerBlock > 1.0E-9) {
            pixelScale = pixelsPerBlock;
        }
    }

    /** 按屏幕像素算出来的笔宽（方块）。选区笔和选区擦共用这一个粗细。 */
    public static double brushWidthOnScreen() {
        double value = ProvinceSettings.get().brushPixels / pixelScale;
        return Math.max(EditSession.MIN_BRUSH_WIDTH, Math.min(EditSession.MAX_BRUSH_WIDTH, value));
    }

    /** 选区笔 / 选区擦在屏幕上的粗细（像素）。 */
    public static int brushPixels() {
        return ProvinceSettings.get().brushPixels;
    }

    /** 只影响选区笔和选区擦；直线 / 矩形 / 圆形 / 魔棒不理会这个粗细。 */
    public static void setBrushPixels(int pixels) {
        ProvinceSettings settings = ProvinceSettings.get();
        settings.brushPixels = Math.max(MIN_BRUSH_PIXELS, Math.min(MAX_BRUSH_PIXELS, pixels));
        if (session != null) {
            session.setBrushWidth(brushWidthOnScreen());
        }
        settings.save();
    }

    /** 当前选中的层级。 */
    public static int viewTier() {
        return viewTier;
    }

    /** 换一层：只显示这一级；换层时放弃手上没提交的会话。 */
    public static void setViewTier(int tier) {
        int clamped = Math.max(Province.MIN_TIER, Math.min(Province.MAX_TIER, tier));
        if (clamped == viewTier) {
            return;
        }
        viewTier = clamped;
        session = null;
        dragging = false;
        status = "已切到第 " + viewTier + " 级";
    }

    public static int newColor() {
        return newColor;
    }

    public static void setNewColor(int rgb) {
        newColor = rgb & 0xFFFFFF;
    }

    public static String newNameZh() {
        return newNameZh;
    }

    public static String newNameEn() {
        return newNameEn;
    }

    public static String newLevelKey() {
        return newLevelKey;
    }

    public static void setNewLevelKey(String key) {
        newLevelKey = key == null || key.isBlank() ? AdminLevels.DEFAULT_KEY : key;
    }

    public static void setNames(String zh, String en) {
        newNameZh = zh == null ? "" : zh.trim();
        newNameEn = en == null ? "" : en.trim();
    }

    public static String status() {
        return status;
    }

    public static void setStatus(String text) {
        status = text == null ? "" : text;
    }

    public static boolean isDragging() {
        return dragging;
    }

    // ————————————————— 显示状态与顺序 —————————————————

    /**
     * 按设置里的「显示第几级」重新铺一遍显示状态：只有这一级的区划默认是显示的。
     *
     * <p>载入存档、改级别设置之后都要走一次这里——在树状图里一块块勾了半天之后，
     * 改一下级别就能一键回到干净状态。</p>
     */
    public static void applyDefaultVisibility() {
        ProvinceStore store = store();
        if (store == null) {
            return;
        }
        int tier = ProvinceSettings.get().shownTier;
        for (Province province : store.allSorted()) {
            province.setVisible(province.tier() == tier);
        }
    }

    /** 改「地图上显示第几级」，顺带把所有手工勾选清掉、重新按级别铺一遍。 */
    public static void setShownTier(int tier) {
        ProvinceSettings settings = ProvinceSettings.get();
        int clamped = Math.max(Province.MIN_TIER, Math.min(Province.MAX_TIER, tier));
        if (settings.shownTier == clamped) {
            return;
        }
        settings.shownTier = clamped;
        settings.save();
        applyDefaultVisibility();
        ProvinceClient.data().markDirty();
        status = "地图上现在显示第 " + clamped + " 级";
    }

    /** 单独把一块区划翻开或藏起来；下次改「显示第几级」时会被重置。 */
    public static boolean toggleVisible(int provinceId) {
        ProvinceStore store = store();
        Province target = store == null ? null : store.byId(provinceId);
        if (target == null) {
            return false;
        }
        target.setVisible(!target.visible());
        ProvinceClient.data().markDirty();
        String name = target.displayName(ProvinceSettings.get().nameFormat);
        status = target.visible() ? "已让「" + name + "」显示出来" : "已把「" + name + "」藏起来";
        return target.visible();
    }

    /** 把一块区划在同级里挪个位置。纯树状图的显示顺序，地图上一点都不变。 */
    public static boolean moveProvince(int provinceId, int index) {
        ProvinceStore store = store();
        if (store == null || !store.reorder(provinceId, index)) {
            return false;
        }
        ProvinceClient.data().markDirty();
        return true;
    }

    /**
     * 在树状图里把一块区划的下级收起来 / 铺开。
     *
     * <p>纯粹是树状图的样子：这一块自己在图上照旧显示，地图上不受任何影响。状态存在
     * 设置文件里，收一次记一次，下次打开树状图还是收着的。</p>
     */
    public static void toggleCollapsed(int provinceId) {
        ProvinceSettings settings = ProvinceSettings.get();
        if (!settings.collapsed.remove(provinceId)) {
            settings.collapsed.add(provinceId);
        }
        settings.save();
    }

    /** 这一块的下级在树状图里是不是收着的。 */
    public static boolean isCollapsed(int provinceId) {
        return ProvinceSettings.get().collapsed.contains(provinceId);
    }

    // ————————————————— 选中与新建 —————————————————

    /** 选中一档区划开始画它的范围（树界面点一行、新建完都走这里）。 */
    public static boolean startEdit(int provinceId) {
        ProvinceStore store = store();
        Province target = store.byId(provinceId);
        if (target == null) {
            status = "找不到这块区划";
            return false;
        }
        EditSession fresh = new EditSession(store);
        if (!fresh.beginEdit(provinceId)) {
            status = "找不到这块区划";
            return false;
        }
        session = fresh;
        session.setTool(tool);
        session.setBrushWidth(brushWidthOnScreen());
        viewTier = target.tier();
        newColor = target.color();
        newNameZh = target.nameZh();
        newNameEn = target.nameEn();
        newLevelKey = target.levelKey();
        dragging = false;
        active = true;
        status = "正在画「" + target.displayName(ProvinceSettings.get().nameFormat) + "」的范围";
        return true;
    }

    /**
     * 新建一档区划并立刻选中它——先落一块空的，再去地图上画范围。
     *
     * @param parentId 父区划 id；{@link Province#NO_PARENT} 表示这是第 1 级
     */
    public static Province createAndSelect(int parentId, String nameZh, String nameEn, String levelKey, int color) {
        ProvinceStore store = store();
        CompoundTag before = ProvinceClient.data().snapshot();
        Province created;
        try {
            created = store.create(parentId, null, nameZh, nameEn, levelKey, color);
        } catch (RuntimeException e) {
            status = "新建失败：" + e.getMessage();
            return null;
        }
        // 名字可能留空，历史记录和状态栏都得有个说法，不能写成「已新建「」」。
        String label = created.displayName(ProvinceSettings.get().nameFormat);
        push(before, "新建 " + (label.isEmpty() ? "区划" : label));
        // 新块默认显不显示，跟其它块一个规矩：设置里正在看第几级，它就显不显。
        // 以前构造函数一律给 true，于是新建的块明明不在当前级别上却照样打着勾。
        created.setVisible(created.tier() == ProvinceSettings.get().shownTier);
        ProvinceClient.data().markDirty();
        setNames(created.nameZh(), created.nameEn());
        setNewLevelKey(created.levelKey());
        setNewColor(created.color());
        startEdit(created.id());
        status = (label.isEmpty() ? "已新建一块还没起名的区划" : "已新建「" + label + "」")
                + "，去地图上画它的范围";
        return created;
    }

    /** 把当前选中的那块区划改成一块空白（重画）。 */
    public static void clearSelection() {
        if (session == null) {
            status = "先在「区划树」里选一块区划";
            return;
        }
        session.clearSelection();
        status = "已清空选区，重新画吧";
    }

    public static void cancelSession() {
        if (session == null) {
            status = "现在没有在编辑的区划";
            return;
        }
        session.resetToOriginal();
        dragging = false;
        status = "已放弃这一轮改动";
    }

    // ————————————————— 绘制 —————————————————

    public static void onPress(double worldX, double worldZ) {
        onPress(worldX, worldZ, false);
    }

    /**
     * @param constrain 按住 Shift：直线走横竖、矩形与圆形补成等边
     */
    public static void onPress(double worldX, double worldZ, boolean constrain) {
        if (session == null) {
            status = "先在「区划树」里新建或选中一块区划，再来地图上画";
            return;
        }
        session.setTool(tool);
        session.setBrushWidth(brushWidthOnScreen());
        session.setConstrain(constrain);
        if (tool.isClickOnly()) {
            double filled = session.applyMagic(worldX, worldZ);
            if (filled < 0.0) {
                status = "这里什么都圈不出来——换一处空地点点看，或者用选区笔勾一个闭合的圈";
            } else if (filled <= 0.0) {
                status = "这一块已经在选区里了";
            } else {
                status = "魔棒选中了 " + Math.round(filled) + " 平方格";
            }
            return;
        }
        session.onPress(worldX, worldZ);
        dragging = true;
    }

    public static void onDrag(double worldX, double worldZ) {
        onDrag(worldX, worldZ, false);
    }

    /** @param constrain 拖拽途中按住 Shift 就会一直是真，松手前随时可以改主意 */
    public static void onDrag(double worldX, double worldZ, boolean constrain) {
        if (session == null || !dragging) {
            return;
        }
        session.setConstrain(constrain);
        session.onDrag(worldX, worldZ);
    }

    public static void onRelease() {
        if (session == null) {
            return;
        }
        if (dragging) {
            session.onRelease();
            dragging = false;
        }
        refreshStatus();
    }

    private static void refreshStatus() {
        if (session == null) {
            return;
        }
        String line = session.statusLine();
        if (line != null && !line.isBlank()) {
            status = line;
        }
    }

    // ————————————————— 提交 —————————————————

    /** 把当前选区写进数据。返回是否真的改了东西。 */
    public static boolean commit() {
        if (session == null) {
            status = "先选一块区划再来保存";
            return false;
        }
        ProvinceStore store = store();
        Province target = store.byId(session.targetId());
        if (target == null) {
            status = "目标区划已经不存在了";
            return false;
        }
        if (!session.hasChanges()) {
            status = "还没有画出任何改动";
            return false;
        }
        CompoundTag before = ProvinceClient.data().snapshot();
        target.setNameZh(newNameZh);
        target.setNameEn(newNameEn);
        target.setLevelKey(newLevelKey);
        target.setColor(newColor);
        double[] delta = store.applyDelta(target, session.addedSinceOriginal(), session.removedSinceOriginal());
        push(before, "修改 " + target.displayName(ProvinceSettings.get().nameFormat));
        ProvinceClient.data().markDirty();
        session.beginEdit(target.id());
        session.setBrushWidth(brushWidthOnScreen());
        dragging = false;
        status = "已保存「" + target.displayName(ProvinceSettings.get().nameFormat) + "」：+"
                + Math.round(delta[0]) + " / -" + Math.round(delta[1]) + " 平方格";
        return true;
    }

    /** 删掉一块区划（连同它的下级）。 */
    public static boolean delete(int provinceId) {
        ProvinceStore store = store();
        Province target = store.byId(provinceId);
        if (target == null) {
            return false;
        }
        CompoundTag before = ProvinceClient.data().snapshot();
        int removed = store.delete(provinceId).size();
        push(before, "删除 " + target.displayName(ProvinceSettings.get().nameFormat));
        ProvinceClient.data().markDirty();
        status = "已删除 " + removed + " 块区划";
        if (session != null && store.byId(session.targetId()) == null) {
            session = null;
        }
        dragging = false;
        return true;
    }

    private static void push(CompoundTag before, String label) {
        HISTORY.push(new EditHistory.Entry<>(label, ProvinceClient.data().snapshot(), before));
    }

    // ————————————————— 撤销 / 重做 —————————————————

    public static boolean undo() {
        EditHistory.Entry<CompoundTag> entry = HISTORY.popUndo();
        if (entry == null) {
            status = "没有可以撤销的操作";
            return false;
        }
        ProvinceClient.data().applySnapshot(entry.backward());
        session = null;
        dragging = false;
        status = "已撤销：" + entry.label();
        return true;
    }

    public static boolean canUndo() {
        return HISTORY.canUndo();
    }

    public static void onWorldChanged() {
        HISTORY.clear();
        session = null;
        dragging = false;
        active = false;
        status = "";
        // 换存档 / 换维度之后，显示状态按「显示第几级」重新铺一遍。
        applyDefaultVisibility();
    }
}
