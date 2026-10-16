package com.mcprovince;

import com.mcprovince.client.MapExporter;
import com.mcprovince.client.PixelFont;
import com.mcprovince.client.ProvinceClient;
import com.mcprovince.client.ProvinceEditor;
import com.mcprovince.client.ProvinceSettings;
import com.mcprovince.client.render.MinimapProjection;
import com.mcprovince.client.render.ProvinceRenderer;
import com.mcprovince.core.AdminLevels;
import com.mcprovince.core.BlockSnap;
import com.mcprovince.core.BorderStyle;
import com.mcprovince.core.FenceFaces;
import com.mcprovince.core.NameFormat;
import com.mcprovince.core.Province;
import com.mcprovince.core.ProvinceStore;
import com.mcprovince.core.VectorCodec;
import com.mcprovince.core.VectorShape;
import com.mcprovince.data.ProvinceNbt;
import com.mcprovince.edit.EditHistory;
import com.mcprovince.edit.EditSession;
import com.mcprovince.edit.EditTool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * 开发期自检：设置了环境变量 {@code MCPROVINCE_SELFTEST=1} 时，客户端启动会把核心逻辑跑一遍并写进日志。
 * 平时不执行，也不影响任何游戏行为。
 */
public final class ProvinceSelfTest {

    private static int passed;
    private static int failed;

    /** 延后用例（字形要等资源包加载完）用的日志与一次性开关。 */
    private static Logger deferredLog;
    private static boolean deferredDone;

    private ProvinceSelfTest() {
    }

    public static boolean isEnabled() {
        String value = System.getenv("MCPROVINCE_SELFTEST");
        return value != null && !value.isBlank() && !value.equals("0");
    }

    public static void run(Logger log) {
        passed = 0;
        failed = 0;
        log.info("[selftest] ======== mc-province self test ========");
        runOne(log, "VectorShape", ProvinceSelfTest::testVectorShape);
        runOne(log, "Curve", ProvinceSelfTest::testCurve);
        runOne(log, "VectorCodec", ProvinceSelfTest::testVectorCodec);
        runOne(log, "Hierarchy", ProvinceSelfTest::testHierarchy);
        runOne(log, "Bucket", ProvinceSelfTest::testBucket);
        runOne(log, "FenceFaces", ProvinceSelfTest::testFenceFaces);
        runOne(log, "BorderStyle", ProvinceSelfTest::testBorderStyle);
        runOne(log, "FillCoverage", ProvinceSelfTest::testFillCoverage);
        runOne(log, "StrokeAdaptive", ProvinceSelfTest::testStrokeAdaptive);
        runOne(log, "Constrain", ProvinceSelfTest::testConstrain);
        runOne(log, "ChildClip", ProvinceSelfTest::testChildClip);
        runOne(log, "UserLevels", ProvinceSelfTest::testUserLevels);
        runOne(log, "Components", ProvinceSelfTest::testComponents);
        runOne(log, "BlockSnap", ProvinceSelfTest::testBlockSnap);
        runOne(log, "BlockMigration", ProvinceSelfTest::testBlockMigration);
        runOne(log, "Nbt", ProvinceSelfTest::testNbt);
        runOne(log, "History", ProvinceSelfTest::testHistory);
        runOne(log, "EditorUi", ProvinceSelfTest::testEditorUi);
        runOne(log, "ExportNames", ProvinceSelfTest::testExportNames);
        log.info("[selftest] ======== done: {} passed, {} failed ========", passed, failed);
        // 字形那一条要等资源包加载完，挪到第一次客户端 tick 再跑。
        deferredLog = log;
        deferredDone = false;
    }

    /**
     * 延后的那一批用例：像素字从客户端资源包读字形，资源没加载完就读不到，所以放到第一次客户端 tick。
     * 非自检时 {@code deferredLog} 一直是 null，这里立刻返回。
     */
    public static void onClientTick(Minecraft minecraft) {
        Logger log = deferredLog;
        if (log == null || deferredDone) {
            return;
        }
        deferredDone = true;
        runOne(log, "PixelFont", () -> testPixelFont(minecraft));
        runOne(log, "MinimapHook", ProvinceSelfTest::testMinimapHook);
        log.info("[selftest] ======== deferred done: {} passed, {} failed ========", passed, failed);
    }

    // ---- 自动进世界验证小地图图层（只有 MCPROVINCE_AUTOWORLD=1 时才走这条路） ----

    private static int autoWorldStage;
    private static int autoWorldTicks;

    /**
     * 测试装置：自动打开测试存档、在玩家周围造两圈测试区划、截一张图再退出。
     *
     * <p>小地图那边「命令提交了却被 PIP 贴图盖住」这类问题只有看像素才算数，
     * 光靠自检函数是测不出来的，所以留了这条路。正式游玩时环境变量不在，这里是空转。</p>
     */
    public static void tickAutoWorld(Minecraft minecraft) {
        if (System.getenv("MCPROVINCE_AUTOWORLD") == null) {
            return;
        }
        autoWorldTicks++;
        if (minecraft.level == null) {
            if (autoWorldStage == 0 && autoWorldTicks > 120) {
                autoWorldStage = 1;
                McProvince.LOGGER.info("[autoworld] 打开测试存档");
                minecraft.createWorldOpenFlows().openWorld("New World",
                        () -> McProvince.LOGGER.error("[autoworld] 打开测试存档失败"));
            }
            return;
        }
        if (autoWorldStage == 1) {
            autoWorldStage = 2;
            autoWorldTicks = 0;
        }
        if (autoWorldStage == 2 && autoWorldTicks == 40) {
            ProvinceSettings.get().minimapEnabled = true;
            ProvinceStore store = ProvinceClient.data().store();
            if (minecraft.player != null && store != null) {
                int x = (int) Math.floor(minecraft.player.getX());
                int z = (int) Math.floor(minecraft.player.getZ());
                // 测试存档里可能留着上一轮造坏的形状，先清干净再重建。
                for (Province stale : new java.util.ArrayList<>(store.allSorted())) {
                    if (stale.parentId() == Province.NO_PARENT) {
                        store.delete(stale.id());
                    }
                }
                // ofRect 的参数是「左下角 + 右上角」，不是宽高 —— 写成 600/240 会造出偏到天边的巨块。
                Province root = store.create(Province.NO_PARENT,
                        VectorShape.ofRect(x - 300, z - 300, x + 300, z + 300),
                        "自动测试大区", "AutoTest", "province", 0x4FA3FF);
                store.create(root.id(), VectorShape.ofRect(x - 120, z - 120, x + 120, z + 120),
                        "自动测试小区", "AutoSub", "county", 0xFFAA33);
                McProvince.LOGGER.info("[autoworld] 已造测试区划 root={}", root.id());
            }
        }
        if (autoWorldStage == 2 && autoWorldTicks == 260) {
            McProvince.LOGGER.info("[autoworld] 截图");
            // 1.21.11 线没有 26.x 的 Screenshot.grab(Minecraft, boolean) 便捷重载，
            // 退回老三参数版本：截图目录 + 主渲染目标 + 完成回调（这里不需要回调内容）。
            Screenshot.grab(minecraft.gameDirectory, minecraft.getMainRenderTarget(), message -> {
            });
            autoWorldStage = 3;
        }
        if (autoWorldStage == 3 && autoWorldTicks > 340) {
            McProvince.LOGGER.info("[autoworld] 退出");
            minecraft.stop();
        }
    }

    private interface Case {
        void run();
    }

    private static void runOne(Logger log, String name, Case body) {
        int beforePassed = passed;
        int beforeFailed = failed;
        try {
            body.run();
        } catch (Throwable t) {
            failed++;
            log.error("[selftest] {} threw {}", name, t.toString());
        }
        log.info("[selftest] {} -> {} ok, {} failed", name, passed - beforePassed, failed - beforeFailed);
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            McProvince.LOGGER.error("[selftest] FAIL: {}", message);
        }
    }

    private static void testVectorShape() {
        VectorShape rect = VectorShape.ofRect(0, 0, 100, 100);
        check(!rect.isEmpty(), "rect shape is not empty");
        check(Math.abs(rect.measure() - 10_000.0) < 1.0, "rect area " + rect.measure());
        check(rect.contains(50, 50), "rect contains its middle");
        check(!rect.contains(150, 50), "rect excludes the outside");

        VectorShape ring = rect.copy();
        ring.subtract(VectorShape.ofRect(25, 25, 75, 75));
        check(Math.abs(ring.measure() - 7_500.0) < 2.0, "rect minus hole area " + ring.measure());
        check(!ring.contains(50, 50), "the hole is really a hole");
        check(ring.contains(10, 10), "the ring is still there");
        check(Math.abs(rect.measure() - 10_000.0) < 1.0, "copy() does not mutate the original");

        VectorShape disc = VectorShape.ofEllipse(0, 0, 40, 40);
        check(Math.abs(disc.measure() - Math.PI * 400.0) < 30.0, "disc area " + disc.measure());

        VectorShape thick = VectorShape.ofThickLine(0, 0, 100, 0, 4.0);
        check(!thick.isEmpty(), "thick line has area");
        check(thick.contains(50, 0), "thick line covers its middle");
        check(!thick.contains(50, 10), "thick line is not fat in z");

        VectorShape.Bounds bounds = VectorShape.ofRect(-5, -7, 5, 7).bounds();
        check(bounds != null && bounds.minX() == -5.0 && bounds.maxZ() == 7.0,
                "bounds " + (bounds == null ? "null"
                        : bounds.minX() + "," + bounds.minZ() + ".." + bounds.maxX() + "," + bounds.maxZ()));

        check(VectorShape.empty().bounds() == null, "an empty shape has no bounds");
    }

    private static void testCurve() {
        List<double[]> points = new ArrayList<>();
        points.add(new double[] { 0, 0 });
        points.add(new double[] { 50, 40 });
        points.add(new double[] { 100, 0 });
        VectorShape curve = VectorShape.ofCurve(points, false, 3.0);
        check(!curve.isEmpty(), "a three point curve has area");
        VectorShape.Bounds bounds = curve.bounds();
        check(bounds != null && bounds.minX() <= 1.5 && bounds.maxX() >= 98.5,
                "the curve spans its control points");
        check(bounds != null && bounds.maxZ() >= 40.0 && bounds.maxZ() < 45.0,
                "the curve bends up to the middle control point, got " + (bounds == null ? "null" : bounds.maxZ()));

        List<double[]> closed = new ArrayList<>();
        closed.add(new double[] { 0, 0 });
        closed.add(new double[] { 40, 0 });
        closed.add(new double[] { 40, 40 });
        closed.add(new double[] { 0, 40 });
        VectorShape loop = VectorShape.ofCurve(closed, true, 0.0);
        check(loop.contains(20, 20), "a closed curve encloses its middle");
        check(!loop.contains(200, 200), "a closed curve does not enclose the whole world");

        // 手抖的时候采样点是一串锯齿；笔迹不能被自己的抖动打成筛子。
        List<double[]> jitter = new ArrayList<>();
        for (int i = 0; i <= 60; i++) {
            jitter.add(new double[] { i * 2.0, (i % 2 == 0) ? 0.0 : 0.35 });
        }
        VectorShape strokeShape = VectorShape.ofCurve(jitter, false, 4.0);
        double expected = 120.0 * 4.0;
        check(strokeShape.measure() > expected * 0.85,
                "a jittery stroke keeps its body, got " + strokeShape.measure() + " vs " + expected);
        check(strokeShape.contains(60.0, 0.0), "the middle of a jittery stroke is still solid");
    }

    private static void testVectorCodec() {
        List<double[]> parts = new ArrayList<>();
        parts.add(new double[] { 0, 0, 10, 0, 10, 10, 0, 10 });
        VectorShape source = VectorShape.ofPoints(parts);
        check(Math.abs(source.measure() - 100.0) < 0.5, "square from points area " + source.measure());

        VectorShape decoded = VectorCodec.decode(VectorCodec.encode(source));
        check(!decoded.isEmpty(), "codec round trip is not empty");
        check(Math.abs(decoded.measure() - 100.0) < 0.5, "codec area " + decoded.measure());
        check(decoded.contains(5, 5), "codec keeps the interior");
        check(VectorCodec.decode(VectorCodec.encode(VectorShape.empty())).isEmpty(), "codec empty round trip");

        VectorShape ragged = VectorShape.ofRect(-1234.5, 987.25, 4321.75, 5678.5);
        VectorShape back = VectorCodec.decode(VectorCodec.encode(ragged));
        check(Math.abs(back.measure() - ragged.measure()) < 1.0,
                "codec keeps sub-block precision, " + back.measure() + " vs " + ragged.measure());
    }

    private static void testHierarchy() {
        ProvinceStore store = new ProvinceStore();

        Province parent = store.create(Province.NO_PARENT, VectorShape.ofRect(0, 0, 100, 100),
                "重庆", "Chongqing", "municipality", 0x4FA3FF);
        check(parent != null, "create parent returns a province");
        check(parent.tier() == Province.MIN_TIER, "parent tier " + parent.tier());
        check(store.size() == 1, "store size after parent " + store.size());

        VectorShape clipped = store.clipFor(parent.id(), 2, -1, VectorShape.ofRect(50, 50, 150, 150));
        Province child = store.create(parent.id(), clipped, "渝中", "Yuzhong", "district", 0x59D98C);
        check(child != null, "create child returns a province");
        check(child.tier() == 2, "child tier " + child.tier());
        check(Math.abs(child.shape().measure() - 2_500.0) < 4.0,
                "child is clipped to the parent, area " + child.shape().measure());
        check(!child.shape().contains(120, 120), "the clipped child does not stick out");

        check(store.findAt(1, 60, 60) == parent, "findAt tier 1 returns parent");
        check(store.findAt(2, 60, 60) == child, "findAt tier 2 returns child");
        check(store.findAt(2, 120, 120) == null, "findAt outside the parent is null");
        check(store.findFinestAt(60, 60) == child, "findFinestAt picks the child");
        check(store.findFinestAt(-50, -50) == null, "findFinestAt outside every province is null");
        check(store.isOccupied(60, 60), "60,60 is occupied");
        check(!store.isOccupied(120, 120), "120,120 is not occupied by a tier-2 shape");

        check(store.isOwnedByOther(2, -1, 60, 60), "60,60 is owned by a tier-2 province");
        check(!store.isOwnedByOther(2, child.id(), 60, 60), "excluding self reports no other owner");
        check(!store.isOwnedByOther(2, -1, 20, 20), "20,20 has no tier-2 owner");

        double[] delta = store.applyDelta(parent, VectorShape.ofRect(200, 200, 220, 220), VectorShape.empty());
        check(delta != null && delta.length == 2, "applyDelta returns a two element array");
        check(delta != null && delta[0] > 0.0, "applyDelta reports the added area " + (delta == null ? "?" : delta[0]));
        check(store.totalArea() > 0.0, "total area is positive, got " + store.totalArea());
    }

    /**
     * 用户随手画几条线围出一个三角形：油漆桶该只认出那个三角形，
     * 画到很远处的那些长线本身不算区划。
     */
    private static void testFenceFaces() {
        List<double[]> lines = new ArrayList<>();
        lines.add(new double[] { -200.0, 0.0, 200.0, 0.0 });
        lines.add(new double[] { 0.0, -200.0, 0.0, 200.0 });
        lines.add(new double[] { 0.0, 1.0, 1.0, 0.0 });

        VectorShape triangle = FenceFaces.faceAt(lines, 0.2, 0.2);
        check(triangle != null, "三条线围出来的三角形被认出来了");
        if (triangle != null) {
            check(Math.abs(triangle.measure() - 0.5) < 0.02, "三角形面积约 0.5，实测 " + triangle.measure());
            check(triangle.contains(0.2, 0.2), "三角形包含点击点");
            check(!triangle.contains(100.0, 100.0), "三角形外面那一大片没被算进来");
        }
        check(FenceFaces.faceAt(lines, 100.0, 100.0) == null, "点在没被围住的地方就填不出来");

        List<double[]> square = new ArrayList<>();
        square.add(new double[] { 0.0, 0.0, 10.0, 0.0 });
        square.add(new double[] { 10.0, 0.0, 10.0, 10.0 });
        square.add(new double[] { 10.0, 10.0, 0.0, 10.0 });
        square.add(new double[] { 0.0, 10.0, 0.0, 0.0 });
        VectorShape box = FenceFaces.faceAt(square, 5.0, 5.0);
        check(box != null && Math.abs(box.measure() - 100.0) < 0.5, "首尾相接的四条线围出 100 平方格");
    }

    /**
     * 父级被擦掉一块，下级必须跟着缩回来。
     *
     * <p>下级记的是自己那份轮廓，父级变小不会自动带着它们缩 —— 不裁的话它们就会探出父级
     * 边界，看上去像父级里长出了一块飞到外面的地。</p>
     */
    private static void testChildClip() {
        ProvinceStore store = new ProvinceStore();
        Province parent = store.create(Province.NO_PARENT, VectorShape.ofRect(0, 0, 100, 100),
                "父", "Parent", "province", 0x4FA3FF);
        Province child = store.create(parent.id(), VectorShape.ofRect(0, 0, 100, 100),
                "子", "Child", "province", 0xFFAA00);
        check(Math.abs(child.shape().measure() - 10_000.0) < 1.0,
                "子区划初始铺满父级，实测 " + Math.round(child.shape().measure()));

        // 把父级右半边擦掉。
        store.applyDelta(parent, null, VectorShape.ofRect(50, 0, 100, 100));
        check(Math.abs(parent.shape().measure() - 5_000.0) < 1.0,
                "父级只剩左半边，实测 " + Math.round(parent.shape().measure()));
        check(Math.abs(child.shape().measure() - 5_000.0) < 1.0,
                "子区划跟着缩回父级里，实测 " + Math.round(child.shape().measure()));
        check(!child.shape().contains(80.0, 50.0), "子区划不再盖住被擦掉的那半边");
        check(child.shape().contains(20.0, 50.0), "子区划留着的那半边还在");
    }

    /**
     * 填充不能漏行。
     *
     * <p>扫描线隔行采样本身没问题：一整带只采一次中点，然后一次涂满整带。<b>但「这一带还没轮到的边」
     * 必须留到下一带接着用。</b>曾经在那里把边当场扔掉了——每跨一个带就少几条边，交点少一对，
     * 填色里就漏出一条横穿整块的颜色缝。缩放越小、rowStep 越大，漏得越狠，小地图正是那个状态。</p>
     */
    private static void testFillCoverage() {
        VectorShape shape = VectorShape.ofRect(0, 0, 64, 64);
        // 每方块 0.4 像素：pixelsPerBlock 小于 0.5，扫描线会隔到 2 行一带，正好压住丢边那条路。
        ProvinceRenderer.Projection projection = new ProvinceRenderer.Projection() {
            @Override
            public double screenX(double worldX, double worldZ) {
                return worldX * 0.4;
            }

            @Override
            public double screenY(double worldX, double worldZ) {
                return worldZ * 0.4;
            }

            @Override
            public double worldX(double screenX, double screenY) {
                return screenX / 0.4;
            }

            @Override
            public double worldZ(double screenX, double screenY) {
                return screenY / 0.4;
            }
        };

        boolean[] rowPainted = new boolean[64];
        int[] calls = new int[1];
        ProvinceRenderer.Sink sink = (x0, y0, x1, y1, argb) -> {
            calls[0]++;
            int top = Math.max(0, y0);
            int bottom = Math.min(rowPainted.length - 1, y1);
            for (int y = top; y <= bottom; y++) {
                rowPainted[y] = true;
            }
        };

        ProvinceRenderer.fillShape(sink, projection, shape, 0xFFFFFFFF, -50.0, -50.0, 50.0, 50.0);

        int gaps = 0;
        // 矩形在屏幕上占 y 从 0 到 25.6，掐头去尾查中间的实心部分。
        for (int y = 4; y <= 20; y++) {
            if (!rowPainted[y]) {
                gaps++;
            }
        }
        check(gaps == 0, "填充不漏横条，漏了 " + gaps + " 行");
        check(calls[0] >= 8, "填充真的画了东西，提交了 " + calls[0] + " 条命令");

        // 放大到区划盖住整个屏幕：右边界落在裁剪框外。要是投影时把它连左右一起裁掉，
        // 框里就只剩一条边、配不出交点，整块一条都不填。
        boolean[] wideRows = new boolean[64];
        ProvinceRenderer.Projection unit = new ProvinceRenderer.Projection() {
            @Override
            public double screenX(double worldX, double worldZ) {
                return worldX;
            }

            @Override
            public double screenY(double worldX, double worldZ) {
                return worldZ;
            }

            @Override
            public double worldX(double screenX, double screenY) {
                return screenX;
            }

            @Override
            public double worldZ(double screenX, double screenY) {
                return screenY;
            }
        };
        ProvinceRenderer.Sink wideSink = (x0, y0, x1, y1, argb) -> {
            int top = Math.max(0, y0);
            int bottom = Math.min(wideRows.length - 1, y1);
            for (int y = top; y <= bottom; y++) {
                wideRows[y] = true;
            }
        };
        ProvinceRenderer.fillShape(wideSink, unit, VectorShape.ofRect(0, 0, 400, 400), 0xFFFFFFFF,
                0.0, 0.0, 40.0, 40.0);
        int wideGaps = 0;
        for (int y = 1; y <= 38; y++) {
            if (!wideRows[y]) {
                wideGaps++;
            }
        }
        check(wideGaps == 0, "区划盖满整个屏幕时也不漏横条，漏了 " + wideGaps + " 行");

        // 「智能降低渲染精度」开关：关掉就不许再有横跨多行的填充条。
        int[] tallBands = new int[1];
        ProvinceRenderer.Projection tiny = new ProvinceRenderer.Projection() {
            @Override
            public double screenX(double worldX, double worldZ) {
                return worldX * 0.05;
            }

            @Override
            public double screenY(double worldX, double worldZ) {
                return worldZ * 0.05;
            }

            @Override
            public double worldX(double screenX, double screenY) {
                return screenX / 0.05;
            }

            @Override
            public double worldZ(double screenX, double screenY) {
                return screenY / 0.05;
            }
        };
        ProvinceRenderer.Sink tallSink = (x0, y0, x1, y1, argb) -> {
            if (y1 - y0 > 1) {
                tallBands[0]++;
            }
        };
        VectorShape wide = VectorShape.ofRect(0, 0, 400, 400);
        ProvinceRenderer.fillShape(tallSink, tiny, wide, 0xFFFFFFFF, -50.0, -50.0, 50.0, 50.0, false);
        check(tallBands[0] == 0, "关掉智能降精度后不再隔行，却有 " + tallBands[0] + " 条跨多行的填充");
        ProvinceRenderer.fillShape(tallSink, tiny, wide, 0xFFFFFFFF, -50.0, -50.0, 50.0, 50.0, true);
        check(tallBands[0] > 0, "开着智能降精度时应当隔行采样");
    }

    private static void testStrokeAdaptive() {
        // 0.1 像素/格：一条 2000 格长的边在屏幕上 200 像素，采样间距应当是 1。
        // 500 个 2 格宽的小锯齿在这个缩放下每条只有 0.2 像素，正好掉进智能档的并边阈值里。
        ProvinceRenderer.Projection projection = new ProvinceRenderer.Projection() {
            @Override
            public double screenX(double worldX, double worldZ) {
                return worldX * 0.1;
            }

            @Override
            public double screenY(double worldX, double worldZ) {
                return worldZ * 0.1;
            }

            @Override
            public double worldX(double screenX, double screenY) {
                return screenX / 0.1;
            }

            @Override
            public double worldZ(double screenX, double screenY) {
                return screenY / 0.1;
            }
        };

        // 200 个 3x3 格的小方块，在这个缩放下每块只有 0.3 像素见方——四条边全都短过
        // 智能档 0.35 像素的并边阈值，会被并成一条；精确档一条都不并，四倍落墨。
        VectorShape dotted = VectorShape.empty();
        for (int i = 0; i < 200; i++) {
            double x = i * 12.0;
            dotted.add(VectorShape.ofRect(x, 0, x + 3.0, 3.0));
        }

        int[] exact = countStrokePixels(projection, dotted, false);
        int[] smart = countStrokePixels(projection, dotted, true);
        check(exact[0] > smart[0] * 3 / 2,
                "关掉智能降低边框精度后一条边都不并，落墨 " + exact[0] + " vs 智能档 " + smart[0]);
        // 智能档把 0.3 像素的小方块整个并掉是应当的——那点尺寸本来也看不见。
        check(smart[0] <= exact[0], "智能档的落墨不该比精确档还多");
        check(exact[1] == 0, "精确档不该出现断口，断了 " + exact[1] + " 处");

        // 正常大小的形状两档都要画得出来，智能档只是省掉看不见的笔。
        VectorShape block = VectorShape.ofRect(0, 0, 600, 600);
        check(countStrokePixels(projection, block, true)[0] > 0, "智能档在正常形状上要画得出线");
        check(countStrokePixels(projection, block, false)[0] > 0, "精确档在正常形状上要画得出线");
    }

    /** 描一圈，返回 {@code {落墨像素格数, 断口数}}；断口按「同一条边上前一落点隔了 3 像素以上」算。 */
    private static int[] countStrokePixels(ProvinceRenderer.Projection projection, VectorShape shape,
            boolean adaptive) {
        boolean[][] painted = new boolean[300][300];
        ProvinceRenderer.Sink sink = (x0, y0, x1, y1, argb) -> {
            for (int y = Math.max(0, y0); y <= Math.min(painted.length - 1, y1); y++) {
                for (int x = Math.max(0, x0); x <= Math.min(painted.length - 1, x1); x++) {
                    painted[y][x] = true;
                }
            }
        };
        ProvinceRenderer.strokeStyled(sink, projection, shape, 0xFF204060, 1, BorderStyle.LINE,
                -10.0, -10.0, 290.0, 290.0, adaptive);

        int cells = 0;
        for (int y = 0; y < painted.length; y++) {
            for (int x = 0; x < painted.length; x++) {
                if (painted[y][x]) {
                    cells++;
                }
            }
        }
        // 每条边扫一遍，看相邻落点之间的空档有没有大到能让线断开。
        int breaks = 0;
        for (int y = 1; y <= 199; y++) {
            int lastX = -100;
            for (int x = 1; x <= 199; x++) {
                boolean edge = painted[y][x] && (!painted[y - 1][x] || !painted[y + 1][x]);
                if (edge) {
                    if (lastX >= 0 && x - lastX > 3) {
                        breaks++;
                    }
                    lastX = x;
                }
            }
        }
        return new int[] {cells, breaks};
    }

    private static void testBorderStyle() {
        check(BorderStyle.byKey("off") == BorderStyle.OFF, "border style byKey off");
        check(BorderStyle.byKey("line") == BorderStyle.LINE, "border style byKey line");
        check(BorderStyle.byKey("dash") == BorderStyle.DASH, "border style byKey dash");
        check(BorderStyle.byKey("dash_dot") == BorderStyle.DASH_DOT, "border style byKey dash_dot");
        check(BorderStyle.byKey("nonsense") == BorderStyle.OFF, "unknown border style falls back to off");
        BorderStyle cycled = BorderStyle.OFF;
        for (int i = 0; i < BorderStyle.values().length; i++) {
            cycled = cycled.shift(1);
        }
        check(cycled == BorderStyle.OFF, "shifting a full cycle comes back around, got " + cycled);
        check(BorderStyle.DASH.shift(-1) == BorderStyle.LINE, "shifting backwards works");
    }

    /** 玩家自己加的级别名：加得上、删得掉，内置的那些动不了；空后缀那项只显示原名。 */
    private static void testUserLevels() {
        int before = AdminLevels.all().size();

        check(!AdminLevels.isUser(AdminLevels.DEFAULT_KEY), "a built-in level is not user-made");
        check(!AdminLevels.isUser(null), "a null key is not user-made");
        check(!AdminLevels.removeUser(AdminLevels.DEFAULT_KEY), "a built-in level cannot be removed");
        check(AdminLevels.addUser("   ", "anything") == null, "a blank chinese name is refused");
        check(AdminLevels.addUser(null, null) == null, "a null chinese name is refused");

        AdminLevels.Name added = AdminLevels.addUser("测试级别", "");
        check(added != null, "a level name can be added");
        check(AdminLevels.all().size() == before + 1, "adding one grows the list by one");
        check(AdminLevels.isUser(added.key()), "the new key counts as user-made");
        check(added.key().startsWith(AdminLevels.USER_PREFIX), "user keys carry the user prefix");
        check("测试级别".equals(AdminLevels.zh(added.key())), "the chinese name comes back");
        check("测试级别".equals(AdminLevels.en(added.key())), "an empty english name follows the chinese one");
        AdminLevels.Name last = AdminLevels.all().get(AdminLevels.all().size() - 1);
        check(last.key().equals(added.key()), "user levels sit after the built-ins");

        check(AdminLevels.removeUser(added.key()), "a user-made level can be removed");
        check(!AdminLevels.isUser(added.key()), "a removed key is no longer user-made");
        check(AdminLevels.all().size() == before, "removing one brings the count back");
        check(AdminLevels.byKey(added.key()).key().equals(AdminLevels.DEFAULT_KEY),
                "a removed key falls back to the default level");

        // 空后缀那项：中文名就是空串，界面上靠 labelOf 给个占位。
        check(AdminLevels.zh(AdminLevels.NONE_KEY).isEmpty(), "the none level has an empty suffix");
        check(AdminLevels.labelOf(AdminLevels.byKey(AdminLevels.NONE_KEY)).equals(AdminLevels.NONE_LABEL),
                "the none level shows a placeholder label");
        AdminLevels.Name tail = AdminLevels.all().get(AdminLevels.all().size() - 1);
        check(AdminLevels.NONE_KEY.equals(tail.key()), "the none level sits at the end of the list");

        // 选了「无后缀」就该只剩区划名本身，中英两种格式都不许多出空格。
        ProvinceStore store = new ProvinceStore();
        Province plain = store.create(Province.NO_PARENT, VectorShape.empty(),
                "重庆", "Chongqing", AdminLevels.NONE_KEY, 0x4FA3FF);
        check("重庆".equals(NameFormat.ZH_FULL.format(plain)), "the none level adds no suffix");
        check("Chongqing".equals(NameFormat.EN_FULL.format(plain)), "the none level adds no spaced suffix");

        // 同名不同体系的内置项：级别格子里带注释，地图上的地名干干净净不带。
        check(AdminLevels.labelOf(AdminLevels.byKey("ken")).equals("县（日）"),
                "a japanese prefecture is labelled with its country");
        check(AdminLevels.labelOf(AdminLevels.byKey("county")).equals("县（中）"),
                "a chinese county is labelled with its country");
        check(AdminLevels.en("ken").equals("Ken") && AdminLevels.zh("prefecture").equals("地区"),
                "the japanese prefecture is transliterated, the chinese one is not");
        Province shizuoka = store.create(Province.NO_PARENT, VectorShape.empty(),
                "静冈", "Shizuoka", "ken", 0x4FA3FF);
        check("静冈县".equals(NameFormat.ZH_FULL.format(shizuoka)), "the map label carries no country note");
        check("Shizuoka Ken".equals(NameFormat.EN_FULL.format(shizuoka)),
                "the english map label carries no country note, and the japanese suffix is the transliteration");
    }

    private static void testComponents() {
        // 飞地：两块分离的岛应该拆成两个分量，好各给一个名牌。
        VectorShape split = VectorShape.ofRect(0, 0, 10, 10);
        split.add(VectorShape.ofRect(30, 0, 40, 10));
        List<VectorShape> parts = split.components();
        check(parts.size() == 2, "two separate islands split into 2 components, got " + parts.size());

        // 洞也会被切出来，但它的中心不属于整体，渲染层靠 contains 把它滤掉。
        VectorShape ring = VectorShape.ofRect(0, 0, 40, 40);
        ring.subtract(VectorShape.ofRect(15, 15, 25, 25));
        check(!ring.contains(20.0, 20.0), "the hole is not part of the shape");
        check(ring.contains(2.0, 2.0), "the ring body is still part of the shape");
        boolean holeSplitOut = false;
        for (VectorShape part : ring.components()) {
            VectorShape.Bounds bounds = part.bounds();
            if (bounds == null) {
                continue;
            }
            if (part.contains(20.0, 20.0) && !ring.contains(20.0, 20.0)) {
                holeSplitOut = true;
            }
        }
        check(holeSplitOut, "a hole is split out as its own component and can be filtered by contains");
        check(!ring.components().isEmpty(), "components of a ring are not empty");
    }

    private static void testBucket() {
        // 选区笔：一张干净的地图，空宿主，画出来的就是那条带子本身。
        ProvinceStore plain = new ProvinceStore();
        Province lone = plain.create(Province.NO_PARENT, VectorShape.empty(),
                "空白", "Blank", "country", 0x4FA3FF);
        EditSession session = new EditSession(plain);
        session.beginEdit(lone.id());
        session.setTool(EditTool.SELECT_BRUSH);
        session.setBrushWidth(8.0);
        session.onPress(40, 40);
        session.onDrag(60, 40);
        session.onRelease();
        check(session.working().measure() > 0.0, "the select brush lays down an area");
        check(session.working().contains(50, 40), "the brush covered the point it was dragged through");
        check(!session.working().contains(300, 300), "the brush only covers where it was dragged");
        check(!session.working().contains(40, 200), "and it does not smear across the map");

        // 三条线围出一个三角形，魔棒点内部就该选中它。
        ProvinceStore fenced = new ProvinceStore();
        Province host = fenced.create(Province.NO_PARENT, VectorShape.ofRect(0, 0, 400, 400),
                "测试国", "Testland", "country", 0x59D98C);
        EditSession magic = new EditSession(fenced);
        magic.beginEdit(host.id());
        magic.setTool(EditTool.SELECT_LINE);
        magic.setBrushWidth(0.05);
        stroke(magic, 100, 100, 200, 100);
        stroke(magic, 200, 100, 100, 200);
        stroke(magic, 100, 200, 100, 100);
        check(magic.guideCount() >= 3, "each line left a guide behind for the magic wand");

        // 从空选区开始，魔棒应该只选中三条线围出来的那一块，不能顺手套走整个父级。
        magic.clearSelection();
        magic.setTool(EditTool.MAGIC);
        double filled = magic.applyMagic(133, 133);
        check(filled > 0.0, "magic selected " + filled + " square blocks");
        check(Math.abs(filled - 5000.0) < 120.0, "the three lines enclose a 100x100/2 triangle, got " + filled);
        check(!magic.working().contains(350, 350), "magic did not swallow the whole parent");

        // 魔棒绝不能把自己之前画的东西清掉——那正是「油漆桶一用就消失」那个 bug。
        check(magic.guideCount() >= 3, "the magic wand did not wipe its guides");

        // 什么都没画过、也没有父级可依时必须直接拒绝，不能把整张地图当成一次选区。
        ProvinceStore emptyStore = new ProvinceStore();
        Province empty = emptyStore.create(Province.NO_PARENT, VectorShape.empty(),
                "空白", "Blank", "country", 0x4FA3FF);
        EditSession bare = new EditSession(emptyStore);
        bare.beginEdit(empty.id());
        bare.setTool(EditTool.MAGIC);
        check(bare.applyMagic(0, 0) == -1.0, "magic refuses when there is nothing to select");
        check(bare.working().isEmpty(), "the refused magic left nothing behind");

        // 同样的道理：一点线索都没有时，魔棒不能拿父级整块来顶。
        ProvinceStore childStore = new ProvinceStore();
        Province parent = childStore.create(Province.NO_PARENT, VectorShape.ofRect(0, 0, 100, 100),
                "母区", "Parent", "country", 0x4FA3FF);
        Province kid = childStore.create(parent.id(), VectorShape.empty(), "子区", "Kid", "province", 0xF2C94C);
        EditSession kidSession = new EditSession(childStore);
        kidSession.beginEdit(kid.id());
        kidSession.setTool(EditTool.MAGIC);
        check(kidSession.applyMagic(50, 50) > 0.0, "with only a parent to lean on, magic takes the parent slice");
        check(!kidSession.working().contains(200, 200), "and it stays inside the parent");

        // 手画的圆环首尾差一点点，靠补出来的闭合边魔棒才认得出里面那块。
        ProvinceStore ringStore = new ProvinceStore();
        Province ringHost = ringStore.create(Province.NO_PARENT, VectorShape.empty(), "环", "Ring", "country", 0x4FA3FF);
        EditSession ringSession = new EditSession(ringStore);
        ringSession.beginEdit(ringHost.id());
        // 笔细一点：四笔围出来的中间才是真的空着，粗笔会把 y=1 那条带也盖进选区，
        // 那样点进去就是「已经在选区里」，测不出补边到底有没有生效。
        ringSession.setBrushWidth(0.5);
        stroke(ringSession, 0, 0, 100, 0);
        stroke(ringSession, 100, 0, 100, 2);
        stroke(ringSession, 100, 2, 0, 2);
        stroke(ringSession, 0, 2, 0, 0.4);
        ringSession.setTool(EditTool.MAGIC);
        check(ringSession.applyMagic(50, 1) > 0.0, "magic finds the inside of a nearly closed ring");
        check(!ringSession.working().contains(300, 300), "and it only takes that inside");

        // 手画一笔会攒出成千上万个点，不抽稀的话平面图的两两求交就是 O(n²) 的灾难。
        ProvinceStore thinStore = new ProvinceStore();
        Province denseHost = thinStore.create(Province.NO_PARENT, VectorShape.empty(),
                "宿主", "", "province", 0x4FA3FF);
        EditSession dense = new EditSession(thinStore);
        dense.beginEdit(denseHost.id());
        dense.setTool(EditTool.SELECT_BRUSH);
        dense.setBrushWidth(2.0);
        dense.onPress(200.0, 140.0);
        for (int i = 1; i <= 400; i++) {
            double angle = i * 0.0157;
            dense.onDrag(200.0 + Math.sin(angle) * 60.0, 200.0 - Math.cos(angle) * 60.0);
        }
        dense.onRelease();
        check(dense.guideCount() <= 400, "a dense freehand stroke gets thinned, got " + dense.guideCount());
        check(dense.guideCount() >= 3, "and it still leaves a usable outline, got " + dense.guideCount());
        check(dense.applyMagic(200.0, 200.0) > 0.0, "the magic wand can fill the thinned circle");

        // 第 1 级已经有一块时，第二块照样要能画。曾经把「同级占用」并进一个空的允许空间里，
        // 结果第二块第 1 级的蒙版是空集，怎么画都被裁光。
        ProvinceStore siblingStore = new ProvinceStore();
        Province landA = siblingStore.create(Province.NO_PARENT, VectorShape.ofRect(0, 0, 100, 100),
                "甲", "A", "province", 0x4FA3FF);
        Province landB = siblingStore.create(Province.NO_PARENT, VectorShape.empty(),
                "乙", "B", "province", 0xF2C94C);
        check(siblingStore.parentBound(Province.NO_PARENT) == null, "the first tier has no outer bound");
        check(siblingStore.siblingUnion(Province.MIN_TIER, landB.id()) != null, "the sibling area is found");
        EditSession siblingSession = new EditSession(siblingStore);
        siblingSession.beginEdit(landB.id());
        siblingSession.setTool(EditTool.SELECT_RECT);
        siblingSession.setBrushWidth(4.0);
        stroke(siblingSession, 300, 300, 400, 400);
        check(siblingSession.currentShape().measure() > 0.0, "the second first-tier province can be drawn");
        VectorShape overlap = siblingSession.currentShape();
        overlap.intersect(landA.shape());
        check(overlap.isEmpty(), "and it never spills onto the sibling");
    }

    /** 按住 Shift 拖出来的应当吸成正形：直线走横竖，矩形与圆形补成等边。 */
    private static void testConstrain() {
        ProvinceStore store = new ProvinceStore();
        Province lineHost = store.create(Province.NO_PARENT, VectorShape.empty(),
                "约束线", "ConstrainLine", "province", 0x4FA3FF);
        Province rectHost = store.create(Province.NO_PARENT, VectorShape.empty(),
                "约束方", "ConstrainRect", "province", 0x59D98C);
        Province roundHost = store.create(Province.NO_PARENT, VectorShape.empty(),
                "约束圆", "ConstrainRound", "province", 0xF2C94C);
        Province straightHost = store.create(Province.NO_PARENT, VectorShape.empty(),
                "约束平", "ConstrainFlat", "province", 0xEB5757);

        // 横向分量 100 远大于纵向 30 ⇒ 吸成水平，纵向只剩下笔宽那点厚度。
        EditSession line = new EditSession(store);
        line.beginEdit(lineHost.id());
        line.setTool(EditTool.SELECT_LINE);
        line.setBrushWidth(2.0);
        line.setConstrain(false);
        stroke(line, 0, 0, 100, 30);
        VectorShape.Bounds free = line.working().bounds();
        check(free != null && free.maxZ() - free.minZ() > 20.0, "without shift the line keeps its slope");

        EditSession straight = new EditSession(store);
        straight.beginEdit(straightHost.id());
        straight.setTool(EditTool.SELECT_LINE);
        straight.setBrushWidth(2.0);
        straight.setConstrain(true);
        stroke(straight, 0, 0, 100, 30);
        VectorShape.Bounds flat = straight.working().bounds();
        check(flat != null && flat.maxZ() - flat.minZ() <= 3.0,
                "shift line goes horizontal, thickness " + (flat == null ? -1.0 : flat.maxZ() - flat.minZ()));

        EditSession held = new EditSession(store);
        held.beginEdit(rectHost.id());
        held.setTool(EditTool.SELECT_RECT);
        held.setConstrain(true);
        stroke(held, 0, 0, 100, 30);
        VectorShape.Bounds box = held.working().bounds();
        double boxW = box.maxX() - box.minX();
        double boxH = box.maxZ() - box.minZ();
        check(Math.abs(boxW - boxH) < 1.0E-6, "shift rect is square, " + boxW + " x " + boxH);
        check(Math.abs(boxW - 100.0) < 1.0E-6, "and takes the longer side, " + boxW);

        EditSession circle = new EditSession(store);
        circle.beginEdit(roundHost.id());
        circle.setTool(EditTool.SELECT_ELLIPSE);
        circle.setConstrain(true);
        stroke(circle, 0, 0, 30, 100);
        VectorShape.Bounds oval = circle.working().bounds();
        check(Math.abs((oval.maxX() - oval.minX()) - (oval.maxZ() - oval.minZ())) < 1.0E-6,
                "shift ellipse is a circle");
    }

    private static void stroke(EditSession session, double x0, double z0, double x1, double z1) {
        session.onPress(x0, z0);
        session.onDrag(x1, z1);
        session.onRelease();
    }

    /**
     * 方块吸附：边界一律落在整数格线上，方块归属和边界不能打架，笔再细也得留得下一格。
     */
    private static void testBlockSnap() {
        VectorShape ragged = VectorShape.ofRect(0.3, 0.7, 10.6, 20.4);
        VectorShape grid = BlockSnap.snap(ragged);
        check(isBlockAligned(grid), "吸附之后的顶点全落在整数格点上");
        check(Math.abs(grid.measure() - 209.0) < 1.0E-6,
                "0.3~10.6 乘 0.7~20.4 正好是 11 x 19 个方块，实测 " + grid.measure());
        VectorShape.Bounds box = grid.bounds();
        check(box != null && box.minX() == 0.0 && box.minZ() == 1.0
                && box.maxX() == 11.0 && box.maxZ() == 20.0,
                "吸附后的范围跟着方块走：" + box);
        check(Math.abs(BlockSnap.snap(grid).measure() - grid.measure()) < 1.0E-9, "吸附是幂等的");

        // 方块归属 -> 边界：一个方块被选中，当且仅当它的中心落在形状里。
        VectorShape disc = BlockSnap.snap(VectorShape.ofEllipse(0, 0, 100, 100));
        check(isBlockAligned(disc), "圆形吸附后也在格线上");
        int covered = 0;
        for (int i = 0; i < 100; i++) {
            for (int j = 0; j < 100; j++) {
                if (disc.contains(i + 0.5, j + 0.5)) {
                    covered++;
                }
            }
        }
        check(Math.abs(disc.measure() - covered) < 1.0E-6,
                "圆形面积正好等于被选中的方块数：" + disc.measure() + " 对 " + covered);
        check(Math.abs(disc.measure() - Math.PI * 2_500.0) < 250.0,
                "方块圆形的面积仍然接近圆，实测 " + disc.measure());

        // 细笔迹最细也留得下一格，不会吸着吸着整条消失。四连通走出来的是一条一格宽的
        // 阶梯：从 (0,0) 斜着到 (100,100) 要迈 200 步，落在 201 个格子上。
        VectorShape hair = BlockSnap.brushStroke(
                List.of(new double[] { 0, 0 }, new double[] { 100, 100 }), 0.05);
        check(isBlockAligned(hair), "最细的笔迹也在格线上");
        check(Math.abs(hair.measure() - 201.0) < 1.0E-6,
                "最细的笔迹是一格宽的阶梯，实测 " + hair.measure());

        VectorShape fat = BlockSnap.brushStroke(
                List.of(new double[] { 0, 0 }, new double[] { 100, 0 }), 12.0);
        check(fat.measure() > 1_000.0 && fat.measure() < 1_600.0,
                "笔宽 12 的笔迹粗细合理，实测 " + fat.measure());
        check(!fat.contains(50, 40), "笔迹不会糊到很远的地方");
    }

    /**
     * 0.2.0 的老存档：曲线边界读进来要自动吸附成方块边界，而且只迁移一次。
     */
    private static void testBlockMigration() {
        ProvinceStore legacy = new ProvinceStore();
        Province curvy = legacy.create(Province.NO_PARENT, null, "曲线国", "Curvy", "municipality", 0x4FA3FF);
        // create 自己会吸附，这里再塞回一条没吸附的曲线，好装作 0.2.0 写下来的老数据。
        curvy.shape().copyFrom(VectorShape.ofEllipse(0.5, 0.5, 200.5, 200.5));
        legacy.rebuildIndexes();
        CompoundTag legacyTag = ProvinceNbt.write(legacy);
        legacyTag.putInt("version", 1);

        ProvinceStore migrated = new ProvinceStore();
        check(ProvinceNbt.read(legacyTag, migrated), "老数据被认出来要迁移");
        Province moved = migrated.byId(curvy.id());
        check(moved != null, "迁移之后区划还在");
        if (moved != null) {
            check(isBlockAligned(moved.shape()), "老曲线边界迁移后落在方块格线上");
            check(moved.shape().measure() > 30_000.0,
                    "迁移没有把地弄丢，实测 " + moved.shape().measure());
            check(moved.shape().contains(100.5, 100.5), "迁移后中心还在自己家里");
            check(!moved.shape().contains(300.5, 300.5), "迁移后没有长出界");

            CompoundTag freshTag = ProvinceNbt.write(migrated);
            ProvinceStore again = new ProvinceStore();
            check(!ProvinceNbt.read(freshTag, again), "已经是新版的数据不再重复迁移");
            Province settled = again.byId(curvy.id());
            check(settled != null
                    && Math.abs(settled.shape().measure() - moved.shape().measure()) < 1.0E-9,
                    "再读一遍形状一个字都不差");
        }
    }

    /** 形状的每一个顶点都是整数，就说明它是整块方块拼出来的。 */
    private static boolean isBlockAligned(VectorShape shape) {
        for (double[] segment : shape.segments()) {
            for (int i = 0; i < 4; i++) {
                if (Math.abs(segment[i] - Math.rint(segment[i])) > 1.0E-6) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void testNbt() {
        ProvinceStore source = new ProvinceStore();

        Province a = source.create(Province.NO_PARENT, VectorShape.ofRect(0, 0, 10, 10),
                "重庆", "Chongqing", "municipality", 0x4FA3FF);
        a.setVisible(true);
        Province b = source.create(a.id(), VectorShape.ofRect(2, 2, 5, 5),
                "渝中", "Yuzhong", "district", 0x59D98C);

        CompoundTag tag = ProvinceNbt.write(source);
        ProvinceStore restored = new ProvinceStore();
        ProvinceNbt.read(tag, restored);

        check(restored.size() == source.size(), "nbt store size " + restored.size());
        Province ra = restored.byId(a.id());
        Province rb = restored.byId(b.id());
        check(ra != null, "nbt parent restored");
        check(rb != null, "nbt child restored");
        if (ra != null) {
            check(ra.nameZh().equals("重庆"), "nbt nameZh " + ra.nameZh());
            check(ra.nameEn().equals("Chongqing"), "nbt nameEn " + ra.nameEn());
            check(ra.levelKey().equals("municipality"), "nbt levelKey " + ra.levelKey());
            check(ra.color() == 0x4FA3FF, "nbt colour " + Integer.toHexString(ra.color()));
            check(ra.visible(), "nbt visible flag");
            check(Math.abs(ra.shape().measure() - 100.0) < 0.5, "nbt shape area " + ra.shape().measure());
            check(ra.tier() == 1, "nbt parent tier " + ra.tier());
        }
        if (rb != null) {
            check(rb.parentId() == a.id(), "nbt parent link " + rb.parentId());
            check(rb.tier() == 2, "nbt child tier " + rb.tier());
            check(Math.abs(rb.shape().measure() - 9.0) < 0.5, "nbt child area " + rb.shape().measure());
        }
        check(restored.nextId() > Math.max(a.id(), b.id()), "nbt nextId " + restored.nextId());
        check(restored.findFinestAt(3, 3) == rb, "restored spatial index finds the child");
    }

    /**
     * 走一遍界面上真正会走的入口：先在树状图里新建并选中一块，再去地图上画范围，
     * 提交后撤销要能把整块收回去。
     */
    private static void testEditorUi() {
        ProvinceEditor.setActive(true);
        check(ProvinceEditor.isActive(), "editor turns active");
        check(ProvinceEditor.session() == null, "turning active does not open a session yet");

        // 没选中任何区划时落笔只会得到一句提示，不会凭空建东西。
        int before = ProvinceClient.data().store().size();
        ProvinceEditor.onPress(0, 0);
        ProvinceEditor.onRelease();
        check(ProvinceEditor.session() == null, "a press without a selected province opens nothing");
        check(ProvinceClient.data().store().size() == before, "and creates nothing");

        // 从树界面那条路走：先落一块空的，再画范围。
        Province created = ProvinceEditor.createAndSelect(Province.NO_PARENT, "测试省", "Testland",
                "province", 0x4FA3FF);
        check(created != null, "createAndSelect made a province");
        if (created == null) {
            ProvinceEditor.setActive(false);
            return;
        }
        check(ProvinceEditor.session() != null, "the new province is selected right away");
        check(ProvinceEditor.viewTier() == Province.MIN_TIER, "view tier followed the new province");
        // 新块勾不勾，跟其它块一个规矩（看设置里正在显示第几级），不是一律打勾。
        check(created.visible() == (created.tier() == ProvinceSettings.get().shownTier),
                "a new province follows the shown tier instead of always being visible");

        ProvinceEditor.setTool(EditTool.SELECT_RECT);
        ProvinceEditor.onPress(0, 0);
        ProvinceEditor.onDrag(20, 20);
        ProvinceEditor.onRelease();
        EditSession session = ProvinceEditor.session();
        check(session != null, "the rect stroke went into the session");
        if (session == null) {
            ProvinceEditor.setActive(false);
            return;
        }
        check(session.working().contains(10, 10), "the rect selected its own inside");
        check(session.working().measure() > 300.0,
                "the rect selected a decent area, got " + session.working().measure());

        check(ProvinceEditor.commit(), "commit saved the shape");
        Province stored = ProvinceClient.data().store().byId(created.id());
        check(stored != null && !stored.shape().isEmpty(), "the province now has an area");
        check(ProvinceEditor.canUndo(), "commit pushed an undo entry");

        ProvinceEditor.undo();
        check(ProvinceClient.data().store().byId(created.id()) != null,
                "the first undo only takes the shape back");
        ProvinceEditor.undo();
        check(ProvinceClient.data().store().byId(created.id()) == null, "the second undo takes the whole new province out");

        ProvinceEditor.setActive(false);
        check(!ProvinceEditor.isActive(), "editor turns inactive again");
    }

    /**
     * 导出图上的地名：0.1.1 之前导出只叠了填充和边框，地名整片消失。
     * 这里在一块内存画布上跑一遍同样的绘制，确认名字真的落墨、装不下的小块会被跳过。
     */
    private static void testExportNames() {
        ProvinceStore store = new ProvinceStore();
        store.create(Province.NO_PARENT, VectorShape.ofRect(0, 0, 400, 400),
                "大区", "Bigland", "province", 0x4FA3FF);
        store.create(Province.NO_PARENT, VectorShape.ofRect(600, 600, 608, 608),
                "小角", "Tiny", "province", 0x4FA3FF);

        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, 400, 400);
        int drawn = MapExporter.paintNames(graphics, store.allSorted(), NameFormat.ZH_ONLY,
                0.0, 0.0, 1.0, 1.0, 1.0, 400, 400);
        graphics.dispose();

        check(drawn == 1, "only the block big enough carries a label, painted " + drawn);
        // 「真的落了墨」不在这里验：字形要等资源包加载完，见延后的 testPixelFont。

        // 又细又长的地块：纵向只有 4 格，以前「高度装不下整行字」这一条会把它整个跳过，
        // 于是长条区划永远没有名字。现在只卡横向，纵向窄也照样画——落点压在条带上，
        // 名字两端探出去一点，这才是长条该有的观感。
        ProvinceStore longStore = new ProvinceStore();
        longStore.create(Province.NO_PARENT, VectorShape.ofRect(0, 0, 300, 4),
                "长条", "Strip", "province", 0x4FA3FF);

        BufferedImage strip = new BufferedImage(340, 60, BufferedImage.TYPE_INT_ARGB);
        Graphics2D stripGraphics = strip.createGraphics();
        stripGraphics.setColor(Color.BLACK);
        stripGraphics.fillRect(0, 0, 340, 60);
        int stripDrawn = MapExporter.paintNames(stripGraphics, longStore.allSorted(), NameFormat.ZH_ONLY,
                0.0, 0.0, 1.0, 1.0, 1.0, 340, 60);
        stripGraphics.dispose();
        check(stripDrawn == 1, "a long thin province still carries a label, painted " + stripDrawn);
    }

    /**
     * 延后跑：导出图上的地名用的是游戏自带那套像素字（ASCII 来自 ascii.png，汉字来自 unifont），
     * 全靠 {@link PixelFont} 从客户端资源包读。这里确认真读到了，并且真的在画布上落了墨。
     */
    private static void testPixelFont(Minecraft minecraft) {
        PixelFont.ensureLoaded(minecraft);
        check(PixelFont.ready(), "pixel font came out of the resource pack: " + PixelFont.describe());
        if (!PixelFont.ready()) {
            return;
        }
        check(PixelFont.textWidth("鲸鱼镇") == 27, "chinese labels measure like the game, width "
                + PixelFont.textWidth("鲸鱼镇"));
        check(PixelFont.textWidth("Hello") == 24, "ascii labels measure like the game, width "
                + PixelFont.textWidth("Hello"));

        ProvinceStore store = new ProvinceStore();
        store.create(Province.NO_PARENT, VectorShape.ofRect(0, 0, 400, 400),
                "大区", "Bigland", "province", 0x4FA3FF);
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, 400, 400);
        int drawn = MapExporter.paintNames(graphics, store.allSorted(), NameFormat.ZH_ONLY,
                0.0, 0.0, 1.0, 1.0, 1.0, 400, 400);
        graphics.dispose();
        check(drawn == 1, "the label is painted once, painted " + drawn);
        int white = countWhite(image);
        check(white > 0, "the pixel font really left ink on the map, white pixels " + white);
    }

    /**
     * 小地图上的区划是靠 mixin 挂进 Xaero 渲染循环的，挂没挂上编译期看不出来。
     * Mixin 会把注入方法改个名再塞进目标类，反射查一下就知道挂上没有。
     * 没装小地图时目标类根本不在，那种情况算通过（那份配置本来就是 required=false）。
     */
    private static void testMinimapHook() {
        check(hasInjectedHandler("xaero.hud.minimap.element.render.MinimapElementRendererHandler", "drawProvinces"),
                "the minimap element renderer carries our draw hook");
        check(hasInjectedHandler("xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler",
                "captureTransform"),
                "the minimap element map handler hands over this frame's transform");
        MinimapProjection projection = new MinimapProjection(100.0, 60.0, 12.5, -33.25, 0.5, 0.866, 1.75);
        double worldX = 40.0;
        double worldZ = 15.0;
        double screenX = projection.screenX(worldX, worldZ);
        double screenY = projection.screenY(worldX, worldZ);
        check(Math.abs(projection.worldX(screenX, screenY) - worldX) < 1.0E-6
                        && Math.abs(projection.worldZ(screenX, screenY) - worldZ) < 1.0E-6,
                "the minimap projection round-trips world -> screen -> world");
        // 小地图画在 FBO 里，坐标必须跟 Xaero 的 transformAndRenderForRenderer 一模一样，
        // 差一个正负号就会画到镜像方向上，光看形状大小是发现不了的。
        double ps = 0.5;
        double pc = 0.866;
        double zoom = 1.75;
        MinimapProjection plain = new MinimapProjection(0.0, 0.0, 0.0, 0.0, ps, pc, zoom);
        check(Math.abs(plain.screenX(10.0, 4.0) - (ps * 10.0 * zoom - pc * 4.0 * zoom)) < 1.0E-6
                        && Math.abs(plain.screenY(10.0, 4.0) - (pc * 10.0 * zoom + ps * 4.0 * zoom)) < 1.0E-6,
                "the minimap projection matches Xaero's element transform");
        // 未锁定北方时小地图整体旋转，屏幕裁剪矩形反投影回世界是个转着的方框，
        // 轴对齐包围盒必须取四个角。只取对角那两个角的话，「南方为上」那一档会算成
        // minZ > maxZ，于是每一块区划都被当成不相交剔掉，整张小地图空白。
        for (int degrees = 0; degrees < 360; degrees += 30) {
            double radians = Math.toRadians(degrees);
            MinimapProjection rotated = new MinimapProjection(0.0, 0.0, 0.0, 0.0,
                    Math.sin(radians), Math.cos(radians), 1.0);
            double[] box = ProvinceRenderer.worldBounds(rotated, -57.0, -57.0, 57.0, 57.0);
            double expected = 114.0 * (Math.abs(Math.sin(radians)) + Math.abs(Math.cos(radians)));
            check(box[0] <= box[2] && box[1] <= box[3]
                            && Math.abs((box[2] - box[0]) - expected) < 1.0E-6
                            && Math.abs((box[3] - box[1]) - expected) < 1.0E-6,
                    "the minimap world bounds stay valid at " + degrees + " degrees");
        }
    }

    private static boolean hasInjectedHandler(String className, String marker) {
        try {
            Class<?> type = Class.forName(className, false, ProvinceSelfTest.class.getClassLoader());
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (method.getName().contains(marker)) {
                    return true;
                }
            }
            return false;
        } catch (ClassNotFoundException | LinkageError e) {
            // 没装小地图：目标类不存在，注入自然无从谈起，跳过。
            return true;
        }
    }

    private static int countWhite(BufferedImage image) {
        int white = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == 0xFFFFFF) {
                    white++;
                }
            }
        }
        return white;
    }

    private static void testHistory() {
        EditHistory<String> history = new EditHistory<>();
        check(!history.canUndo() && !history.canRedo(), "history starts empty");

        history.push(new EditHistory.Entry<>("first", "after-1", "before-1"));
        history.push(new EditHistory.Entry<>("second", "after-2", "before-2"));
        check(history.canUndo(), "can undo after push");
        check(history.undoDepth() == 2, "undo depth " + history.undoDepth());

        EditHistory.Entry<String> undone = history.popUndo();
        check(undone != null && "after-2".equals(undone.forward()), "popUndo returns the newest entry");
        check(history.canRedo(), "can redo after undo");

        EditHistory.Entry<String> redone = history.popRedo();
        check(redone != null && "after-2".equals(redone.forward()), "popRedo returns the same entry");
        check(!history.canRedo(), "redo stack empty again");

        history.clear();
        check(!history.canUndo() && !history.canRedo(), "clear empties the history");
    }
}
