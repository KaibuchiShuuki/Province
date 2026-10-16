package com.mcprovince.edit;

import com.mcprovince.client.ProvinceSettings;
import com.mcprovince.core.AreaFormat;
import com.mcprovince.core.BlockSnap;
import com.mcprovince.core.FenceFaces;
import com.mcprovince.core.Province;
import com.mcprovince.core.ProvinceStore;
import com.mcprovince.core.VectorShape;

import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次编辑过程的状态：正在把哪一档区划画成什么样子。
 *
 * <p>这里没有「边框」了。所有工具都在直接修改同一块选区，画出来的东西本身就是面积：</p>
 * <ul>
 *   <li>{@code working}：真正会被提交的选区，一开始是目标区划原来的形状。</li>
 *   <li>{@code preview}：正在拖的那一笔，松手前不并入。</li>
 *   <li>{@code guides}：用户这一路画下来的【中线与轮廓】，只喂给魔棒做平面图找面用。
 *       它不会出现在提交结果里，也不会显示成边框——取消一笔选区时它跟着一起没用，仅此而已。</li>
 *   <li>{@code limit}：本级能画到哪儿（父区划的形状），{@code null} 表示没有外边界。</li>
 *   <li>{@code blocked}：同级邻居已经占掉的部分，{@code null} 表示同级没有别人。</li>
 * </ul>
 *
 * <p>坐标一律是方块格点：鼠标点进来先 floor，笔画沿整数阶梯走，落笔的形状由
 * {@link BlockSnap} 拼成整块方块。所以边界永远贴着方块格线，笔迹最细也留得下一格。</p>
 */
public final class EditSession {

    /** 选区笔的笔迹宽度（方块）。宽度由界面按屏幕像素换算后喂进来，不再是用户调的参数。 */
    public static final double MIN_BRUSH_WIDTH = 0.05;
    public static final double MAX_BRUSH_WIDTH = 4096.0;
    public static final double DEFAULT_BRUSH_WIDTH = 12.0;

    /** 自由画笔一次落笔最多采样多少个点；拖太久会攒出几万个点，布尔运算会卡。 */
    public static final int MAX_STROKE_POINTS = 8192;

    /**
     * 魔棒用的辅助线最多存多少条。
     *
     * <p>围栏要把轮廓、父级边界、邻居边界和这些辅助线一起塞进 {@link FenceFaces}，那边是
     * 两两求交的平方级算法，总量必须压住。原来允许 3000 条，光辅助线就能顶掉整个求交
     * 预算，一次点击要卡上好几秒。</p>
     */
    private static final int MAX_GUIDES = 1_200;

    /** 一条辅助线最多留多少个点。 */
    private static final int GUIDE_MAX_POINTS = 320;

    /** 从一条路径里最多收多少个原始点。 */
    private static final int MAX_RAW_GUIDE_POINTS = 40_000;

    /** 一笔画完时首尾离得多近就算「这是想闭合一个圈」，自动补一条闭合边给魔棒用。 */
    private static final double CLOSE_GAP_MAX = 24.0;
    private static final int MAX_FREE_ENDS = 96;
    /**
     * 判端点接没接上时用的格子边长。
     *
     * <p>必须小到这个程度：只有真正共用同一个顶点的两段才算接上。格子开大了
     * 会把「同一根笔的首尾、但中间并没有边连着」也当成接上，于是圈永远收不了口。</p>
     */
    private static final double CLOSE_CELL = 1.0E-4;

    /** 一次拖拽最多跨多少格；地图缩到很小时一拉就是几百万格，布尔运算和屏幕坐标都会崩掉。 */
    public static final int MAX_DRAG_SPAN = 4096;

    private final ProvinceStore store;

    private int targetId = Province.NO_PARENT;
    private int tier = Province.MIN_TIER;
    private int parentId = Province.NO_PARENT;

    private final VectorShape working = VectorShape.empty();

    /** 外边界：本级区划最多画到父区划内部。{@code null} 表示第 1 级，没有外边界。 */
    private VectorShape limit;

    /** 内边界：同级邻居已经占掉的部分要避开。{@code null} 表示同级没有别人。 */
    private VectorShape blocked;

    private VectorShape preview;

    /** 魔棒的素材：用户画过的中线与轮廓，扁平化成线段。 */
    private final List<double[]> guides = new ArrayList<>();

    private EditTool tool = EditTool.SELECT_BRUSH;
    /** 拖拽时按住 Shift：直线走横竖、矩形与圆形补成等边。 */
    private boolean constrain;
    private double brushWidth = DEFAULT_BRUSH_WIDTH;

    private boolean dragging;
    private double strokeStartX;
    private double strokeStartZ;
    private double lastX;
    private double lastZ;
    private final List<double[]> stroke = new ArrayList<>();

    public EditSession(ProvinceStore store) {
        this.store = store;
    }

    // ——— 会话生命周期 ———

    /**
     * 开始编辑一档区划。区划必须已经存在——新建是先创建一块空区划再来画，不再「画完才建」。
     *
     * @return 目标是否存在
     */
    public boolean beginEdit(int provinceId) {
        Province province = this.store.byId(provinceId);
        if (province == null) {
            return false;
        }
        this.targetId = provinceId;
        this.tier = province.tier();
        this.parentId = province.parentId();
        this.limit = this.store.parentBound(this.parentId);
        this.blocked = this.store.siblingUnion(this.tier, provinceId);
        this.working.copyFrom(province.shape());
        applyMask();
        this.preview = null;
        this.guides.clear();
        this.dragging = false;
        this.stroke.clear();
        return true;
    }

    public int targetId() {
        return this.targetId;
    }

    public Province target() {
        return this.targetId == Province.NO_PARENT ? null : this.store.byId(this.targetId);
    }

    public Province parentProvince() {
        return this.parentId == Province.NO_PARENT ? null : this.store.byId(this.parentId);
    }

    public int tier() {
        return this.tier;
    }

    public int parentId() {
        return this.parentId;
    }

    // ——— 画布 ———

    public VectorShape working() {
        return this.working;
    }

    public VectorShape preview() {
        return this.preview;
    }

    public EditTool tool() {
        return this.tool;
    }

    public void setTool(EditTool tool) {
        if (this.tool == tool) {
            return;
        }
        this.tool = tool;
        this.preview = null;
        this.dragging = false;
        this.stroke.clear();
    }

    public double brushWidth() {
        return this.brushWidth;
    }

    public void setBrushWidth(double value) {
        this.brushWidth = Math.max(MIN_BRUSH_WIDTH, Math.min(MAX_BRUSH_WIDTH, value));
    }

    public void clearSelection() {
        this.working.clear();
        this.preview = null;
    }

    public int guideCount() {
        return this.guides.size();
    }

    /** 把选区裁回「本级允许占用的空间」。选区一变就得调一次，边界才真的管用。 */
    private void applyMask() {
        if (this.limit != null) {
            this.working.intersect(this.limit);
        }
        if (this.blocked != null) {
            this.working.subtract(this.blocked);
        }
    }

    /** 撤销到「目标区划原本的样子」。 */
    public void resetToOriginal() {
        Province target = this.target();
        if (target == null) {
            this.working.clear();
        } else {
            this.working.copyFrom(target.shape());
        }
        this.preview = null;
        this.guides.clear();
        this.dragging = false;
        this.stroke.clear();
        applyMask();
    }

    // ——— 鼠标事件 ———

    /** 这一笔是往选区里加还是减。 */
    private boolean removing() {
        return this.tool.isErasing();
    }

    /** 鼠标按下。传进来的是地图上的连续坐标，这里先吸附到方块格点。 */
    public void onPress(double x, double z) {
        if (this.tool.isClickOnly()) {
            // 魔棒按下即生效，不走拖拽。
            return;
        }
        double gx = Math.floor(x);
        double gz = Math.floor(z);
        this.dragging = true;
        this.strokeStartX = gx;
        this.strokeStartZ = gz;
        this.lastX = gx;
        this.lastZ = gz;
        this.stroke.clear();
        this.stroke.add(new double[] { gx, gz });
        this.preview = VectorShape.empty();
        if (this.tool.isDragShape()) {
            this.rebuildShape(gx, gz);
        } else {
            this.paintSegment(gx, gz);
        }
    }

    /** 鼠标拖拽。 */
    public void onDrag(double x, double z) {
        if (!this.dragging) {
            return;
        }
        double gx = Math.floor(x);
        double gz = Math.floor(z);
        if (this.tool.isDragShape()) {
            this.rebuildShape(gx, gz);
        } else {
            this.paintSegment(gx, gz);
        }
        this.lastX = gx;
        this.lastZ = gz;
    }

    private static double clampSpan(double from, double to) {
        double delta = to - from;
        if (delta > MAX_DRAG_SPAN) {
            return from + MAX_DRAG_SPAN;
        }
        return delta < -MAX_DRAG_SPAN ? from - MAX_DRAG_SPAN : to;
    }

    /** 鼠标松开，把预览并进选区，并留下这一笔的辅助线。 */
    public void onRelease() {
        if (!this.dragging) {
            return;
        }
        this.dragging = false;
        if (this.preview != null && !this.preview.isEmpty()) {
            if (removing()) {
                this.working.subtract(this.preview);
            } else {
                this.working.add(this.preview);
            }
            recordGuides();
            applyMask();
        }
        this.preview = null;
        this.stroke.clear();
    }

    /**
     * 自由画笔 / 选区擦：把这一小段并进当前笔迹。
     *
     * <p>笔迹交给 {@link BlockSnap#brushStroke}：它沿整数阶梯铺方块，笔再细也留得下一格，
     * 不会出现「细线吸附之后整条消失」。采样点本来就是格点，同一格里的抖动直接丢掉。</p>
     */
    private void paintSegment(double x, double z) {
        if (this.dragging && this.stroke.size() < MAX_STROKE_POINTS) {
            double[] tail = this.stroke.get(this.stroke.size() - 1);
            if (x != tail[0] || z != tail[1]) {
                this.stroke.add(new double[] { x, z });
            }
        }
        this.preview = BlockSnap.brushStroke(this.stroke, this.brushWidth);
    }

    /** 直线 / 矩形 / 圆形：全部实心，拖出来的就是一整块面积，而且一律吸附成整块方块。 */
    private void rebuildShape(double x, double z) {
        double startX = this.strokeStartX;
        double startZ = this.strokeStartZ;
        double[] end = constrainedEnd(x, z);
        double ex = end[0];
        double ez = end[1];
        this.preview = switch (this.tool) {
            // 直线也是笔迹：沿阶梯铺方块，细笔不会因为「没有方块中心落在线上」整条不见。
            case SELECT_LINE -> BlockSnap.brushStroke(
                    List.of(new double[] { startX, startZ }, new double[] { ex, ez }), this.brushWidth);
            case SELECT_RECT -> BlockSnap.snap(VectorShape.ofRect(startX, startZ, ex, ez));
            case SELECT_ELLIPSE -> BlockSnap.snap(VectorShape.ofEllipse(startX, startZ, ex, ez));
            default -> VectorShape.empty();
        };
    }

    /** 按住 Shift 就吸成正形；平时原样返回。 */
    public void setConstrain(boolean value) {
        this.constrain = value;
    }

    /**
     * 拖拽终点。按住 Shift 时吸成正形：直线只走水平或垂直，矩形与圆形补成等边。
     *
     * <p>吸的是终点，起点（锚点）不动 —— 往哪边拖、哪边的分量说了算，和画图软件里一个手感。</p>
     */
    private double[] constrainedEnd(double x, double z) {
        double startX = this.strokeStartX;
        double startZ = this.strokeStartZ;
        double ex = clampSpan(startX, x);
        double ez = clampSpan(startZ, z);
        if (!this.constrain) {
            return new double[] { ex, ez };
        }
        double dx = ex - startX;
        double dz = ez - startZ;
        return switch (this.tool) {
            case SELECT_LINE -> Math.abs(dx) >= Math.abs(dz)
                    ? new double[] { ex, startZ }
                    : new double[] { startX, ez };
            case SELECT_RECT, SELECT_ELLIPSE -> {
                double side = Math.max(Math.abs(dx), Math.abs(dz));
                yield new double[] { startX + Math.copySign(side, dx), startZ + Math.copySign(side, dz) };
            }
            default -> new double[] { ex, ez };
        };
    }

    // ——— 辅助线（只给魔棒用） ———

    /** 把刚画完的这一笔的中线 / 轮廓记下来。 */
    private void recordGuides() {
        switch (this.tool) {
            // 笔迹直接按整数格点折线喂进去。原来这里走 smoothPath 做 Catmull-Rom 平滑，
            // 控制点落在格子中间、急转弯处还会过冲，跟已经吸附好的方块轮廓叠在同一个平面图
            // 里就会切出一堆细缝，魔棒常常选中那条缝，而不是玩家真正要填的那一块。
            case SELECT_BRUSH, ERASER -> appendGuides(polyline(this.stroke));
            case SELECT_LINE -> {
                double[] end = constrainedEnd(this.lastX, this.lastZ);
                Path2D.Double path = new Path2D.Double();
                path.moveTo(this.strokeStartX, this.strokeStartZ);
                path.lineTo(end[0], end[1]);
                appendGuides(path);
            }
            case SELECT_RECT -> {
                double[] end = constrainedEnd(this.lastX, this.lastZ);
                double ex = end[0];
                double ez = end[1];
                Path2D.Double path = new Path2D.Double();
                path.moveTo(this.strokeStartX, this.strokeStartZ);
                path.lineTo(ex, this.strokeStartZ);
                path.lineTo(ex, ez);
                path.lineTo(this.strokeStartX, ez);
                path.closePath();
                appendGuides(path);
            }
            case SELECT_ELLIPSE -> {
                double[] end = constrainedEnd(this.lastX, this.lastZ);
                double sx = this.strokeStartX;
                double sz = this.strokeStartZ;
                double ex = end[0];
                double ez = end[1];
                double a = Math.min(sx, ex);
                double b = Math.max(sx, ex);
                double c = Math.min(sz, ez);
                double d = Math.max(sz, ez);
                if (b - a > 1.0E-3 && d - c > 1.0E-3) {
                    appendGuides(new java.awt.geom.Ellipse2D.Double(a, c, b - a, d - c));
                }
            }
            default -> {
            }
        }
    }

    /**
     * 把一条路径拆成线段塞进 {@code guides}。
     *
     * <p>这里故意不走 {@link VectorShape} 的线段表——它内部是 {@code Area}，会把每条开放
     * 子路径偷偷连回起点，凭空多出一堵墙；平面图要的是用户真正画下的那些线。</p>
     */
    private void appendGuides(Shape shape) {
        if (shape == null || this.guides.size() >= MAX_GUIDES) {
            return;
        }
        PathIterator it = shape.getPathIterator(null, 0.35);
        double[] coords = new double[6];
        List<double[]> points = new ArrayList<>();
        boolean closed = false;
        while (!it.isDone() && points.size() < MAX_RAW_GUIDE_POINTS) {
            int type = it.currentSegment(coords);
            if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO) {
                points.add(new double[] { coords[0], coords[1] });
            } else if (type == PathIterator.SEG_CLOSE) {
                closed = true;
            }
            it.next();
        }
        if (points.size() < 2) {
            return;
        }
        // 抽稀：手画的点太密，原样丢给平面图会让两两求交变成 O(n²) 的灾难，
        // 一笔画的圆环能攒出上万条线段，直接就超过平面上限、连面都找不到。
        List<double[]> thin = decimate(points, GUIDE_MAX_POINTS);
        for (int i = 1; i < thin.size(); i++) {
            addGuideLine(thin.get(i - 1), thin.get(i));
            if (this.guides.size() >= MAX_GUIDES) {
                return;
            }
        }
        if (closed && this.guides.size() < MAX_GUIDES) {
            addGuideLine(thin.get(thin.size() - 1), thin.get(0));
        }
    }

    /** 把一串格点连成一条**不闭合**的折线路径。 */
    private static Shape polyline(List<double[]> points) {
        Path2D.Double path = new Path2D.Double();
        if (points.isEmpty()) {
            return path;
        }
        path.moveTo(points.get(0)[0], points.get(0)[1]);
        for (int i = 1; i < points.size(); i++) {
            path.lineTo(points.get(i)[0], points.get(i)[1]);
        }
        return path;
    }

    /**
     * 往围栏里加一条中线：端点取整对齐到方块边界，连线本身保持原样。
     *
     * <p>取整是为了跟轮廓那圈墙对得上；但**不**把斜线拆成横平竖直两段——玩家拿直线
     * 工具画的对角线，一拆就会正好压在另外两条线上，围出来的三角形整个瘪掉，魔棒
     * 点进去什么都找不到。墙允许是斜的，它本来就是玩家亲手画的那条线。</p>
     */
    private void addGuideLine(double[] a, double[] b) {
        if (this.guides.size() >= MAX_GUIDES) {
            return;
        }
        double x0 = Math.round(a[0]);
        double z0 = Math.round(a[1]);
        double x1 = Math.round(b[0]);
        double z1 = Math.round(b[1]);
        if (x0 == x1 && z0 == z1) {
            return;
        }
        this.guides.add(new double[] { x0, z0, x1, z1 });
    }

    /** 等距抽稀到最多 {@code max} 个点，首尾一定保留。 */
    private static List<double[]> decimate(List<double[]> points, int max) {
        if (points.size() <= max) {
            return points;
        }
        double total = 0.0;
        for (int i = 1; i < points.size(); i++) {
            total += Math.hypot(points.get(i)[0] - points.get(i - 1)[0], points.get(i)[1] - points.get(i - 1)[1]);
        }
        double step = total / (max - 1);
        if (!(step > 0.0)) {
            return points;
        }
        List<double[]> out = new ArrayList<>(max);
        out.add(points.get(0));
        double acc = 0.0;
        double next = step;
        for (int i = 1; i < points.size() - 1 && out.size() < max - 1; i++) {
            acc += Math.hypot(points.get(i)[0] - points.get(i - 1)[0], points.get(i)[1] - points.get(i - 1)[1]);
            if (acc >= next) {
                out.add(points.get(i));
                next += step;
            }
        }
        out.add(points.get(points.size() - 1));
        return out;
    }

    // ——— 魔棒 ———

    /**
     * 在 (x,z) 处点一下魔棒。
     *
     * <p>围栏不只算玩家自己画的线：已经选中的那一块、父级的边界、兄弟区划的边界，统统
     * 算墙。少了它们，魔棒就会从父级边缘、或者从旁边那块地直接漫过去——点击点外围本来
     * 是别人的地盘，却被当成同一块吞进选区。</p>
     *
     * <p>这几样墙都是现成的，用不着玩家先画一笔才认——真要画，那也是为了圈出边界本身
     * 围不出来的形状。</p>
     *
     * <p>也绝不再退化成「没线就选整个父级」：那正是「一点魔棒整块变色」的根源。
     * 实在圈不出来就老实说圈不出来。</p>
     *
     * @return 这次选区真正变化的面积（平方方块）；{@code -1} 表示这里什么都圈不出来
     */
    public double applyMagic(double x, double z) {
        double gx = Math.floor(x);
        double gz = Math.floor(z);
        // 查询点取这一格的中心，而不是左下角：围栏的墙全压在整数格线上，格的角点正好落在
        // 墙上，「点在不在这个面里」就成了边界上的未定义行为，魔棒于是会莫名其妙地选中
        // 隔壁那块面（常常是外侧那块），填出来的东西顺着边界溢出去。
        double px = gx + 0.5;
        double pz = gz + 0.5;
        // 一笔都没画过的时候，墙就是现成的几个形状，用不着搭平面图。
        VectorShape fill = this.guides.isEmpty() ? quickFill(px, pz) : null;
        if (fill == null) {
            List<double[]> fences = magicFences(px, pz);
            fill = fences.size() >= 3 ? FenceFaces.faceAt(fences, px, pz) : null;
            if (fill != null && !fill.isEmpty()) {
                // 围栏交点可能是小数，填出来的面也得落回方块格线。
                fill = BlockSnap.snap(fill);
            }
        }
        if (fill == null || fill.isEmpty()) {
            // 平面图那条路会被预算截断：父级和同级的地盘一大，墙就拼不全，墙一断面就圈不出来。
            // 别急着报「圈不出来」——现成的墙（父级边界、同级邻居、已经选中的部分）还在手上，
            // 拿它们再算一次。玩家画过线也好、没画过也好，这一步都该管用。
            fill = quickFill(px, pz);
        }
        if (fill == null || fill.isEmpty()) {
            this.preview = null;
            return -1.0;
        }
        fill = this.store.clipFor(this.parentId, this.tier, this.targetId, fill);
        if (fill.isEmpty()) {
            this.preview = null;
            return 0.0;
        }
        double before = this.working.measure();
        if (removing()) {
            this.working.subtract(fill);
        } else {
            this.working.add(fill);
        }
        applyMask();
        this.preview = null;
        // 点在自己已经选中的地方时 working 根本不会变，这里如实报 0，而不是报一个
        // 「选中了多少多少格」——那会让玩家以为又圈进来新的一片。
        double delta = Math.abs(this.working.measure() - before);
        return delta < 1.0E-6 ? 0.0 : delta;
    }

    /**
     * 近路：直接拿现成的形状算，不搭平面图。
     *
     * <p>魔棒的墙本来就只有三样——已选部分、同级邻居、外面那圈边界（见 {@link #outerBound}），
     * 而这三样在手里都是现成的形状。平面图那边是平方级求交，一块顶到父级的大空地光轮廓就上千段，
     * 围栏一超预算就被截断，面再也合不上，「点了一大片空闲地却毫无反应」正是打这儿来的。
     * 这里改成：把外边界减掉已选部分和邻居，再挑出包含点击点的那个连通块。</p>
     *
     * <p>玩家画没画过线都能走这一步：画过的线只是多给平面图一把尺子，墙本身没变。
     * 平面图圈不出面的时候，就靠它兜底。</p>
     *
     * @return 包含这一点的连通块；用不上近路（点在同级邻居身上、外边界为空）时返回 {@code null}
     */
    private VectorShape quickFill(double x, double z) {
        if (this.working.contains(x, z)) {
            return componentAt(this.working, x, z);
        }
        // 点在邻居身上，说明这一处本来就是别人的地盘，交回平面图去分辨该圈哪块。
        if (this.blocked != null && this.blocked.contains(x, z)) {
            return null;
        }
        VectorShape region = outerBound(x, z);
        if (region == null) {
            return null;
        }
        if (!this.working.isEmpty()) {
            region.subtract(this.working);
        }
        if (this.blocked != null && !this.blocked.isEmpty()) {
            region.subtract(this.blocked);
        }
        return componentAt(region, x, z);
    }

    /**
     * 第 1 级没有父级边界可依，魔棒临时给它搭的方框，比「已选部分 + 同级邻居」再大出这么多格。
     * 太小的话点远一点的空地就没反应，太大又等于放它漫出去。
     */
    private static final double MAGIC_WORLD_MARGIN = 512.0;

    /**
     * 魔棒最外面那圈墙：有父级就用父级的地盘，没有父级（第 1 级）就现搭一个方框兜住。
     *
     * <p>第 1 级外面本来是无边无际的空地。旁边已经有同级邻居的时候，空地是被邻居夹出来的，
     * 圈一块是玩家的本意，所以搭个方框把点击点、同级邻居和已经选中的部分都圈进去，再往外
     * 放一圈余量——点哪儿都能圈出一块像样的地方，也不会顺着平面图一路铺到天边。</p>
     *
     * <p>可要是连一个邻居都没有，那就什么边都没有，什么都不该给：给了就等于「一点魔棒
     * 吞掉整张地图」。</p>
     */
    private VectorShape outerBound(double x, double z) {
        if (this.limit != null) {
            return this.limit.copy();
        }
        if (this.blocked == null || this.blocked.isEmpty()) {
            return null;
        }
        double minX = x;
        double maxX = x;
        double minZ = z;
        double maxZ = z;
        for (VectorShape shape : new VectorShape[] { this.working, this.blocked }) {
            if (shape == null) {
                continue;
            }
            VectorShape.Bounds bounds = shape.bounds();
            if (bounds == null) {
                continue;
            }
            minX = Math.min(minX, bounds.minX());
            maxX = Math.max(maxX, bounds.maxX());
            minZ = Math.min(minZ, bounds.minZ());
            maxZ = Math.max(maxZ, bounds.maxZ());
        }
        return VectorShape.ofRect(minX - MAGIC_WORLD_MARGIN, minZ - MAGIC_WORLD_MARGIN,
                maxX + MAGIC_WORLD_MARGIN, maxZ + MAGIC_WORLD_MARGIN);
    }

    /**
     * 从一块形状的连通块里挑出包含这一点的那个。
     *
     * <p>{@link VectorShape#components()} 会把洞也一并切出来，所以先拿整块形状复核一次：
     * 能走到这里的点必定不在洞里，之后逐个分量去找就安全了。</p>
     */
    private static VectorShape componentAt(VectorShape shape, double x, double z) {
        if (!shape.contains(x, z)) {
            return null;
        }
        for (VectorShape part : shape.components()) {
            if (part.contains(x, z)) {
                return part;
            }
        }
        return shape;
    }

    /**
     * 围栏线段数的硬上限，飞地特别多时兜底。
     *
     * <p>必须压住 {@link FenceFaces#MAX_SEGMENTS}（4000）：一旦超了那边直接返回「圈不出来」，
     * 魔棒就成了点一下毫无反应。轮廓是逐顶点接的，一块顶到父级的大空地光自己就上千段，
     * 所以余量得留够——实在超了还有 {@link #quickFill} 那条路兜着。</p>
     */
    private static final int MAGIC_MAX_FENCE = 3_000;

    /**
     * 魔棒的围栏：已选部分 + 同级邻居 + 父级边界 + 玩家自己画的线。
     *
     * <p>前三样是「不该越过的墙」——魔棒的选区必须被它们夹住；最后一样才是玩家用来围面的线。
     * 第 1 级没有父级，就用 {@link #outerBound} 现搭的方框当外边界。</p>
     */
    private List<double[]> magicFences(double x, double z) {
        List<double[]> fences = new ArrayList<>();
        // 三样墙各有各的份额，谁也别想把预算全吃光：玩家画的线得留得下位置，
        // 不然「拿笔圈一圈再点魔棒」就永远围不出面来。
        int share = Math.max(64, (MAGIC_MAX_FENCE - MAX_GUIDES) / 3);
        appendOutline(fences, this.working, share);
        appendOutline(fences, this.blocked, share * 2);
        appendOutline(fences, outerBound(x, z), share * 3);
        fences.addAll(guidesWithClosure());
        return dedupe(fences);
    }

    /**
     * 去掉重合的线段，顺便把端点取整。
     *
     * <p>玩家沿着已经选好的边界再描一笔的时候，笔迹的中线和方块轮廓会压在同一条格线上。
     * 重合的两条有向边在平面图里是一对「去而复返」的边，最右转会绕着它们转出只有两条边
     * 的环，本来好好的面就被顶掉了——表现是点了魔棒一点反应都没有。顺便也把段数压下去
     * 一些：{@link FenceFaces} 的求交是平方级的。</p>
     */
    private static List<double[]> dedupe(List<double[]> fences) {
        Set<String> seen = new HashSet<>();
        List<double[]> out = new ArrayList<>(fences.size());
        for (double[] edge : fences) {
            double x0 = Math.round(edge[0]);
            double z0 = Math.round(edge[1]);
            double x1 = Math.round(edge[2]);
            double z1 = Math.round(edge[3]);
            if (x0 == x1 && z0 == z1) {
                continue;
            }
            String key = x0 < x1 || (x0 == x1 && z0 <= z1)
                    ? x0 + "," + z0 + "," + x1 + "," + z1
                    : x1 + "," + z1 + "," + x0 + "," + z0;
            if (seen.add(key)) {
                out.add(new double[] { x0, z0, x1, z1 });
            }
        }
        return out;
    }

    /**
     * 把一个形状的轮廓接成折线墙，塞进魔棒的围栏。
     *
     * <p>这里的轮廓是给魔棒当墙用的，得**首尾相连**才算一堵墙，所以按 {@link VectorShape#outlinePoints()}
     * 给的子路径逐条处理：外圈一条、每个洞一条，各自闭合成折线。</p>
     *
     * <p>一个顶点都不跳：轮廓本来就是一格一格拼出来的，相邻顶点之间必然横平竖直，老实
     * 按顶点连下去，墙就正好压在原边界上。跳过顶点去连弦的话，斜弦会从格子中间穿过去，
     * 魔棒顺着这条错开的墙填，就贴着边界成片漏格、或者反过来整块溢到外面——那正是老
     * 版本「围栏附近大小遗漏 + 溢出」的来源。</p>
     *
     * @param max 这块轮廓自己的段数份额：围栏是平方级求交，段数得总量控制；
     *            按形状分摊之后，谁的轮廓再长也挤不掉别人的墙
     */
    private static void appendOutline(List<double[]> fences, VectorShape shape, int max) {
        if (shape == null || shape.isEmpty()) {
            return;
        }
        List<double[]> parts = shape.outlinePoints();
        if (parts.isEmpty()) {
            return;
        }
        for (double[] part : parts) {
            int count = part.length / 2;
            if (count < 3) {
                continue;
            }
            int prev = count - 1;
            for (int i = 0; i < count; i++) {
                if (fences.size() >= max) {
                    return;
                }
                appendGridEdge(fences, part, prev, i);
                prev = i;
            }
        }
    }

    /**
     * 把子路径上两个顶点之间的一段接进围栏，并且**只走格线**。
     *
     * <p>抽稀留下的那两个顶点本来就在整数格线上（轮廓是方块拼出来的），直接连一条斜边的话，
     * 阶梯状的直角边界会被拉成一条斜弦，而斜弦会从格子中间穿过去。围栏一旦跟真实边界错开，
     * 魔棒就顺着这条错开的墙去填：贴着边界成片地漏格，或者反过来溢到外面去。所以斜着走的
     * 时候拆成先横后竖（或者先竖后横）两段，墙始终压在格线上。</p>
     */
    private static void appendGridEdge(List<double[]> fences, double[] part, int from, int to) {
        double x0 = Math.round(part[from * 2]);
        double z0 = Math.round(part[from * 2 + 1]);
        double x1 = Math.round(part[to * 2]);
        double z1 = Math.round(part[to * 2 + 1]);
        if (x0 == x1 && z0 == z1) {
            return;
        }
        if (x0 == x1 || z0 == z1) {
            fences.add(new double[] { x0, z0, x1, z1 });
            return;
        }
        // 长的那一边先走：折点因此落在更贴近原来那段阶梯的位置。
        boolean horizontalFirst = Math.abs(x1 - x0) >= Math.abs(z1 - z0);
        double mx = horizontalFirst ? x1 : x0;
        double mz = horizontalFirst ? z0 : z1;
        fences.add(new double[] { x0, z0, mx, mz });
        fences.add(new double[] { mx, mz, x1, z1 });
    }

    /**
     * 魔棒用的线段表：把「差不多该接上、其实还差一点点」的端点接起来。
     *
     * <p>手画的东西很难严丝合缝，差那么零点几格，平面图里就没有闭合的面，
     * 魔棒就永远点不中圈里的那块空心。做法是：把所有还封不住口、孤零零露在外面的
     * 端点找出来，就近两两接一条边——一笔画的圈、几笔拼的圈都能收口。</p>
     */
    private List<double[]> guidesWithClosure() {
        List<double[]> out = new ArrayList<>(this.guides.size() + 4);
        out.addAll(this.guides);
        if (this.guides.size() < 2) {
            return out;
        }

        // 端点按极小的格子归堆：格子小到只有真正共点的两段才会落进同一格。
        // 用格哈希而不是两两比距离，是因为一笔画的圈能攒出几百条边——
        // 两两比就是十几万次开方，还会把 MAX_FREE_ENDS 顶爆，于是收口边一条都补不上。
        Map<Long, Integer> occupancy = new HashMap<>();
        for (double[] segment : this.guides) {
            bump(occupancy, segment[0], segment[1]);
            bump(occupancy, segment[2], segment[3]);
        }

        List<double[]> free = new ArrayList<>();
        for (double[] segment : this.guides) {
            if (isFreeEnd(occupancy, segment[0], segment[1])) {
                free.add(new double[] { segment[0], segment[1] });
            }
            if (isFreeEnd(occupancy, segment[2], segment[3])) {
                free.add(new double[] { segment[2], segment[3] });
            }
        }
        if (free.isEmpty()) {
            return out;
        }
        // 自由端太多就只接前 MAX_FREE_ENDS 个，而不是一条都不接：分段多的笔迹很容易超过
        // 这个数，全放弃等于围栏永远收不了口，魔棒填出来的面就从缺口一路漏到围栏外面去。
        if (free.size() > MAX_FREE_ENDS) {
            free = new ArrayList<>(free.subList(0, MAX_FREE_ENDS));
        }

        // 收口边的长度上限：基础值跟着笔宽走，但必须封顶。地图缩小的时候笔宽能到几千格，
        // 上限跟着涨就等于「随便哪两个端点都连一条」，那条横穿整个区域的假墙会把围栏切成
        // 两半，魔棒填出来的要么缺一大块，要么绕过围栏溢出去。
        double limit = Math.min(Math.max(CLOSE_GAP_MAX, this.brushWidth), 32.0);
        boolean[] used = new boolean[free.size()];
        for (int i = 0; i < free.size(); i++) {
            if (used[i]) {
                continue;
            }
            int best = -1;
            double bestGap = Double.MAX_VALUE;
            for (int j = i + 1; j < free.size(); j++) {
                if (used[j]) {
                    continue;
                }
                double gap = Math.hypot(free.get(i)[0] - free.get(j)[0], free.get(i)[1] - free.get(j)[1]);
                if (gap < bestGap) {
                    bestGap = gap;
                    best = j;
                }
            }
            if (best < 0 || bestGap > limit) {
                continue;
            }
            used[i] = true;
            used[best] = true;
            out.add(new double[] { free.get(i)[0], free.get(i)[1], free.get(best)[0], free.get(best)[1] });
        }
        return out;
    }

    /** 端点落在哪一格。格边长取 {@link #CLOSE_CELL}，小到只有真正共点才算同一格。 */
    private static long cellOf(double x, double z) {
        long cx = Math.round(x / CLOSE_CELL);
        long cz = Math.round(z / CLOSE_CELL);
        return (cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private static void bump(Map<Long, Integer> occupancy, double x, double z) {
        occupancy.merge(cellOf(x, z), 1, Integer::sum);
    }

    /** 周围 3x3 格子里只有自己一个端点——那就是个没接上的自由端。 */
    private static boolean isFreeEnd(Map<Long, Integer> occupancy, double x, double z) {
        long cx = Math.round(x / CLOSE_CELL);
        long cz = Math.round(z / CLOSE_CELL);
        int nearby = 0;
        for (long dx = -1; dx <= 1; dx++) {
            for (long dz = -1; dz <= 1; dz++) {
                Integer hit = occupancy.get((((cx + dx) << 32) ^ ((cz + dz) & 0xFFFFFFFFL)));
                if (hit != null) {
                    nearby += hit;
                }
            }
        }
        return nearby < 2;
    }

    // ——— 提交 ———

    /** 当前这一笔要提交的形状——就是选区本身，没有任何边框成分。 */
    public VectorShape currentShape() {
        return this.working.copy();
    }

    /** 相对原状新增的部分。 */
    public VectorShape addedSinceOriginal() {
        VectorShape out = this.currentShape();
        Province target = this.target();
        if (target != null) {
            out.subtract(target.shape());
        }
        return out;
    }

    /** 相对原状被删掉的部分。 */
    public VectorShape removedSinceOriginal() {
        VectorShape out = VectorShape.empty();
        Province target = this.target();
        if (target != null) {
            out.copyFrom(target.shape());
            out.subtract(this.currentShape());
        }
        return out;
    }

    public boolean hasChanges() {
        Province target = this.target();
        if (target == null) {
            return !this.currentShape().isEmpty();
        }
        return !addedSinceOriginal().isEmpty() || !removedSinceOriginal().isEmpty();
    }

    /** 供界面显示的一行状态。 */
    public String statusLine() {
        Province target = this.target();
        String what = target == null
                ? ("第 " + this.tier + " 级")
                : target.displayName(com.mcprovince.core.NameFormat.ZH_FULL);
        return what + " · 选区 "
                + AreaFormat.format(this.currentShape().measure(), ProvinceSettings.get().areaInKilo);
    }
}
