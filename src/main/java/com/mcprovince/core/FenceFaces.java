package com.mcprovince.core;

import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把一堆线段当成一张平面图，找出「包含某个点的最小闭合区域」。
 *
 * <p>油漆桶要干的就是这件事：用户随手画几条线围出一个三角形，该被填色的只有那个三角形，
 * 画出去的长线本身只是辅助，填完就该消失。所以这里先把线段两两求交、在交点处切开，
 * 再沿着平面图找一圈面，取包含目标点、面积又最小的那个。</p>
 */
public final class FenceFaces {

    /** 端点吸附精度：比这更近的两个端点当成同一个节点。 */
    private static final double SNAP = 1.0E-4;

    /** 参与面提取的最大线段数；再多就认输（拖拽画出来的线可能上万条）。 */
    public static final int MAX_SEGMENTS = 4_000;

    private FenceFaces() {
    }

    /**
     * 找出包含 ({@code px}, {@code pz}) 的最小闭合区域。
     *
     * @return 围出来的那块区域；围栏没能围住这个点就返回 {@code null}
     */
    public static VectorShape faceAt(List<double[]> segments, double px, double pz) {
        if (segments == null || segments.size() < 3 || segments.size() > MAX_SEGMENTS) {
            return null;
        }
        List<double[]> edges = splitAtIntersections(segments);
        if (edges.size() < 3 || edges.size() > MAX_SEGMENTS * 4) {
            return null;
        }

        Map<String, Integer> ids = new HashMap<>();
        List<double[]> nodes = new ArrayList<>();
        List<int[]> arcs = new ArrayList<>();
        // 同一对节点之间只留一条弧。两条重合的边（玩家沿着已经存在的边界又描了一笔，
        // 或者围栏本身就重复了）是一对「去而复返」的边，最右转会绕着它们转出一个只有
        // 两条边的环，把原本好好的面顶掉——表现就是点了魔棒毫无反应。
        Set<Long> arcsSeen = new HashSet<>();
        for (double[] edge : edges) {
            int a = nodeId(ids, nodes, edge[0], edge[1]);
            int b = nodeId(ids, nodes, edge[2], edge[3]);
            if (a == b) {
                continue;
            }
            long key = a < b
                    ? ((long) a << 32) | (b & 0xFFFFFFFFL)
                    : ((long) b << 32) | (a & 0xFFFFFFFFL);
            if (arcsSeen.add(key)) {
                arcs.add(new int[] { a, b });
            }
        }
        if (arcs.size() < 3) {
            return null;
        }
        return smallestFace(nodes, arcs, px, pz);
    }

    // ————————————————— 求交与切分 —————————————————

    /** 在每两条线段的交点处把线段切开，让它们只在自己端点上碰头。 */
    private static List<double[]> splitAtIntersections(List<double[]> segments) {
        int n = segments.size();
        List<List<Double>> cuts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            cuts.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            double[] a = segments.get(i);
            double aMinX = Math.min(a[0], a[2]);
            double aMaxX = Math.max(a[0], a[2]);
            double aMinZ = Math.min(a[1], a[3]);
            double aMaxZ = Math.max(a[1], a[3]);
            for (int j = i + 1; j < n; j++) {
                double[] b = segments.get(j);
                // 包围盒都不重叠的两段不可能相交，直接跳过。围栏里成片都是轴对齐的格线，
                // 不剪枝的话这几千条边两两求交能把一次点击拖成好几秒。
                if (aMaxX < Math.min(b[0], b[2]) || Math.max(b[0], b[2]) < aMinX
                        || aMaxZ < Math.min(b[1], b[3]) || Math.max(b[1], b[3]) < aMinZ) {
                    continue;
                }
                double[] hit = intersect(a, b);
                if (hit == null) {
                    continue;
                }
                if (hit[0] > 1.0E-9 && hit[0] < 1.0 - 1.0E-9) {
                    cuts.get(i).add(hit[0]);
                }
                if (hit[1] > 1.0E-9 && hit[1] < 1.0 - 1.0E-9) {
                    cuts.get(j).add(hit[1]);
                }
            }
        }

        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] a = segments.get(i);
            List<Double> ts = cuts.get(i);
            ts.add(0.0);
            ts.add(1.0);
            Collections.sort(ts);
            for (int k = 0; k + 1 < ts.size(); k++) {
                double t0 = ts.get(k);
                double t1 = ts.get(k + 1);
                if (t1 - t0 < 1.0E-9) {
                    continue;
                }
                out.add(new double[] {
                    lerp(a[0], a[2], t0), lerp(a[1], a[3], t0),
                    lerp(a[0], a[2], t1), lerp(a[1], a[3], t1)
                });
            }
        }
        return out;
    }

    /** 两条线段的交点参数 {t1, t2}；平行或不相交返回 {@code null}。 */
    private static double[] intersect(double[] a, double[] b) {
        double ax = a[2] - a[0];
        double az = a[3] - a[1];
        double bx = b[2] - b[0];
        double bz = b[3] - b[1];
        double denom = ax * bz - az * bx;
        if (Math.abs(denom) < 1.0E-12) {
            return null;
        }
        double dx = b[0] - a[0];
        double dz = b[1] - a[1];
        double t = (dx * bz - dz * bx) / denom;
        double u = (dx * az - dz * ax) / denom;
        if (t < 0.0 || t > 1.0 || u < 0.0 || u > 1.0) {
            return null;
        }
        return new double[] { t, u };
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    // ————————————————— 平面图的面 —————————————————

    private static int nodeId(Map<String, Integer> ids, List<double[]> nodes, double x, double z) {
        String key = Math.round(x / SNAP) + "," + Math.round(z / SNAP);
        Integer found = ids.get(key);
        if (found != null) {
            return found;
        }
        int id = nodes.size();
        nodes.add(new double[] { x, z });
        ids.put(key, id);
        return id;
    }

    /** 从包含目标点的所有面里挑面积最小的那个。 */
    private static VectorShape smallestFace(List<double[]> nodes, List<int[]> arcs, double px, double pz) {
        int n = nodes.size();
        List<List<Integer>> outEdges = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            outEdges.add(new ArrayList<>());
        }

        List<int[]> directed = new ArrayList<>();
        for (int[] arc : arcs) {
            directed.add(new int[] { arc[0], arc[1] });
            directed.add(new int[] { arc[1], arc[0] });
        }
        for (int i = 0; i < directed.size(); i++) {
            outEdges.get(directed.get(i)[0]).add(i);
        }

        int m = directed.size();
        int[] nextEdge = new int[m];
        for (int i = 0; i < m; i++) {
            int[] edge = directed.get(i);
            double back = angle(nodes.get(edge[1]), nodes.get(edge[0]));
            int best = -1;
            double bestDelta = Double.MAX_VALUE;
            for (int j : outEdges.get(edge[1])) {
                if (j == i) {
                    continue;
                }
                double delta = angle(nodes.get(edge[1]), nodes.get(directed.get(j)[1])) - back;
                while (delta <= 1.0E-12) {
                    delta += Math.PI * 2.0;
                }
                if (delta < bestDelta) {
                    bestDelta = delta;
                    best = j;
                }
            }
            nextEdge[i] = best < 0 ? i : best;
        }

        boolean[] used = new boolean[m];
        VectorShape best = null;
        double bestArea = Double.MAX_VALUE;
        for (int start = 0; start < m; start++) {
            if (used[start]) {
                continue;
            }
            List<Integer> loop = new ArrayList<>();
            int cur = start;
            while (!used[cur]) {
                used[cur] = true;
                loop.add(directed.get(cur)[0]);
                cur = nextEdge[cur];
                if (loop.size() > m) {
                    break;
                }
            }
            if (loop.size() < 3) {
                continue;
            }
            VectorShape shape = polygon(nodes, loop);
            if (shape == null || shape.isEmpty()) {
                continue;
            }
            double area = shape.measure();
            if (area <= 1.0E-6 || area >= bestArea) {
                continue;
            }
            if (!shape.contains(px, pz)) {
                continue;
            }
            bestArea = area;
            best = shape;
        }
        return best;
    }

    private static double angle(double[] from, double[] to) {
        return Math.atan2(to[1] - from[1], to[0] - from[0]);
    }

    private static VectorShape polygon(List<double[]> nodes, List<Integer> loop) {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        double[] first = nodes.get(loop.get(0));
        path.moveTo(first[0], first[1]);
        for (int i = 1; i < loop.size(); i++) {
            double[] pt = nodes.get(loop.get(i));
            path.lineTo(pt[0], pt[1]);
        }
        path.closePath();
        return VectorShape.of(path);
    }
}
