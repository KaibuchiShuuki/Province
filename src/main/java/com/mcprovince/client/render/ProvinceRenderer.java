package com.mcprovince.client.render;

import com.mcprovince.client.ProvinceSettings;
import com.mcprovince.core.BorderStyle;
import com.mcprovince.core.Province;
import com.mcprovince.core.ProvinceStore;
import com.mcprovince.core.VectorShape;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 把矢量区划画到任意一张地图上。
 *
 * <p>MC 的 GUI 绘制只有轴对齐矩形，所以填充走屏幕空间扫描线：先把轮廓投影成屏幕线段，
 * 再用活跃边表按行配对填充。描边就把每条线段投影后沿像素落点，同一条线上的连续像素
 * 攒成一根长条再提交。</p>
 *
 * <p>一块区划的轮廓动辄上万条线段，缩到屏幕上以后绝大多数挤在同一个像素里。所以投影时
 * 顺手做三件事：裁到屏幕框内、把接在一起又短到看不见的边并进前一条、同一行的落墨
 * 合并成一次提交。填充和描边共用这一份投影结果，一块区划一帧只投影一遍。</p>
 */
public final class ProvinceRenderer {

    /**
     * 只管「画一个矩形」的绘制目标。
     *
     * <p>世界地图那一侧是 {@code GuiGraphicsExtractor}，小地图那一侧是 Xaero 的
     * {@code MinimapElementGraphics}（画在小地图自己的 FBO 里），两者没有共同父类，
     * 但都提供了同样签名的 {@code fill(int,int,int,int,int)}，取交集就够了。</p>
     */
    public interface Sink {

        void fill(int x0, int y0, int x1, int y1, int argb);
    }

    public interface Projection {
        double screenX(double worldX, double worldZ);

        double screenY(double worldX, double worldZ);

        double worldX(double screenX, double screenY);

        double worldZ(double screenX, double screenY);
    }

    /** 低于这个缩放就不画填充了，免得为几十个像素扫几万条边。 */
    private static final double MIN_FILL_PIXELS_PER_BLOCK = 0.02;

    /**
     * 单条边细分出来的点数上限。
     *
     * <p>这个值必须远大于一条边可能跨越的屏幕像素数。以前它只有 64，一条横平竖直的长边
     * 放大后就变成每隔几十像素才落一个点，设成实线看上去也是一串虚线。</p>
     */
    private static final int MAX_STEPS_PER_SEGMENT = 2048;

    /** 一次填充最多扫多少行。 */
    private static final int MAX_FILL_ROWS = 30_000;

    /**
     * 开了「智能降低边框精度」时，屏幕空间里短于这个长度的边接在前一条后面并进去。
     *
     * <p>误差只有一个像素的三分之一，肉眼看不出；但一个放大到满屏的区划，边界上
     * 一半以上的线段都比这还短，并掉它们等于把扫描线的活跃边砍掉一大截。</p>
     *
     * <p>这个阈值只管智能档。关掉智能档是要「精确刻画所有边界」的，一条边都不并，
     * 调用方会给 {@code projectEdges} 传 0。</p>
     */
    private static final double MIN_EDGE_PIXELS = 0.35;

    /**
     * 一次描边在屏幕上最多落多少笔。
     *
     * <p>超了就把采样间距整体放大——宁可线画得稀一点，也不能像以前那样画到一半
     * {@code return}，那条边界剩下的部分会整段消失。精确档会把这个预算整个关掉。</p>
     */
    public static final double MAX_STROKE_DOTS = 60_000.0;

    /**
     * 智能档里描边采样间距最多拉稀到这个倍数。
     *
     * <p>笔数超预算时本来按总长等比拉开间距；不设上限的话，一块超大区划缩得刚好铺满屏幕，
     * 按预算算出来的间隔能到上百像素，落墨就变成一串互不相连的点，边界看上去像虚线、
     * 甚至像整圈消失。宁可多几笔，也要让它连成线。</p>
     */
    private static final double MAX_STROKE_STEP_SCALE = 3.0;

    private ProvinceRenderer() {
    }

    /**
     * 把区划画到世界地图上。
     *
     * @param onlyTier 只画这一级的区划（编辑模式用）；{@code 0} 表示按设置里的「显示到第几级」画
     */
    public static void draw(GuiGraphicsExtractor graphics, Font font, ProvinceStore store, ProvinceSettings settings,
            Projection projection, double clipMinX, double clipMinY, double clipMaxX, double clipMaxY,
            double pixelsPerBlock, int onlyTier) {
        drawInto(graphics::fill, graphics, font, store, settings, projection,
                clipMinX, clipMinY, clipMaxX, clipMaxY, pixelsPerBlock, onlyTier);
    }

    /**
     * 只画填充与描边，不画地名。
     *
     * <p>小地图那一侧画在小地图自己的 FBO 里，手上只有 Xaero 的
     * {@code MinimapElementGraphics}，它没有字体绘制，所以单留这个不碰文字的入口。</p>
     */
    public static void drawShapes(Sink sink, ProvinceStore store, ProvinceSettings settings, Projection projection,
            double clipMinX, double clipMinY, double clipMaxX, double clipMaxY,
            double pixelsPerBlock, int onlyTier) {
        drawInto(sink, null, null, store, settings, projection,
                clipMinX, clipMinY, clipMaxX, clipMaxY, pixelsPerBlock, onlyTier);
    }

    /**
     * 把屏幕裁剪矩形反投影回世界坐标，取轴对齐包围盒，返回 {@code [minX, minZ, maxX, maxZ]}。
     *
     * <p>四个角必须都算。小地图没锁定北方时屏幕矩形相对世界是转着的，只取对角线上的两个角
     * 得到的 AABB 会偏小；而「南方为上」那一档更是会算成 {@code minZ > maxZ}，
     * 于是每一块区划都被当成不相交剔掉，整张小地图空白。</p>
     */
    public static double[] worldBounds(Projection projection,
            double clipMinX, double clipMinY, double clipMaxX, double clipMaxY) {
        double ax = projection.worldX(clipMinX, clipMinY);
        double az = projection.worldZ(clipMinX, clipMinY);
        double bx = projection.worldX(clipMaxX, clipMinY);
        double bz = projection.worldZ(clipMaxX, clipMinY);
        double cx = projection.worldX(clipMaxX, clipMaxY);
        double cz = projection.worldZ(clipMaxX, clipMaxY);
        double dx = projection.worldX(clipMinX, clipMaxY);
        double dz = projection.worldZ(clipMinX, clipMaxY);
        return new double[] {
                Math.min(Math.min(ax, bx), Math.min(cx, dx)),
                Math.min(Math.min(az, bz), Math.min(cz, dz)),
                Math.max(Math.max(ax, bx), Math.max(cx, dx)),
                Math.max(Math.max(az, bz), Math.max(cz, dz)),
        };
    }

    private static void drawInto(Sink sink, GuiGraphicsExtractor graphics, Font font,
            ProvinceStore store, ProvinceSettings settings,
            Projection projection, double clipMinX, double clipMinY, double clipMaxX, double clipMaxY,
            double pixelsPerBlock, int onlyTier) {
        if (store == null || store.isEmpty()) {
            return;
        }
        double[] worldClip = worldBounds(projection, clipMinX, clipMinY, clipMaxX, clipMaxY);
        double worldMinX = worldClip[0];
        double worldMinZ = worldClip[1];
        double worldMaxX = worldClip[2];
        double worldMaxZ = worldClip[3];

        boolean filled = settings.showFilled && pixelsPerBlock >= MIN_FILL_PIXELS_PER_BLOCK;
        int fillAlpha = settings.fillAlphaByte() << 24;
        boolean stroked = settings.borderStyle != BorderStyle.OFF;
        boolean named = settings.showNames && font != null && graphics != null;

        if (named) {
            // 序号标记得先知道「这一帧谁没把名字画上屏幕」，所以从这里从零开始记。
            // 小地图那条路（named = false）不碰这个标记，免得把世界地图刚记好的结果冲掉。
            for (Province province : store.allSorted()) {
                province.setNameDrawn(false);
            }
        }

        // 第一遍只铺色块和边框。
        for (Province province : store.allSorted()) {
            if (!inView(province, onlyTier, worldMinX, worldMinZ, worldMaxX, worldMaxZ)) {
                continue;
            }

            // 填充与描边各投影一遍。填充不能共用那份裁到框内的边表：扫描线填充要的是
            // 「每条边与水平线的交点 x」，这个 x 本来就可以落在屏幕外（提交命令前才夹回来）。
            // 左右一起裁掉的话，屏幕外的边会整条消失，交点少配一对，屏幕上就露出一条没填到的横缝。
            if (filled) {
                projectEdgesForFill(projection, province.shape().segments(), clipMinY, clipMaxY);
                fillProjected(sink, fillAlpha | (province.color() & 0xFFFFFF), pixelsPerBlock,
                        clipMinX, clipMinY, clipMaxX, clipMaxY, settings.smartFill);
            }
            if (stroked) {
                projectEdges(projection, province.shape().segments(), clipMinX, clipMinY, clipMaxX, clipMaxY,
                        settings.smartStroke ? MIN_EDGE_PIXELS : 0.0);
                strokeProjected(sink, province.color(), settings.borderWidthPixels(), settings.borderStyle,
                        clipMinX, clipMinY, clipMaxX, clipMaxY, MAX_STROKE_DOTS,
                        settings.smartStroke, pixelsPerBlock);
            }

        }

        if (named) {
            // 名字单独走第二遍：色块和边框全铺完了再写字。要是混在第一遍里，先画的那块的名字
            // 会被后画的邻居色块盖住——层级越低的区划越容易被压住。
            for (Province province : store.allSorted()) {
                if (!inView(province, onlyTier, worldMinX, worldMinZ, worldMaxX, worldMaxZ)) {
                    continue;
                }
                String label = province.displayName(settings.nameFormat);
                if (!label.isEmpty() && drawNames(graphics, font, projection, province.shape(), label,
                        clipMinX, clipMinY, clipMaxX, clipMaxY)) {
                    // 这一帧确实有名字落在屏幕上了，序号标记就别来抢位置。
                    province.setNameDrawn(true);
                }
            }
        }
    }

    /**
     * 这一帧要不要画这一块：空形状、层级／勾选、以及包围盒跟画面完全不挨着的，都跳过。
     *
     * <p>填充边框那一遍和写名字那一遍用的是同一套判据，抽出来才不会两边走岔。</p>
     */
    private static boolean inView(Province province, int onlyTier,
            double worldMinX, double worldMinZ, double worldMaxX, double worldMaxZ) {
        if (province.shape().isEmpty()) {
            return false;
        }
        if (onlyTier > 0) {
            // 编辑时只认层级：正在改的那一级不管有没有勾上都要看得见。
            if (province.tier() != onlyTier) {
                return false;
            }
        } else if (!province.visible()) {
            return false;
        }
        VectorShape.Bounds bounds = province.shape().bounds();
        if (bounds == null) {
            return false;
        }
        return !(bounds.maxX() < worldMinX || bounds.minX() > worldMaxX
                || bounds.maxZ() < worldMinZ || bounds.minZ() > worldMaxZ);
    }

    // ————————————————— 投影后的屏幕线段表 —————————————————

    /*
     * 渲染全部发生在客户端主线程上，所以这几组数组直接就地复用：一块区划的轮廓动辄上万条，
     * 每帧给每块区划各分配四个 double 数组，光是把这些内存扔掉就够 GC 忙的了。
     */
    private static double[] edgeX0 = new double[512];
    private static double[] edgeY0 = new double[512];
    private static double[] edgeX1 = new double[512];
    private static double[] edgeY1 = new double[512];
    private static int edgeCount;
    /** 投影并抽稀之后，所有边的屏幕长度之和。描边的采样间距按它算。 */
    private static double edgeLength;

    private static void ensureEdgeCapacity(int n) {
        if (edgeX0.length >= n) {
            return;
        }
        int size = Math.max(edgeX0.length * 2, n);
        edgeX0 = new double[size];
        edgeY0 = new double[size];
        edgeX1 = new double[size];
        edgeY1 = new double[size];
    }

    private static void pushEdge(double x0, double y0, double x1, double y1) {
        edgeX0[edgeCount] = x0;
        edgeY0[edgeCount] = y0;
        edgeX1[edgeCount] = x1;
        edgeY1[edgeCount] = y1;
        edgeCount++;
    }

    /**
     * 把世界坐标的线段表投影到屏幕，裁到裁剪框内，再把接在一起、短到看不见的边并进前一条。
     *
     * <p>裁剪框外放一像素的余量，免得边界上那一圈正好卡在半像素上的填充缺一条缝。</p>
     *
     * @param minEdgePixels 短到这以下的边并进前一条；要精确刻画时调用方传 0
     */
    private static void projectEdges(Projection projection, List<double[]> segments,
            double clipMinX, double clipMinY, double clipMaxX, double clipMaxY, double minEdgePixels) {
        edgeCount = 0;
        edgeLength = 0.0;
        int n = segments.size();
        ensureEdgeCapacity(n);
        double left = clipMinX - 1.0;
        double top = clipMinY - 1.0;
        double right = clipMaxX + 1.0;
        double bottom = clipMaxY + 1.0;

        // 上一条留下来的边的终点。和它严丝合缝接上的短边会被并进去——判断用精确相等：
        // 同一个顶点投影两次得到的是同一个数，而不同子路径的端点不会正好重合到这一步。
        double pendingX = Double.NaN;
        double pendingY = Double.NaN;
        for (int i = 0; i < n; i++) {
            double[] s = segments.get(i);
            double ax = projection.screenX(s[0], s[1]);
            double ay = projection.screenY(s[0], s[1]);
            double bx = projection.screenX(s[2], s[3]);
            double by = projection.screenY(s[2], s[3]);
            if (!clipSegment(ax, ay, bx, by, left, top, right, bottom)) {
                pendingX = Double.NaN;
                pendingY = Double.NaN;
                continue;
            }
            double cx0 = clipOut[0];
            double cy0 = clipOut[1];
            double cx1 = clipOut[2];
            double cy1 = clipOut[3];
            double length = Math.hypot(cx1 - cx0, cy1 - cy0);
            if (length < minEdgePixels && edgeCount > 0 && cx0 == pendingX && cy0 == pendingY) {
                edgeX1[edgeCount - 1] = cx1;
                edgeY1[edgeCount - 1] = cy1;
                pendingX = cx1;
                pendingY = cy1;
                continue;
            }
            pushEdge(cx0, cy0, cx1, cy1);
            pendingX = cx1;
            pendingY = cy1;
        }

        for (int i = 0; i < edgeCount; i++) {
            edgeLength += Math.hypot(edgeX1[i] - edgeX0[i], edgeY1[i] - edgeY0[i]);
        }
    }

    /**
     * 只按上下边界裁剪的投影，专供填充用。
     *
     * <p>扫描线填充要的是「每条边和水平线的交点 x」。这个 x 落在屏幕左右之外完全没关系——
     * 提交绘制命令之前会把两端夹回裁剪框。反过来，如果左右也一起裁掉，屏幕外那些边就整条
     * 消失了：交点少配一对，屏幕上反而多出一条没填到的横缝。区划一大（放大到超出屏幕，
     * 或者小地图那种缩得很小的情形）就满屏横条，根子就在这里。</p>
     */
    private static void projectEdgesForFill(Projection projection, List<double[]> segments,
            double clipMinY, double clipMaxY) {
        edgeCount = 0;
        edgeLength = 0.0;
        int n = segments.size();
        ensureEdgeCapacity(n);
        double top = clipMinY - 1.0;
        double bottom = clipMaxY + 1.0;
        for (int i = 0; i < n; i++) {
            double[] s = segments.get(i);
            double ax = projection.screenX(s[0], s[1]);
            double ay = projection.screenY(s[0], s[1]);
            double bx = projection.screenX(s[2], s[3]);
            double by = projection.screenY(s[2], s[3]);
            if (!clipVertically(ax, ay, bx, by, top, bottom)) {
                continue;
            }
            pushEdge(clipOut[0], clipOut[1], clipOut[2], clipOut[3]);
        }
    }

    /** 把线段按上下两条边界收窄；整条都在上下之外返回 {@code false}。结果写进 {@link #clipOut}。 */
    private static boolean clipVertically(double x0, double y0, double x1, double y1,
            double top, double bottom) {
        double dy = y1 - y0;
        double t0 = 0.0;
        double t1 = 1.0;
        if (dy == 0.0) {
            if (y0 < top || y0 > bottom) {
                return false;
            }
        } else {
            double r0 = (top - y0) / dy;
            double r1 = (bottom - y0) / dy;
            if (r0 > r1) {
                double swap = r0;
                r0 = r1;
                r1 = swap;
            }
            if (r0 > t0) {
                t0 = r0;
            }
            if (r1 < t1) {
                t1 = r1;
            }
            if (t0 > t1) {
                return false;
            }
        }
        clipOut[0] = x0 + t0 * (x1 - x0);
        clipOut[1] = y0 + t0 * dy;
        clipOut[2] = x0 + t1 * (x1 - x0);
        clipOut[3] = y0 + t1 * dy;
        return true;
    }

    private static final double[] clipOut = new double[4];

    /**
     * Liang–Barsky 把线段裁到矩形里；整条都在外面返回 {@code false}。
     *
     * <p>结果写进 {@link #clipOut}。返回数组会每帧分配几万个小对象，这里就不讲究了。</p>
     */
    private static boolean clipSegment(double x0, double y0, double x1, double y1,
            double left, double top, double right, double bottom) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double t0 = 0.0;
        double t1 = 1.0;

        // 四条边依次收窄参数区间：[p, q] 里的 p 是方向的负数，q 是到边界的距离。
        for (int side = 0; side < 4; side++) {
            double p;
            double q;
            switch (side) {
                case 0 -> {
                    p = -dx;
                    q = x0 - left;
                }
                case 1 -> {
                    p = dx;
                    q = right - x0;
                }
                case 2 -> {
                    p = -dy;
                    q = y0 - top;
                }
                default -> {
                    p = dy;
                    q = bottom - y0;
                }
            }
            if (p == 0.0) {
                if (q < 0.0) {
                    return false;
                }
                continue;
            }
            double r = q / p;
            if (p < 0.0) {
                if (r > t1) {
                    return false;
                }
                if (r > t0) {
                    t0 = r;
                }
            } else {
                if (r < t0) {
                    return false;
                }
                if (r < t1) {
                    t1 = r;
                }
            }
        }
        clipOut[0] = x0 + t0 * dx;
        clipOut[1] = y0 + t0 * dy;
        clipOut[2] = x0 + t1 * dx;
        clipOut[3] = y0 + t1 * dy;
        return true;
    }

    /**
     * 每块互不相连的封闭区域各画一个名牌。
     *
     * <p>有飞地时一块区划是好几座分离的岛，只按整体包围盒算既会漏掉小的那块，
     * 锚点还可能落到两块地之间的空白上。这里逐块处理：每块自己的包围盒决定
     * 名字装不装得下，每块自己的内部点决定名字落在哪。</p>
     *
     * <p>名字是整行居中画上去的，落点只保证「中心在区划里」，两端允许稍微探出去一点——
     * 换来的是细长地块也有名字。要是要求整行字连边角都落在里面，一条几格宽的长条
     * 就永远不可能有名字。</p>
     */
    private static boolean drawNames(GuiGraphicsExtractor graphics, Font font, Projection projection,
                                  VectorShape shape, String label,
                                  double clipMinX, double clipMinY, double clipMaxX, double clipMaxY) {
        double pixelsPerBlock = Math.abs(projection.screenX(1.0, 0.0) - projection.screenX(0.0, 0.0));
        double textW = font.width(label);
        double textH = font.lineHeight;
        if (pixelsPerBlock > 1.0E-9) {
            textW /= pixelsPerBlock;
            textH /= pixelsPerBlock;
        } else {
            textW = 0.0;
            textH = 0.0;
        }

        // 返回「这一块的名字到底有没有落到屏幕上」：一块都没画出来时，序号标记会顶上来。
        boolean drawn = false;
        for (VectorShape part : shape.components()) {
            VectorShape.Bounds bounds = part.bounds();
            if (bounds == null) {
                continue;
            }
            double boxW = Math.abs(projection.screenX(bounds.maxX(), bounds.centerZ())
                    - projection.screenX(bounds.minX(), bounds.centerZ()));
            // 屏幕上横向装不下这行字就不画——缩得太远时名字会挤成一团，连成一片反而看不清。
            // 「装得下」到底算多宽由设置里那条滑条说了算（{@code nameSlack}：正数就是允许字比
            // 地块宽出去几个像素，负数反过来要求地块比字宽）。导出图用的是同一个值。
            // 纵向不设门槛：细长的地块本来就窄，只按高度卡的话它永远没有名字，
            // 「中心落在条带上、两端各探出去一点」才是这种地块该有的样子。
            //
            // 真判它装不下之前，下面那套落点搜索已经先替它找过一遍宽敞的地方了：中心塞不下
            // 就在整块地上重新采样，第一轮只认「整行字四角都在里面」的点，找不着才退到
            // 「探出去最少」的那一档。两轮都没结果才轮到序号。
            if (boxW + ProvinceSettings.get().nameSlack < font.width(label)) {
                continue;
            }
            double[] anchor = nameAnchorForText(part, bounds, textW / 2.0, textH / 2.0, shape);
            if (anchor == null) {
                continue;
            }
            int textX = (int) Math.round(projection.screenX(anchor[0], anchor[1]));
            int textY = (int) Math.round(projection.screenY(anchor[0], anchor[1]));
            if (textX >= clipMinX - 100 && textX <= clipMaxX + 100
                    && textY >= clipMinY - 20 && textY <= clipMaxY + 20) {
                graphics.centeredText(font, label, textX, textY, 0xFFFFFFFF);
                drawn = true;
            }
        }
        return drawn;
    }

    /**
     * 给名牌找一个落点。
     *
     * <p>硬条件只有一条：落点本身得在这块区划里面。</p>
     *
     * <p>挑点的规矩分两轮。第一轮只要「整行字连四角都落在区划里」的候选，取离包围盒中心
     * 最近的那个：这么挑出来的名字一格都不出界。空位充足的时候（倒三角形那种中心正好
     * 落在斜边外面的地块就是典型）就不会再往边界上挤了。一轮下来一个都没有，第二轮才
     * 退而求其次：取命中采样点最多的候选，命中一样多再比谁离中心近——三格宽的长条就是
     * 这一类，它横竖都要探出去，那就让名字压在条带上。</p>
     *
     * <p>网格取 12x12。以前是 9x9，大块区划的步长能有几十格，一片正好摆得下字的小空位
     * 有可能整段被跳过去，于是明明还有地方却还是挑了个出界的点。多这一圈只多几百次
     * {@code contains}。</p>
     *
     * <p>导出的 PNG 也走这一套：屏幕上怎么摆，图里就怎么摆。</p>
     */
    public static double[] nameAnchorForText(VectorShape shape, VectorShape.Bounds bounds,
            double halfW, double halfH, VectorShape inside) {
        double cx = bounds.centerX();
        double cz = bounds.centerZ();
        // 中心那一处就装得下的话没什么好挑的。
        if (shape.contains(cx, cz) && fitScore(inside, cx, cz, halfW, halfH) >= FIT_PROBES.length) {
            return new double[] {cx, cz};
        }

        double stepX = Math.max(1.0, bounds.width() / 11.0);
        double stepZ = Math.max(1.0, bounds.height() / 11.0);
        double[] full = null;
        double fullDist = Double.MAX_VALUE;
        double[] loose = null;
        double looseFit = -1.0;
        double looseDist = Double.MAX_VALUE;
        for (double z = bounds.minZ(); z <= bounds.maxZ(); z += stepZ) {
            for (double x = bounds.minX(); x <= bounds.maxX(); x += stepX) {
                if (!shape.contains(x, z)) {
                    continue;
                }
                double distance = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                double fit = fitScore(inside, x, z, halfW, halfH);
                if (fit >= FIT_PROBES.length) {
                    if (distance < fullDist) {
                        fullDist = distance;
                        full = new double[] {x, z};
                    }
                    continue;
                }
                if (fit > looseFit || (fit == looseFit && distance < looseDist)) {
                    looseFit = fit;
                    looseDist = distance;
                    loose = new double[] {x, z};
                }
            }
        }
        return full != null ? full : loose;
    }

    /** 采样点表：中心 + 四角 + 四边中点。 */
    private static final double[][] FIT_PROBES = {
        {0.0, 0.0},
        {-1.0, -1.0}, {1.0, -1.0}, {-1.0, 1.0}, {1.0, 1.0},
        {0.0, -1.0}, {0.0, 1.0}, {-1.0, 0.0}, {1.0, 0.0},
    };

    /** 文字包围盒上 9 个采样点有几个落在形状里。 */
    private static double fitScore(VectorShape inside, double x, double z, double halfW, double halfH) {
        if (halfW <= 0.0 && halfH <= 0.0) {
            return inside.contains(x, z) ? FIT_PROBES.length : 0.0;
        }
        double hits = 0.0;
        for (double[] probe : FIT_PROBES) {
            if (inside.contains(x + probe[0] * halfW, z + probe[1] * halfH)) {
                hits += 1.0;
            }
        }
        return hits;
    }

    // ————————————————— 自动外框 —————————————————

    /** 虚线与点划线：沿轮廓的累计屏幕长度决定哪一段落墨。 */
    private static boolean inInk(BorderStyle style, double pos) {
        switch (style) {
            case LINE:
                return true;
            case DASH:
                return pos % 10.0 < 6.0;
            case DASH_DOT: {
                double p = pos % 18.5;
                return p < 9.0 || (p >= 13.0 && p < 14.5);
            }
            default:
                return false;
        }
    }

    /**
     * 给一个区划自动描一圈外框。
     *
     * <p>矢量图形本身没有线宽也没有虚线，这里画的其实是屏幕像素：把每条轮廓边投影后
     * 按像素细分，再用累计长度决定这一点落不落墨。</p>
     */
    public static void strokeStyled(Sink sink, Projection projection, VectorShape shape,
            int rgb, int thicknessPixels, BorderStyle style,
            double clipMinX, double clipMinY, double clipMaxX, double clipMaxY) {
        strokeStyled(sink, projection, shape, rgb, thicknessPixels, style,
                clipMinX, clipMinY, clipMaxX, clipMaxY, true);
    }

    /**
     * 同上，但可以指定要不要走「智能降低边框精度」那档。
     *
     * @param smart 为真时按缩放把不可见的短边并掉、采样间距允许拉稀；
     *              为假时一条边都不并、每个屏幕像素都采，精确刻画所有边界
     */
    public static void strokeStyled(Sink sink, Projection projection, VectorShape shape,
            int rgb, int thicknessPixels, BorderStyle style,
            double clipMinX, double clipMinY, double clipMaxX, double clipMaxY, boolean smart) {
        if (style == BorderStyle.OFF || shape == null || shape.isEmpty()) {
            return;
        }
        double pixelsPerBlock = Math.abs(projection.screenX(1.0, 0.0) - projection.screenX(0.0, 0.0));
        projectEdges(projection, shape.segments(), clipMinX, clipMinY, clipMaxX, clipMaxY,
                smart ? MIN_EDGE_PIXELS : 0.0);
        // 精确档把预算关掉（budget 传 0），strokeProjected 就会一直用 1 像素的采样间距。
        strokeProjected(sink, rgb, thicknessPixels, style, clipMinX, clipMinY, clipMaxX, clipMaxY,
                smart ? MAX_STROKE_DOTS : 0.0, smart, pixelsPerBlock);
    }

    /**
     * 用已经投影好的边表描一圈。采样间距按总长统一缩放，所以一整圈都画得出来，
     * 只是笔数超预算时线会稀一点。
     */
    private static void strokeProjected(Sink sink, int rgb, int thicknessPixels, BorderStyle style,
            double clipMinX, double clipMinY, double clipMaxX, double clipMaxY, double budget,
            boolean smart, double pixelsPerBlock) {
        int color = 0xFF000000 | (rgb & 0xFFFFFF);
        int radius = Math.max(0, Math.min(6, thicknessPixels / 2));
        int size = radius * 2 + 1;
        // 采样间距：默认一个屏幕像素采一次。只有开了智能档、而且笔数超出预算时才会拉稀，
        // 上限是为了不让一条线断成一串点（见下）。精确档预算为 0，这里恒等于 1。
        double stepScale = edgeLength > budget && budget > 0.0 ? edgeLength / budget : 1.0;
        if (smart) {
            if (stepScale > MAX_STROKE_STEP_SCALE) {
                stepScale = MAX_STROKE_STEP_SCALE;
            }
            // 间距不能超过笔宽的两倍：超了落墨就断成一串互不相连的点，边框看上去像虚线。
            // 这是智能档「宁可多采几笔也要连成线」的那条底线。
            double connectLimit = Math.max(2.0, size * 2.0);
            if (stepScale > connectLimit) {
                stepScale = connectLimit;
            }
        }

        runSink = sink;
        runColor = color;
        runSize = size;
        runRadius = radius;
        runActive = false;

        double phase = 0.0;
        for (int i = 0; i < edgeCount; i++) {
            double sx0 = edgeX0[i];
            double sy0 = edgeY0[i];
            double sx1 = edgeX1[i];
            double sy1 = edgeY1[i];
            double dx = sx1 - sx0;
            double dy = sy1 - sy0;
            double length = Math.hypot(dx, dy);
            if (!(length > 0.0)) {
                continue;
            }
            int steps = (int) Math.ceil(length / stepScale);
            if (steps > MAX_STEPS_PER_SEGMENT) {
                steps = MAX_STEPS_PER_SEGMENT;
            }
            // 偏横的边沿同一行往右并，偏竖的边沿同一列往下并：一条 800 像素的直边
            // 因此从 800 个 1xN 的矩形缩成一次提交。
            boolean horizontal = Math.abs(dx) >= Math.abs(dy);
            for (int k = 0; k <= steps; k++) {
                double t = (double) k / steps;
                if (!inInk(style, phase + length * t)) {
                    continue;
                }
                int px = (int) Math.floor(sx0 + dx * t);
                int py = (int) Math.floor(sy0 + dy * t);
                if (px < clipMinX - 1.0 || px > clipMaxX + 1.0 || py < clipMinY - 1.0 || py > clipMaxY + 1.0) {
                    continue;
                }
                runAdd(px, py, horizontal);
            }
            // 这里故意不 flush：相邻两条边的落墨常常接着，攒着能省下不少提交。
            // runAdd 自己会在换行 / 换列 / 换方向时把攒好的交出去。
            phase += length;
        }
        flushRun();
        runSink = null;
    }

    /*
     * 落墨的合并缓冲。同一条边的落点是一串紧挨着的像素，攒成一根长条就够了；
     * 换行（或换列）时先把攒好的交出去。放静态字段是为了省掉每次描边都新建对象。
     */
    private static Sink runSink;
    private static int runColor;
    private static int runSize;
    private static int runRadius;
    private static boolean runActive;
    private static boolean runHorizontal;
    private static int runLine;
    private static int runFrom;
    private static int runTo;
    private static int runLast;

    private static void runAdd(int px, int py, boolean horizontal) {
        int from = horizontal ? px - runRadius : py - runRadius;
        int to = from + runSize;
        int line = horizontal ? py : px;
        int main = horizontal ? px : py;
        // 只有「同一条线、而且跟上一个落点紧挨着」才接着往长条上加。
        // 采样密的时候这等于把整条直边攒成一次提交；采样稀（缩得很远，间隔拉到十几个
        // 像素）的时候点与点之间隔着一大截，各自独立成块，不会被错连成一根横贯全图的长条。
        // 之前这里只判「新起点不超出旧终点」，一旦同一行上有两段互不相连的落墨，
        // 中间那段空白就会被一并涂上——那正是屏幕上那些莫名其妙的横条和串色。
        if (runActive && runHorizontal == horizontal && line == runLine
                && main >= runLast - 1 && main <= runLast + 1) {
            if (to > runTo) {
                runTo = to;
            }
            if (from < runFrom) {
                runFrom = from;
            }
            runLast = main;
            return;
        }
        flushRun();
        runActive = true;
        runHorizontal = horizontal;
        runLine = line;
        runFrom = from;
        runTo = to;
        runLast = main;
    }

    private static void flushRun() {
        if (!runActive || runSink == null) {
            runActive = false;
            return;
        }
        if (runHorizontal) {
            runSink.fill(runFrom, runLine - runRadius, runTo, runLine - runRadius + runSize, runColor);
        } else {
            runSink.fill(runLine - runRadius, runFrom, runLine - runRadius + runSize, runTo, runColor);
        }
        runActive = false;
    }

    // ————————————————— 扫描线填充 —————————————————

    /** 用偶奇规则把形状填充到屏幕上，允许按缩放倍率自动隔行采样。 */
    public static void fillShape(Sink sink, Projection projection, VectorShape shape, int argb,
            double clipMinX, double clipMinY, double clipMaxX, double clipMaxY) {
        fillShape(sink, projection, shape, argb, clipMinX, clipMinY, clipMaxX, clipMaxY, true);
    }

    /**
     * 用偶奇规则把形状填充到屏幕上。
     *
     * @param adaptive 是否允许「缩得越小、边越多，扫描线就隔得越开」这套自动降精度。
     *                 关掉就每个屏幕像素都扫一遍，画面最干净，大面积区划上会非常卡。
     */
    public static void fillShape(Sink sink, Projection projection, VectorShape shape, int argb,
            double clipMinX, double clipMinY, double clipMaxX, double clipMaxY, boolean adaptive) {
        if (shape == null || shape.isEmpty()) {
            return;
        }
        projectEdgesForFill(projection, shape.segments(), clipMinY, clipMaxY);
        double pixelsPerBlock = Math.abs(projection.screenX(1.0, 0.0) - projection.screenX(0.0, 0.0));
        fillProjected(sink, argb, pixelsPerBlock, clipMinX, clipMinY, clipMaxX, clipMaxY, adaptive);
    }

    private static int[] fillBucketHead = new int[512];
    private static int[] fillNext = new int[512];
    private static int[] fillActive = new int[512];
    private static int[] fillParked = new int[512];
    private static double[] fillActiveX = new double[512];

    private static void fillProjected(Sink sink, int argb, double pixelsPerBlock,
            double clipMinX, double clipMinY, double clipMaxX, double clipMaxY, boolean adaptive) {
        int n = edgeCount;
        if (n < 3) {
            return;
        }
        if (fillNext.length < n) {
            fillNext = new int[n];
            fillActive = new int[n];
            fillParked = new int[n];
            fillActiveX = new double[n];
        }

        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            minY = Math.min(minY, Math.min(edgeY0[i], edgeY1[i]));
            maxY = Math.max(maxY, Math.max(edgeY0[i], edgeY1[i]));
        }
        // 桶的基准行必须放在所有边的上方。之前基准取的是 clipMinY：区划一旦放大到超出屏幕，
        // 它的边全都在基准行之上，一条都装不进桶里，整个色块就凭空消失了。
        int yStart = Math.min((int) Math.floor(clipMinY), (int) Math.ceil(minY - 0.5));
        int yEnd = Math.min((int) Math.ceil(clipMaxY), (int) Math.ceil(maxY));
        if (yEnd <= yStart) {
            return;
        }
        if (yEnd - yStart > MAX_FILL_ROWS) {
            yStart = yEnd - MAX_FILL_ROWS;
        }
        int rows = yEnd - yStart + 1;
        if (fillBucketHead.length < rows) {
            fillBucketHead = new int[rows];
        }

        // 扫描线隔多密：缩得越远、边越多，就隔得越开。填充是一整片颜色，少扫几行肉眼看不出来，
        // 但逐行推进几万条活跃边的开销就省下来了。每一带仍然覆盖 rowStep 像素高，
        // 所以色块是连成一片的，不会留下横条纹。
        int rowStep = 1;
        if (adaptive) {
            if (n > 8_000) {
                rowStep = 2;
            }
            if (n > 20_000) {
                rowStep = 3;
            }
            if (n > 48_000) {
                rowStep = 4;
            }
            if (pixelsPerBlock < 0.5) {
                rowStep = Math.max(rowStep, 2);
            }
            if (pixelsPerBlock < 0.15) {
                rowStep = Math.max(rowStep, 4);
            }
        }

        // 每条边按它的最小 y 挂进桶里，扫描线走到哪一行就把哪些边激活。
        java.util.Arrays.fill(fillBucketHead, 0, rows, -1);
        for (int i = 0; i < n; i++) {
            if (Math.abs(edgeY1[i] - edgeY0[i]) < 1.0E-9) {
                continue;
            }
            // 起点在基准行之上的边一律挂进第 0 桶。基准行只在区划大到超出屏幕时才会被
            // 往后推（见上面的 MAX_FILL_ROWS），这时候一大堆长边的上端都落在基准行上方，
            // 按老写法 slot 是负数、直接 continue —— 那些边整条都不会被激活，
            // 于是「放大之后有些地方忽然不填色」。它们本来就已经在穿过基准行了，挂进 0 桶才对。
            int first = (int) Math.ceil(Math.min(edgeY0[i], edgeY1[i]) - 0.5);
            int slot = first - yStart;
            if (slot < 0) {
                slot = 0;
            } else if (slot >= rows) {
                continue;
            }
            fillNext[i] = fillBucketHead[slot];
            fillBucketHead[slot] = i;
        }

        int activeCount = 0;
        int clipLeft = (int) Math.floor(clipMinX);
        int clipRight = (int) Math.ceil(clipMaxX);

        for (int row = 0; row < rows; row += rowStep) {
            int bandEnd = Math.min(rows - 1, row + rowStep - 1);
            // 这一带里所有行的边都要激活：错过的行以后不会再回来。
            for (int r = row; r <= bandEnd; r++) {
                for (int e = fillBucketHead[r]; e != -1; e = fillNext[e]) {
                    fillActive[activeCount++] = e;
                }
            }
            double bandTop = yStart + row;
            double bandBottomY = yStart + bandEnd + 1;
            // 采样取整带的中间。
            double scanY = (bandTop + bandBottomY) * 0.5;

            // 分两拨：这一带真的穿过采样点的边排在前面算交点；还没轮到的寄在一边，下一带接着看。
            //
            // 这里以前是「采样点没穿过就当场扔掉」。扔掉的那些边不是没用——它们只是起点落在
            // 采样点之下，本该在后面几个带里接着用。结果每跨一个带就少几条边，交点少一对，
            // 填充里就漏出一条横穿整块的颜色缝。rowStep 越大丢得越狠，小地图缩放小、
            // rowStep 起步就是 2，所以那边更明显。
            int fillCount = 0;
            int parkedCount = 0;
            for (int k = 0; k < activeCount; k++) {
                int e = fillActive[k];
                double ya = edgeY0[e];
                double yb = edgeY1[e];
                double lo = Math.min(ya, yb);
                double hi = Math.max(ya, yb);
                if (hi <= bandTop) {
                    // 整带都在它下面了，这条边到此为止。
                    continue;
                }
                if (scanY < lo || scanY >= hi) {
                    fillParked[parkedCount++] = e;
                    continue;
                }
                double t = (scanY - ya) / (yb - ya);
                fillActiveX[fillCount] = edgeX0[e] + t * (edgeX1[e] - edgeX0[e]);
                fillActive[fillCount] = e;
                fillCount++;
            }
            for (int i = 0; i < parkedCount; i++) {
                fillActive[fillCount + i] = fillParked[i];
            }
            activeCount = fillCount + parkedCount;
            if (fillCount < 2) {
                continue;
            }
            sortActive(fillCount);
            int screenY = yStart + row;
            if (screenY < clipMinY - rowStep || screenY > clipMaxY + 1.0) {
                // 这一带整带都在可视区外：扫描线继续走（活跃边要按行推进），但一个绘制命令都不提交。
                continue;
            }
            int bandBottom = yStart + bandEnd + 1;
            for (int k = 0; k + 1 < fillCount; k += 2) {
                // 先夹回裁剪框再转成整数。交点的 x 可能远在屏幕外（填充用的边表不裁左右），
                // 直接 (int) 会溢出。
                double xa = Math.max(fillActiveX[k], clipLeft);
                double xb = Math.min(fillActiveX[k + 1], clipRight);
                if (xb > xa) {
                    sink.fill((int) Math.floor(xa), screenY, (int) Math.ceil(xb), bandBottom, argb);
                }
            }
        }
    }

    /**
     * 把活跃边按屏幕 x 排好序。
     *
     * <p>绝大多数行里，边只是沿着自己往上挪了一点点，顺序和上一行几乎一样，插入排序
     * 在这种近似有序的数组上基本就是一趟线性扫描。只有一上来就完全乱序（刚激活一大堆
     * 边）时才退化成平方级，所以先用一趟比较看出「已经有序」就直接返回。</p>
     */
    private static void sortActive(int count) {
        if (isSorted(count)) {
            return;
        }
        for (int i = 1; i < count; i++) {
            double xv = fillActiveX[i];
            int ev = fillActive[i];
            int j = i - 1;
            while (j >= 0 && fillActiveX[j] > xv) {
                fillActiveX[j + 1] = fillActiveX[j];
                fillActive[j + 1] = fillActive[j];
                j--;
            }
            fillActiveX[j + 1] = xv;
            fillActive[j + 1] = ev;
        }
    }

    private static boolean isSorted(int count) {
        for (int i = 1; i < count; i++) {
            if (fillActiveX[i - 1] > fillActiveX[i]) {
                return false;
            }
        }
        return true;
    }

    // ————————————————— 描边 —————————————————

    /**
     * 把形状的轮廓画出来。
     *
     * <p>沿屏幕像素把每条边细分成点链再落笔：边本身是数学意义上的直线段，落笔是一串
     * 单像素方块，看上去就是一条连续的细线；要是按包围盒铺矩形，斜线会变成一排扎眼的方块。</p>
     *
     * @param budget 落笔点数上限；超了就整体降采样，一圈照样画全
     * @return 投影后实际参与绘制的线段条数
     */
    public static int strokeShape(Sink sink, Projection projection, VectorShape shape,
            int color, double halfWidth, int budget) {
        if (shape == null || shape.isEmpty() || budget <= 0) {
            return 0;
        }
        projectEdges(projection, shape.segments(), -1.0E9, -1.0E9, 1.0E9, 1.0E9, MIN_EDGE_PIXELS);
        int radius = Math.max(0, (int) Math.round(halfWidth));
        // 编辑器预览走智能档：它自带 budget，缩得很小时得让间距机制能省下力气。
        strokeProjected(sink, color, radius * 2, BorderStyle.LINE,
                -1.0E9, -1.0E9, 1.0E9, 1.0E9, budget, true, 1.0);
        return edgeCount;
    }
}
