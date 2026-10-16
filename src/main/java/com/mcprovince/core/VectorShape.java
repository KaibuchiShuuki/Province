package com.mcprovince.core;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 一块区划的几何形状——矢量表示，但边界一定落在方块格线上。
 *
 * <p>内部包一个 {@link Area}：合并、相减、求交、包含判定全都交给它做布尔运算。
 * 进存档的形状都是「整块方块的并集」：顶点全是整数、边只走横竖，由 {@link BlockSnap}
 * 吸附出来，所以「边界画在哪」和「这个方块归谁」永远不会打架。</p>
 */
public final class VectorShape {

    /** 坐标包围盒（非空形状才有意义）。 */
    public record Bounds(double minX, double minZ, double maxX, double maxZ) {
        public boolean contains(double x, double z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public double width() {
            return maxX - minX;
        }

        public double height() {
            return maxZ - minZ;
        }

        public double centerX() {
            return (minX + maxX) / 2.0;
        }

        public double centerZ() {
            return (minZ + maxZ) / 2.0;
        }
    }

    /** 扁平化精度：越小越贴合曲线，点也越多。 */
    private static final double FLATTEN = 0.35;

    private final Area area;

    /** 点是否落在形状内部。飞地会让包围盒中心跑到形状外面，画名牌前要用它确认。 */
    public boolean contains(double x, double z) {
        return this.area.contains(x, z);
    }

    /** {@link #segments()} 的缓存，形状一改就作废。 */
    private List<double[]> segmentCache;

    /** {@link #components()} 的缓存。 */
    private List<VectorShape> componentCache;

    private VectorShape(Area area) {
        this.area = area;
    }

    private void invalidate() {
        this.segmentCache = null;
        this.componentCache = null;
    }

    /**
     * 把形状拆成一块块互不相连的封闭区域。
     *
     * <p>有飞地时一块区划其实是好几个分离的岛，名牌得每块各给一个，
     * 所以这里按 {@link Area} 的每一段闭合子路径切开。洞也会被切出来，
     * 调用方拿 {@link #contains(double, double)} 复核一下就能把洞滤掉。</p>
     */
    public List<VectorShape> components() {
        if (this.componentCache != null) {
            return this.componentCache;
        }
        List<VectorShape> parts = new ArrayList<>();
        PathIterator iterator = this.area.getPathIterator(null, FLATTEN);
        Path2D.Double current = null;
        double[] coords = new double[6];
        while (!iterator.isDone()) {
            int type = iterator.currentSegment(coords);
            if (type == PathIterator.SEG_MOVETO) {
                if (current != null) {
                    addComponent(parts, current);
                }
                current = new Path2D.Double(Path2D.WIND_NON_ZERO);
                current.moveTo(coords[0], coords[1]);
            } else if (current != null) {
                switch (type) {
                    case PathIterator.SEG_LINETO -> current.lineTo(coords[0], coords[1]);
                    case PathIterator.SEG_QUADTO ->
                            current.quadTo(coords[0], coords[1], coords[2], coords[3]);
                    case PathIterator.SEG_CUBICTO ->
                            current.curveTo(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5]);
                    case PathIterator.SEG_CLOSE -> current.closePath();
                    default -> {
                    }
                }
            }
            iterator.next();
        }
        if (current != null) {
            addComponent(parts, current);
        }
        this.componentCache = parts;
        return parts;
    }

    private static void addComponent(List<VectorShape> parts, Path2D.Double path) {
        Area part = new Area(path);
        if (!part.isEmpty()) {
            parts.add(new VectorShape(part));
        }
    }

    public static VectorShape empty() {
        return new VectorShape(new Area());
    }

    public static VectorShape of(Shape shape) {
        return new VectorShape(new Area(shape));
    }

    /** 包一层现成的 {@link Area}，不再拷贝；{@link BlockSnap} 拼方块时用。 */
    static VectorShape wrap(Area area) {
        return new VectorShape(area);
    }

    /** 内部的 {@link Area} 本身，只读；方块吸附的扫描线要遍历它。 */
    Area areaView() {
        return this.area;
    }

    /** 矩形：外接矩形由一对对角点给出，顺序随意；退化成点或线时返回空形状。 */
    public static VectorShape ofRect(double x0, double z0, double x1, double z1) {
        double[] box = normalize(x0, z0, x1, z1);
        return box == null ? empty() : of(new Rectangle2D.Double(box[0], box[1], box[2], box[3]));
    }

    /** 椭圆：外接矩形由一对对角点给出，顺序随意。 */
    public static VectorShape ofEllipse(double x0, double z0, double x1, double z1) {
        double[] box = normalize(x0, z0, x1, z1);
        return box == null ? empty() : of(new Ellipse2D.Double(box[0], box[1], box[2], box[3]));
    }

    /** 把任意顺序的一对对角点归一成 {@code {minX, minZ, 宽, 高}}；退化的返回 {@code null}。 */
    private static double[] normalize(double x0, double z0, double x1, double z1) {
        double w = Math.abs(x1 - x0);
        double h = Math.abs(z1 - z0);
        if (w < 1.0E-3 || h < 1.0E-3) {
            return null;
        }
        return new double[] { Math.min(x0, x1), Math.min(z0, z1), w, h };
    }

    /** 有厚度的线段，面积画笔和「直线」笔迹都用它变成一块真正的区域。 */
    public static VectorShape ofThickLine(double x0, double z0, double x1, double z1, double thickness) {
        float w = (float) Math.max(0.25, thickness);
        Shape stroked = new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                .createStrokedShape(new Line2D.Double(x0, z0, x1, z1));
        return of(stroked);
    }

    /**
     * 把一串采样点连成平滑曲线（Catmull-Rom 转三次贝塞尔），再变成一个形状。
     *
     * <p>自由画笔走的就是这条：手指划过去的点不该被折成硬邦邦的折线。</p>
     *
     * @param pts       依次是 {x, z}
     * @param closed    是否首尾相连
     * @param thickness 笔迹宽度；填 0 表示只要一条曲线本身围出来的区域
     */
    public static VectorShape ofCurve(List<double[]> pts, boolean closed, double thickness) {
        if (pts == null || pts.size() < 2) {
            return empty();
        }
        if (thickness <= 0.01) {
            return of(smoothPath(pts, closed));
        }
        // 逐段做成圆头胶囊再并起来。
        //
        // 整条折线一次性描边——或者先过一遍 Catmull-Rom 样条——在鼠标抖动攒出来的密集采样点上
        // 会「过冲」自交，那些自交的小环在布尔解析里会变成一个个小空洞，画出来就是
        // 「笔迹中间到处是洞」。一对一的凸胶囊做并集则永远不会啃出洞。
        List<double[]> points = decimate(pts);
        float w = (float) Math.max(0.25, thickness);
        int n = points.size();
        int last = closed ? n : n - 1;
        Area out = new Area();
        for (int i = 0; i < last; i++) {
            double[] a = points.get(i);
            double[] b = points.get((i + 1) % n);
            out.add(new Area(capsule(a[0], a[1], b[0], b[1], w)));
        }
        return of(out);
    }

    private static Shape capsule(double x0, double z0, double x1, double z1, float w) {
        return new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                .createStrokedShape(new Line2D.Double(x0, z0, x1, z1));
    }

    /** 参与布尔运算的最大段数；再多就先等距抽稀，否则一次并集要算到天荒地老。 */
    private static final int MAX_CURVE_POINTS = 768;

    private static List<double[]> decimate(List<double[]> pts) {
        int n = pts.size();
        int step = Math.max(1, n / MAX_CURVE_POINTS);
        if (step <= 1) {
            return pts;
        }
        List<double[]> out = new ArrayList<>(n / step + 2);
        for (int i = 0; i < n; i += step) {
            out.add(pts.get(i));
        }
        double[] veryLast = pts.get(n - 1);
        double[] tail = out.get(out.size() - 1);
        if (tail[0] != veryLast[0] || tail[1] != veryLast[1]) {
            out.add(veryLast);
        }
        return out;
    }

    /** Catmull-Rom 样条转成三次贝塞尔路径。 */
    public static Path2D.Double smoothPath(List<double[]> pts, boolean closed) {
        Path2D.Double path = new Path2D.Double();
        int n = pts.size();
        if (n < 2) {
            return path;
        }
        double[] first = pts.get(0);
        path.moveTo(first[0], first[1]);
        if (n == 2) {
            double[] second = pts.get(1);
            path.lineTo(second[0], second[1]);
            if (closed) {
                path.closePath();
            }
            return path;
        }
        int last = closed ? n : n - 1;
        for (int i = 0; i < last; i++) {
            double[] p0 = pts.get(closed ? (i - 1 + n) % n : Math.max(0, i - 1));
            double[] p1 = pts.get(i % n);
            double[] p2 = pts.get((i + 1) % n);
            double[] p3 = pts.get(closed ? (i + 2) % n : Math.min(n - 1, i + 2));
            double c1x = p1[0] + (p2[0] - p0[0]) / 6.0;
            double c1z = p1[1] + (p2[1] - p0[1]) / 6.0;
            double c2x = p2[0] - (p3[0] - p1[0]) / 6.0;
            double c2z = p2[1] - (p3[1] - p1[1]) / 6.0;
            path.curveTo(c1x, c1z, c2x, c2z, p2[0], p2[1]);
        }
        if (closed) {
            path.closePath();
        }
        return path;
    }

    public boolean isEmpty() {
        return this.area.isEmpty();
    }

    public void add(VectorShape other) {
        if (other != null && !other.area.isEmpty()) {
            this.area.add(other.area);
            invalidate();
        }
    }

    public void subtract(VectorShape other) {
        if (other != null && !other.area.isEmpty()) {
            this.area.subtract(other.area);
            invalidate();
        }
    }

    public void intersect(VectorShape other) {
        if (other == null || other.area.isEmpty()) {
            this.area.reset();
            invalidate();
            return;
        }
        this.area.intersect(other.area);
        invalidate();
    }

    public void clear() {
        this.area.reset();
        invalidate();
    }

    public void copyFrom(VectorShape other) {
        this.area.reset();
        if (other != null) {
            this.area.add(other.area);
        }
        invalidate();
    }

    public VectorShape copy() {
        return new VectorShape((Area) this.area.clone());
    }

    /** 包围盒；空形状返回 {@code null}。 */
    public Bounds bounds() {
        if (this.area.isEmpty()) {
            return null;
        }
        Rectangle2D r = this.area.getBounds2D();
        return new Bounds(r.getMinX(), r.getMinY(), r.getMaxX(), r.getMaxY());
    }

    /**
     * 面积（平方方块）。
     *
     * <p>鞋带公式直接套在 {@link #segments()} 给出的闭合折线上，和渲染共用同一份缓存，
     * 反复取面积不会每次重走一遍 {@link PathIterator}。</p>
     */
    public double measure() {
        double total = 0.0;
        for (double[] s : segments()) {
            total += s[0] * s[3] - s[2] * s[1];
        }
        return Math.abs(total) / 2.0;
    }

    /** 扁平化后的线段列表，每条是 {x0, z0, x1, z1}，渲染用它。结果缓存到形状再次被改动为止。 */
    public List<double[]> segments() {
        if (this.segmentCache != null) {
            return this.segmentCache;
        }
        List<double[]> out = new ArrayList<>();
        if (this.area.isEmpty()) {
            this.segmentCache = out;
            return out;
        }
        PathIterator it = this.area.getPathIterator(null, FLATTEN);
        double[] coords = new double[6];
        double startX = 0.0;
        double startZ = 0.0;
        double curX = 0.0;
        double curZ = 0.0;
        while (!it.isDone()) {
            int type = it.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO -> {
                    startX = curX = coords[0];
                    startZ = curZ = coords[1];
                }
                case PathIterator.SEG_LINETO -> {
                    out.add(new double[] { curX, curZ, coords[0], coords[1] });
                    curX = coords[0];
                    curZ = coords[1];
                }
                case PathIterator.SEG_CLOSE -> {
                    if (curX != startX || curZ != startZ) {
                        out.add(new double[] { curX, curZ, startX, startZ });
                    }
                    curX = startX;
                    curZ = startZ;
                }
                default -> {
                }
            }
            it.next();
        }
        this.segmentCache = out;
        return out;
    }

    /**
     * 扁平化后的闭合轮廓点表：每个元素是一个子路径的顶点序列 {@code [x0,z0,x1,z1,...]}。
     *
     * <p>存档写的就是这个；读回来用 {@link #ofPoints(List)} 还原。洞（比如环形区划的内圈）
     * 会作为方向相反的额外子路径出现，配合 {@code WIND_NON_ZERO} 正好还原成洞。</p>
     */
    public List<double[]> outlinePoints() {
        List<double[]> parts = new ArrayList<>();
        if (this.area.isEmpty()) {
            return parts;
        }
        PathIterator it = this.area.getPathIterator(null, FLATTEN);
        double[] coords = new double[6];
        List<Double> current = new ArrayList<>();
        while (!it.isDone()) {
            int type = it.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO -> {
                    flushPart(parts, current);
                    current.add(coords[0]);
                    current.add(coords[1]);
                }
                case PathIterator.SEG_LINETO -> {
                    current.add(coords[0]);
                    current.add(coords[1]);
                }
                case PathIterator.SEG_CLOSE -> flushPart(parts, current);
                default -> {
                }
            }
            it.next();
        }
        flushPart(parts, current);
        return parts;
    }

    private static void flushPart(List<double[]> parts, List<Double> current) {
        if (current.size() >= 6) {
            double[] part = new double[current.size()];
            for (int i = 0; i < part.length; i++) {
                part[i] = current.get(i);
            }
            parts.add(part);
        }
        current.clear();
    }

    /** {@link #outlinePoints()} 的逆运算。 */
    public static VectorShape ofPoints(List<double[]> parts) {
        if (parts == null || parts.isEmpty()) {
            return empty();
        }
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        boolean any = false;
        for (double[] part : parts) {
            if (part == null || part.length < 6) {
                continue;
            }
            path.moveTo(part[0], part[1]);
            for (int i = 2; i + 1 < part.length; i += 2) {
                path.lineTo(part[i], part[i + 1]);
            }
            path.closePath();
            any = true;
        }
        return any ? of(path) : empty();
    }
}
