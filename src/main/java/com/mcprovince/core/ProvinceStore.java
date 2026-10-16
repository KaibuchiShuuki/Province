package com.mcprovince.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个存档里的全部行政区划，外加按层级分桶的空间索引。
 *
 * <p>形状是矢量的 {@link VectorShape}，没有方块粒度可依赖，所以索引改成
 * 「{@value #GRID} 方块见方的大格」：每个层级一张 {@code 网格 -> 候选区划} 表，
 * 区划按自己的包围盒登记进覆盖到的格子。查询某个坐标属于谁时先缩小到一格，
 * 再对候选做精确的 {@link VectorShape#contains}。比网格还大的巨型区划走全局桶。</p>
 */
public final class ProvinceStore {

    /** 空间索引的格子边长（方块）。 */
    private static final double GRID = 256.0;

    /** 一个区划最多登记进多少格；超了就退化成全局桶，免得铺满整个索引。 */
    private static final long MAX_INDEX_CELLS = 4096L;

    private final Map<Integer, Province> byId = new LinkedHashMap<>();
    private final Map<Integer, Map<Long, List<Province>>> tierIndex = new HashMap<>();
    private final Map<Integer, List<Province>> tierGlobal = new HashMap<>();
    private int nextId = 1;

    /** {@link #allSorted()} 的缓存。增删区划时置空；渲染每帧都要拿这份列表。 */
    private List<Province> sortedCache;

    /**
     * 全部区划，按层级升序、同级按 id 升序。
     *
     * <p>返回的是内部缓存本身：渲染每帧都会调用，现场 new 一个 ArrayList 再排序太浪费。
     * 调用方只读，不要改动它，也不要把它存下来当快照（增删区划后这份列表会被换掉）。</p>
     */
    public List<Province> allSorted() {
        if (this.sortedCache == null) {
            List<Province> list = new ArrayList<>(this.byId.values());
            list.sort((a, b) -> a.tier() != b.tier() ? Integer.compare(a.tier(), b.tier()) : Integer.compare(a.id(), b.id()));
            this.sortedCache = list;
        }
        return this.sortedCache;
    }

    public boolean isEmpty() {
        return this.byId.isEmpty();
    }

    public int size() {
        return this.byId.size();
    }

    public Province byId(int id) {
        return this.byId.get(id);
    }

    public int nextId() {
        return this.nextId;
    }

    /** 反序列化时用：把 id 计数器推到已知最大值之后。 */
    public void bumpNextId(int id) {
        if (id >= this.nextId) {
            this.nextId = id + 1;
        }
    }

    public List<Province> atTier(int tier) {
        List<Province> out = new ArrayList<>();
        for (Province p : this.byId.values()) {
            if (p.tier() == tier) {
                out.add(p);
            }
        }
        return out;
    }

    /** 挂在 {@code parentId} 下面的区划，按显示顺序（{@link Province#order()}）排。 */
    public List<Province> childrenOf(int parentId) {
        List<Province> out = new ArrayList<>();
        for (Province p : this.byId.values()) {
            if (p.parentId() == parentId) {
                out.add(p);
            }
        }
        sortByOrder(out);
        return out;
    }

    /**
     * 同级之间按显示顺序排；顺序一样（老档案没存过这个字段）就退回 id。
     *
     * <p>只影响树状图里谁在上面，跟地图上的几何、层级、渲染顺序都不搭界。</p>
     */
    private static void sortByOrder(List<Province> list) {
        list.sort((a, b) -> a.order() != b.order()
                ? Integer.compare(a.order(), b.order())
                : Integer.compare(a.id(), b.id()));
    }

    /**
     * 把一块区划在同级里挪到第 {@code index} 位。
     *
     * <p>挪完把同级的 order 重新压成 0..n-1，免得来回拖几次之后数字越滚越大。
     * 越界会被夹回区间里；本来就停在原位就什么都不做。</p>
     *
     * @return 真的挪动了才返回 {@code true}
     */
    public boolean reorder(int id, int index) {
        Province target = this.byId.get(id);
        if (target == null) {
            return false;
        }
        List<Province> siblings = childrenOf(target.parentId());
        int from = siblings.indexOf(target);
        if (from < 0) {
            return false;
        }
        int to = Math.max(0, Math.min(siblings.size() - 1, index));
        if (from == to) {
            return false;
        }
        siblings.remove(from);
        siblings.add(to, target);
        for (int i = 0; i < siblings.size(); i++) {
            siblings.get(i).setOrder(i);
        }
        return true;
    }

    /** 新块排在同级末尾：取同级最大的 order 再加一。 */
    private int nextOrderUnder(int parentId) {
        int max = -1;
        for (Province p : this.byId.values()) {
            if (p.parentId() == parentId) {
                max = Math.max(max, p.order());
            }
        }
        return max + 1;
    }

    // ——— 空间查询 ———

    private static long gridKey(int gx, int gz) {
        return ((long) gx << 32) | (gz & 0xFFFFFFFFL);
    }

    private static int gridOf(double value) {
        return (int) Math.floor(value / GRID);
    }

    private static Province firstHit(List<Province> candidates, double x, double z, int excludeId) {
        if (candidates == null) {
            return null;
        }
        for (int i = 0; i < candidates.size(); i++) {
            Province p = candidates.get(i);
            if (p.id() != excludeId && p.shape().contains(x, z)) {
                return p;
            }
        }
        return null;
    }

    /** 找出第 {@code tier} 级里包含坐标 (x,z) 的区划，没有就返回 null。 */
    public Province findAt(int tier, double x, double z) {
        return lookup(tier, x, z, -1);
    }

    /** 先查巨型区划的全局桶，再查坐标落进的那一格。 */
    private Province lookup(int tier, double x, double z, int excludeId) {
        Province hit = firstHit(this.tierGlobal.get(tier), x, z, excludeId);
        if (hit != null) {
            return hit;
        }
        Map<Long, List<Province>> index = this.tierIndex.get(tier);
        if (index == null) {
            return null;
        }
        return firstHit(index.get(gridKey(gridOf(x), gridOf(z))), x, z, excludeId);
    }

    /** 从最细的一级往上找，返回第一个包含该坐标的区划。 */
    public Province findFinestAt(double x, double z) {
        for (int tier = Province.MAX_TIER; tier >= Province.MIN_TIER; tier--) {
            Province p = findAt(tier, x, z);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    /** 该坐标是否已经被任何区划占用。 */
    public boolean isOccupied(double x, double z) {
        return findFinestAt(x, z) != null;
    }

    /** 第 {@code tier} 级里，除了 {@code excludeId} 之外，还有别的区划盖住这个坐标吗。 */
    public boolean isOwnedByOther(int tier, int excludeId, double x, double z) {
        return lookup(tier, x, z, excludeId) != null;
    }

    // ——— 修改 ———

    /** 按父区划和同级占用情况把一块区域裁成「合法可加入」的部分。 */
    public VectorShape clipFor(int parentId, int tier, int selfId, VectorShape region) {
        VectorShape out = region == null ? VectorShape.empty() : region.copy();
        if (out.isEmpty()) {
            return out;
        }
        VectorShape bound = parentBound(parentId);
        if (bound != null) {
            out.intersect(bound);
            if (out.isEmpty()) {
                return out;
            }
        }
        VectorShape siblings = siblingUnion(tier, selfId);
        if (siblings != null) {
            out.subtract(siblings);
        }
        return out;
    }

    /**
     * 本级最多能画到哪儿：父区划的形状。
     *
     * <p>第 1 级没有父区划，也就没有外边界，返回 {@code null}。</p>
     */
    public VectorShape parentBound(int parentId) {
        Province parent = parentId == Province.NO_PARENT ? null : this.byId.get(parentId);
        return parent == null ? null : parent.shape().copy();
    }

    /**
     * 第 {@code tier} 级里已经被别的区划占掉的空间，合成一块。
     *
     * @param selfId 正在编辑的那块自己，要从占用里排除
     * @return {@code null} 表示同级里再没有别的区划
     */
    public VectorShape siblingUnion(int tier, int selfId) {
        VectorShape out = null;
        for (Province other : this.byId.values()) {
            if (other.tier() != tier || other.id() == selfId) {
                continue;
            }
            if (out == null) {
                out = other.shape().copy();
            } else {
                out.add(other.shape());
            }
        }
        return out;
    }

    /**
     * 新建一档区划。
     *
     * @param parentId 父区划 id；{@link Province#NO_PARENT} 表示这是第 1 级
     * @param shape    初始形状，会按父区划与同级占用自动裁剪，可为 null
     */
    public Province create(int parentId, VectorShape shape, String nameZh, String nameEn, String levelKey, int color) {
        int tier;
        if (parentId == Province.NO_PARENT) {
            tier = Province.MIN_TIER;
        } else {
            Province parent = this.byId.get(parentId);
            if (parent == null) {
                throw new IllegalArgumentException("父区划不存在: " + parentId);
            }
            tier = parent.tier() + 1;
        }
        if (tier > Province.MAX_TIER) {
            throw new IllegalStateException("行政区划最多 " + Province.MAX_TIER + " 级");
        }
        Province province = new Province(this.nextId++, parentId, tier, nameZh, nameEn, levelKey, color);
        // 新块排在同级末尾，树状图里一眼就能找到刚建的那块。
        province.setOrder(nextOrderUnder(parentId));
        if (shape != null && !shape.isEmpty()) {
            // 传进来的形状可能带着小数（曲线、手画），先吸附到方块格线再裁剪，
            // 这样存档里每一块地的边界都正好落在方块上。
            province.shape().copyFrom(clipFor(parentId, tier, province.id(), BlockSnap.snap(shape)));
        }
        this.byId.put(province.id(), province);
        index(province);
        return province;
    }

    /** 删除一档区划，连同它的所有下级。 */
    public List<Province> delete(int id) {
        List<Province> removed = new ArrayList<>();
        collectSubtree(id, removed);
        for (Province p : removed) {
            this.byId.remove(p.id());
            unindex(p);
        }
        return removed;
    }

    private void collectSubtree(int id, List<Province> out) {
        Province p = this.byId.get(id);
        if (p == null) {
            return;
        }
        out.add(p);
        for (Province child : childrenOf(id)) {
            collectSubtree(child.id(), out);
        }
    }

    /**
     * 在已有区划上应用一次「加一块 / 减一块」的修改（扩张、缩小、擦除都走这里）。
     *
     * @return {实际新增面积, 实际删除面积}，单位是平方方块
     */
    public double[] applyDelta(Province province, VectorShape added, VectorShape removed) {
        double addedArea = 0.0;
        double removedArea = 0.0;
        if (removed != null && !removed.isEmpty()) {
            VectorShape cut = province.shape().copy();
            cut.intersect(removed);
            if (!cut.isEmpty()) {
                removedArea = cut.measure();
                province.shape().subtract(removed);
                reindex(province);
                clipChildren(province);
            }
        }
        if (added != null && !added.isEmpty()) {
            VectorShape gain = clipFor(province.parentId(), province.tier(), province.id(), added);
            gain.subtract(province.shape());
            if (!gain.isEmpty()) {
                addedArea = gain.measure();
                province.shape().add(gain);
                reindex(province);
            }
        }
        return new double[] { addedArea, removedArea };
    }

    /**
     * 把子孙裁回父级的新范围，逐层往下。
     *
     * <p>父级被擦掉一块之后，原本落在那块地上的下级区划不会自己缩回来 —— 它们记的
     * 还是自己那份轮廓，于是就会探出父级边界。这里每裁完一层就往下递归一层，
     * 保证任何一级都待在它爹的范围里。</p>
     */
    private void clipChildren(Province parent) {
        for (Province child : childrenOf(parent.id())) {
            VectorShape before = child.shape().copy();
            child.shape().intersect(parent.shape());
            if (Math.abs(before.measure() - child.shape().measure()) > 1.0E-9) {
                reindex(child);
            }
            clipChildren(child);
        }
    }

    // ——— 索引维护 ———

    /**
     * 包围盒覆盖到的格子范围 {@code {gx0, gz0, gx1, gz1}}。
     *
     * @return {@code null} 表示格子太多（超过 {@link #MAX_INDEX_CELLS}），该走全局桶
     */
    private static int[] cellRange(VectorShape.Bounds b) {
        int gx0 = gridOf(b.minX());
        int gx1 = gridOf(b.maxX());
        int gz0 = gridOf(b.minZ());
        int gz1 = gridOf(b.maxZ());
        if (((long) (gx1 - gx0) + 1L) * ((long) (gz1 - gz0) + 1L) > MAX_INDEX_CELLS) {
            return null;
        }
        return new int[] { gx0, gz0, gx1, gz1 };
    }

    private void index(Province province) {
        this.sortedCache = null;
        VectorShape.Bounds b = province.shape().bounds();
        if (b == null) {
            return;
        }
        int[] cells = cellRange(b);
        if (cells == null) {
            this.tierGlobal.computeIfAbsent(province.tier(), t -> new ArrayList<>(2)).add(province);
            return;
        }
        Map<Long, List<Province>> index = this.tierIndex.computeIfAbsent(province.tier(), t -> new HashMap<>());
        for (int gx = cells[0]; gx <= cells[2]; gx++) {
            for (int gz = cells[1]; gz <= cells[3]; gz++) {
                index.computeIfAbsent(gridKey(gx, gz), k -> new ArrayList<>(2)).add(province);
            }
        }
    }

    private void unindex(Province province) {
        this.sortedCache = null;
        List<Province> global = this.tierGlobal.get(province.tier());
        if (global != null) {
            global.remove(province);
        }
        VectorShape.Bounds b = province.shape().bounds();
        Map<Long, List<Province>> index = this.tierIndex.get(province.tier());
        if (b == null || index == null) {
            return;
        }
        int[] cells = cellRange(b);
        if (cells == null) {
            return;
        }
        for (int gx = cells[0]; gx <= cells[2]; gx++) {
            for (int gz = cells[1]; gz <= cells[3]; gz++) {
                long key = gridKey(gx, gz);
                List<Province> list = index.get(key);
                if (list != null) {
                    list.remove(province);
                    if (list.isEmpty()) {
                        index.remove(key);
                    }
                }
            }
        }
    }

    private void reindex(Province province) {
        unindex(province);
        index(province);
    }

    /** 从零重建索引（载入存档后调用一次）。 */
    public void rebuildIndexes() {
        this.tierIndex.clear();
        this.tierGlobal.clear();
        // putRaw 是绕过 index() 直接塞进 byId 的，这里补一刀，免得排序缓存留着上一次世界的数据。
        this.sortedCache = null;
        for (Province p : this.byId.values()) {
            index(p);
        }
    }

    /** 只加载、不索引，供反序列化过程中逐条填入。 */
    public void putRaw(Province province) {
        this.byId.put(province.id(), province);
        bumpNextId(province.id());
    }

    /** 全部区划的面积之和（平方方块）。 */
    public double totalArea() {
        double sum = 0.0;
        for (Province p : this.byId.values()) {
            sum += p.shape().measure();
        }
        return sum;
    }
}
