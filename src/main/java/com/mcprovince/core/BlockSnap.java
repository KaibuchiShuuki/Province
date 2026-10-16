package com.mcprovince.core;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 方块吸附：把矢量形状换算成「由完整方块拼出来」的形状。
 *
 * <p>规则只有一条 —— 方块 {@code (i, j)} 属于新形状，当且仅当它的中心
 * {@code (i + 0.5, j + 0.5)} 落在原形状里。于是新形状一定是整块方块的并集，
 * 边界自然贴着整数格线；「边界画在哪儿」和「这个方块归谁」永远不会打架。</p>
 *
 * <p>三个入口：{@link #snap(VectorShape)} 是任意形状的通用方块化（0.2.0 的曲线数据从这里
 * 搬过来，圆形工具也用它收尾）；{@link #brushStroke(List, double)} 沿线铺一条方块笔迹
 * （自由画笔与直线走它，好处是最细也留得下一格，细笔不会吸着吸着整条消失）；
 * {@link #radiusOf(double)} 把界面上的笔宽换算成整数半径。</p>
 */
public final class BlockSnap {

    /** 扫描线用的扁平化精度，和 {@link VectorShape} 内部保持一致。 */
    private static final double FLATTEN = 0.35;

    /** 一条笔迹最多摊开成多少个路径格点；原始采样点更多时先等距抽稀。 */
    private static final int MAX_PATH_POINTS = 512;

    /** 没有方块被选中的那一行。 */
    private static final List<int[]> NO_RUNS = List.of();

    private BlockSnap() {
    }

    // ——————————————— 通用方块化 ———————————————

    /**
     * 把任意形状吸附到方块格线上。
     *
     * <p>逐行扫描：取每一行的中线 {@code z = j + 0.5}，和形状轮廓求交，交点两两配对得到
     * 这一行上被盖住的 {@code x} 区间，再换算成整数方块号。边界本来就细到没有方块中心的
     * 形状会得到空结果——想保证留下痕迹就用 {@link #brushStroke}。</p>
     */
    public static VectorShape snap(VectorShape shape) {
        if (shape == null || shape.isEmpty()) {
            return VectorShape.empty();
        }
        Area area = shape.areaView();
        Rectangle2D box = area.getBounds2D();
        int rowFrom = (int) Math.floor(box.getMinY());
        int rowTo = (int) Math.ceil(box.getMaxY()) - 1;
        List<double[]> edges = edgesOf(area);
        if (rowTo < rowFrom || edges.isEmpty()) {
            return VectorShape.empty();
        }
        // 边按最小 z 升序：扫描线一路往下走时，新跨进来的边只要往后追加就行。
        edges.sort((a, b) -> Double.compare(a[1], b[1]));

        Builder builder = new Builder();
        List<double[]> active = new ArrayList<>();
        double[] hits = new double[8];
        int cursor = 0;
        for (int row = rowFrom; row <= rowTo; row++) {
            double z = row + 0.5;
            while (cursor < edges.size() && edges.get(cursor)[1] <= z) {
                active.add(edges.get(cursor++));
            }
            int kept = 0;
            for (int i = 0; i < active.size(); i++) {
                double[] edge = active.get(i);
                if (edge[1] <= z && edge[3] > z) {
                    active.set(kept++, edge);
                }
            }
            if (kept < active.size()) {
                active.subList(kept, active.size()).clear();
            }
            if (active.size() < 2) {
                builder.row(row, NO_RUNS);
                continue;
            }
            if (hits.length < active.size()) {
                hits = new double[active.size()];
            }
            for (int i = 0; i < active.size(); i++) {
                double[] edge = active.get(i);
                double t = (z - edge[1]) / (edge[3] - edge[1]);
                hits[i] = edge[0] + (edge[2] - edge[0]) * t;
            }
            Arrays.sort(hits, 0, active.size());
            builder.row(row, runsOf(hits, active.size()));
        }
        return builder.build();
    }

    /** 取形状的轮廓边，统一整理成「下面的端点在前」；水平边永远不会碰到半格扫描线，直接扔掉。 */
    private static List<double[]> edgesOf(Area area) {
        List<double[]> edges = new ArrayList<>();
        PathIterator it = area.getPathIterator(null, FLATTEN);
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
                    addEdge(edges, curX, curZ, coords[0], coords[1]);
                    curX = coords[0];
                    curZ = coords[1];
                }
                case PathIterator.SEG_CLOSE -> {
                    addEdge(edges, curX, curZ, startX, startZ);
                    curX = startX;
                    curZ = startZ;
                }
                default -> {
                }
            }
            it.next();
        }
        return edges;
    }

    private static void addEdge(List<double[]> edges, double x0, double z0, double x1, double z1) {
        if (z0 == z1) {
            return;
        }
        edges.add(z0 < z1 ? new double[] { x0, z0, x1, z1 } : new double[] { x1, z1, x0, z0 });
    }

    /** 把一行上的交点两两配成「被盖住的整数方块区间」。 */
    private static List<int[]> runsOf(double[] hits, int count) {
        List<int[]> runs = new ArrayList<>(count / 2 + 1);
        for (int i = 0; i + 1 < count; i += 2) {
            int from = (int) Math.ceil(hits[i] - 0.5);
            int to = (int) Math.ceil(hits[i + 1] - 0.5) - 1;
            if (to >= from) {
                runs.add(new int[] { from, to });
            }
        }
        return runs;
    }

    // ——————————————— 方块笔迹 ———————————————

    /** 笔宽换算成整数半径：笔迹总宽 {@code 2r + 1} 格，最细也有一格。 */
    public static int radiusOf(double width) {
        if (!(width > 1.0)) {
            return 0;
        }
        return (int) Math.round((width - 1.0) / 2.0);
    }

    /**
     * 沿着一串点铺一条有宽度的方块笔迹。
     *
     * <p>点先 floor 到格点，相邻点之间用整数阶梯补齐，再给路径上每个格点盖一个半径 {@code r}
     * 的方块圆盘。笔迹因此永远连成一片整块，不会出现「细线吸附之后整条消失」或者断成
     * 一串孤点那种事。</p>
     */
    public static VectorShape brushStroke(List<double[]> points, double width) {
        List<int[]> path = pathCells(points);
        if (path.isEmpty()) {
            return VectorShape.empty();
        }
        int radius = radiusOf(width);
        Map<Integer, List<int[]>> spans = new HashMap<>();
        int rowFrom = Integer.MAX_VALUE;
        int rowTo = Integer.MIN_VALUE;
        for (int[] cell : path) {
            for (int dz = -radius; dz <= radius; dz++) {
                double squared = (double) radius * radius - (double) dz * dz;
                int half = (int) Math.floor(Math.sqrt(Math.max(0.0, squared)) + 1.0E-9);
                int row = cell[1] + dz;
                spans.computeIfAbsent(row, k -> new ArrayList<>()).add(new int[] { cell[0] - half, cell[0] + half });
                rowFrom = Math.min(rowFrom, row);
                rowTo = Math.max(rowTo, row);
            }
        }

        Builder builder = new Builder();
        for (int row = rowFrom; row <= rowTo; row++) {
            List<int[]> list = spans.get(row);
            builder.row(row, list == null ? NO_RUNS : merge(list));
        }
        return builder.build();
    }

    /** 把一行上的格子区间排序、合并成互不相邻的若干段。 */
    private static List<int[]> merge(List<int[]> spans) {
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> runs = new ArrayList<>(spans.size());
        int from = spans.get(0)[0];
        int to = spans.get(0)[1];
        for (int i = 1; i < spans.size(); i++) {
            int[] span = spans.get(i);
            if (span[0] <= to + 1) {
                to = Math.max(to, span[1]);
            } else {
                runs.add(new int[] { from, to });
                from = span[0];
                to = span[1];
            }
        }
        runs.add(new int[] { from, to });
        return runs;
    }

    /** 采样点 -> 路径格点：抽稀、取整、相邻点之间补阶梯。 */
    private static List<int[]> pathCells(List<double[]> points) {
        List<int[]> cells = new ArrayList<>();
        if (points == null || points.isEmpty()) {
            return cells;
        }
        int n = points.size();
        int step = Math.max(1, n / MAX_PATH_POINTS);
        int[] previous = null;
        for (int i = 0; i < n; i += step) {
            int[] cell = cellOf(points.get(i));
            if (previous == null) {
                cells.add(cell);
            } else if (previous[0] != cell[0] || previous[1] != cell[1]) {
                connect(cells, previous, cell);
            }
            previous = cell;
        }
        // 尾巴必须带上：抽稀掉的那个点很可能正是笔的终点。
        int[] last = cellOf(points.get(n - 1));
        if (previous != null && (previous[0] != last[0] || previous[1] != last[1])) {
            connect(cells, previous, last);
        }
        return cells;
    }

    private static int[] cellOf(double[] point) {
        return new int[] { (int) Math.floor(point[0]), (int) Math.floor(point[1]) };
    }

    /** 整数阶梯：一格一格地从 {@code a} 走到 {@code b}，每一步只动一个轴。 */
    private static void connect(List<int[]> cells, int[] a, int[] b) {
        int dx = Math.abs(b[0] - a[0]);
        int dz = Math.abs(b[1] - a[1]);
        int sx = a[0] < b[0] ? 1 : -1;
        int sz = a[1] < b[1] ? 1 : -1;
        int error = dx - dz;
        int x = a[0];
        int z = a[1];
        while (x != b[0] || z != b[1]) {
            int doubled = error * 2;
            // 一步只动一个轴。两个条件都成立时（|dx| == |dz|）原本会走出对角步，
            // 而笔迹只有一格宽的时候（半径 0）对角相邻的两格只共一个角、并不连片，
            // 画出来的线看着就断成一串孤点。宁可走成阶梯，也要保持四连通。
            // 轴先到位之后就得改走另一个轴，否则会一步跨过终点。
            if (doubled > -dz && x != b[0]) {
                error -= dz;
                x += sx;
            } else if (z != b[1]) {
                error += dx;
                z += sz;
            } else {
                error -= dz;
                x += sx;
            }
            cells.add(new int[] { x, z });
        }
    }

    // ——————————————— 逐行拼形状 ———————————————

    /**
     * 逐行收方块区间，把上下完全相同的连续行并成一块矩形，最后一次性拼出形状。
     *
     * <p>并成矩形很要紧：一整片一万格见方的地就是一行 run 延续一万行，最后只出一个矩形；
     * 要是逐格铺方块，光构造 {@link Area} 就能卡上十几秒。</p>
     */
    private static final class Builder {

        private final List<double[]> rects = new ArrayList<>();
        private List<int[]> current;
        private int firstRow;
        private int lastRow;

        void row(int row, List<int[]> runs) {
            if (this.current != null && row == this.lastRow + 1 && same(this.current, runs)) {
                this.lastRow = row;
                return;
            }
            flush();
            this.current = runs;
            this.firstRow = row;
            this.lastRow = row;
        }

        VectorShape build() {
            flush();
            return this.rects.isEmpty() ? VectorShape.empty() : toShape(this.rects);
        }

        private void flush() {
            if (this.current == null) {
                return;
            }
            for (int[] run : this.current) {
                this.rects.add(new double[] {
                    run[0], this.firstRow, run[1] + 1.0, this.lastRow + 1.0
                });
            }
            this.current = null;
        }
    }

    private static boolean same(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i)[0] != b.get(i)[0] || a.get(i)[1] != b.get(i)[1]) {
                return false;
            }
        }
        return true;
    }

    /** 一批互不重叠的矩形拼成一块形状；同一方向绕行，{@code WIND_NON_ZERO} 下正好是它们的并集。 */
    private static VectorShape toShape(List<double[]> rects) {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (double[] rect : rects) {
            path.moveTo(rect[0], rect[1]);
            path.lineTo(rect[2], rect[1]);
            path.lineTo(rect[2], rect[3]);
            path.lineTo(rect[0], rect[3]);
            path.closePath();
        }
        return VectorShape.wrap(new Area(path));
    }
}
