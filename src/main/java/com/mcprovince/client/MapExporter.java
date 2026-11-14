package com.mcprovince.client;

import com.mcprovince.client.render.ProvinceRenderer;
import com.mcprovince.core.BorderStyle;
import com.mcprovince.core.NameFormat;
import com.mcprovince.core.Province;
import com.mcprovince.core.ProvinceStore;
import com.mcprovince.core.VectorShape;
import com.mcprovince.data.ProvinceData;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import xaero.map.MapProcessor;
import xaero.map.WorldMap;
import xaero.map.WorldMapSession;
import xaero.map.file.RegionDetection;
import xaero.map.file.export.PNGExportResult;
import xaero.map.file.export.PNGExportResultType;
import xaero.map.file.export.PNGExporter;
import xaero.map.gui.MapTileSelection;
import xaero.map.region.LeveledRegion;
import xaero.map.region.MapLayer;
import xaero.map.region.MapRegion;
import xaero.map.world.MapDimension;
import xaero.map.world.MapWorld;

/**
 * 把当前屏幕上的 Xaero 世界地图导出成 PNG。
 *
 * <p>底图交给 Xaero 自己的 {@link PNGExporter}：它从区块缓存重绘，分辨率不受窗口大小限制，
 * 也就是「高清」的那一份。行政区划是本模组的矢量数据，导完再用 Java2D 叠上去——
 * 导出图覆盖的方块范围由区块选区推得，所以叠上去的边界和图上地形是对得齐的。</p>
 */
public final class MapExporter {

    /** 提示条的三种状态，界面按它挑颜色。 */
    public static final int NOTICE_RUNNING = 1;
    public static final int NOTICE_OK = 2;
    public static final int NOTICE_ERROR = 3;

    /** 导出结束后提示条再挂多久（纳秒）。 */
    private static final long NOTICE_NANOS = 8_000_000_000L;

    /** 从按下按钮到真正开跑之间的宽限期：隔得太久说明地图界面已经关了，这次请求作废。 */
    private static final long REQUEST_TIMEOUT_NANOS = 2_000_000_000L;

    /** 每帧由 {@code GuiMapMixin} 刷新的相机快照；导出用的是按下按钮那一帧的视角。 */
    private static volatile double cameraX;
    private static volatile double cameraZ;
    private static volatile double scale = 1.0;
    private static volatile double screenScale = 1.0;

    /** 导出的三种范围。 */
    public enum ExportMode {
        /** 屏幕上正显示的那一块。 */
        SCREEN("地图"),
        /** 整个世界地图已经加载过的区域。 */
        WORLD("世界全图"),
        /** 玩家自己填的矩形方块范围。 */
        RANGE("指定范围");

        private final String label;

        ExportMode(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    /** 指定范围导出单边最多多少方块：再大 PNGExporter 一张图要吃掉几个 G。 */
    public static final int MAX_RANGE_BLOCKS = 4096;

    // —— 导出请求与提示 ——
    // 导出是同步阻塞的（几秒），所以按下按钮那一帧不能直接开跑：先让「正在导出…」上屏，
    // 下一帧才动真格，否则提示根本没机会显示出来。
    private static boolean requested;
    private static ExportMode requestedMode = ExportMode.SCREEN;
    private static boolean requestDrawn;
    private static long requestNanos;
    private static String notice = "";
    private static int noticeKind = NOTICE_RUNNING;
    private static long noticeUntil;

    /** 指定范围导出用的方块范围；请求时就规范成 min/max，导出时直接用。 */
    private static int rangeMinX;
    private static int rangeMinZ;
    private static int rangeMaxX;
    private static int rangeMaxZ;

    private MapExporter() {
    }

    /** 由地图界面每帧调用，记录当前视角。 */
    public static void updateCamera(double cameraX, double cameraZ, double scale, double screenScale) {
        MapExporter.cameraX = cameraX;
        MapExporter.cameraZ = cameraZ;
        MapExporter.scale = scale;
        MapExporter.screenScale = screenScale;
    }

    // ————————————————— 导出请求与提示条 —————————————————

    /** 界面按下「导出」时调这个；真正的导出放到下一帧去跑。 */
    public static void requestExport(ExportMode mode) {
        requestExport(mode, 0, 0, 0, 0);
    }

    /**
     * 指定范围导出的入口：四个方块坐标（含端点）。
     *
     * <p>写反了不报错：这里先把小的当 min、大的当 max，界面那边漏了也还有这一道。</p>
     */
    public static void requestExport(ExportMode mode, int x1, int z1, int x2, int z2) {
        rangeMinX = Math.min(x1, x2);
        rangeMaxX = Math.max(x1, x2);
        rangeMinZ = Math.min(z1, z2);
        rangeMaxZ = Math.max(z1, z2);
        requested = true;
        requestedMode = mode;
        requestDrawn = false;
        requestNanos = System.nanoTime();
        notice = "正在导出" + mode.label() + "…";
        noticeKind = NOTICE_RUNNING;
        noticeUntil = Long.MAX_VALUE;
    }

    /**
     * 当前屏幕上看得见的那一块的方块范围（含端点），给「指定范围」页当默认值。
     *
     * @return {@code {x1, z1, x2, z2}}；视角异常时 {@code null}
     */
    public static int[] currentScreenBlockRange(Minecraft minecraft) {
        double pixelsPerBlock = scale / (screenScale <= 0.0 ? 1.0 : screenScale);
        if (!(pixelsPerBlock > 0.0) || !Double.isFinite(cameraX) || !Double.isFinite(cameraZ)) {
            return null;
        }
        int guiWidth = minecraft.getWindow().getGuiScaledWidth();
        int guiHeight = minecraft.getWindow().getGuiScaledHeight();
        double halfW = guiWidth / 2.0 / pixelsPerBlock;
        double halfZ = guiHeight / 2.0 / pixelsPerBlock;
        int x1 = (int) Math.floor(cameraX - halfW);
        int z1 = (int) Math.floor(cameraZ - halfZ);
        int x2 = Math.max(x1, (int) Math.ceil(cameraX + halfW) - 1);
        int z2 = Math.max(z1, (int) Math.ceil(cameraZ + halfZ) - 1);
        return new int[] {x1, z1, x2, z2};
    }

    /**
     * 地图界面每帧调一次。
     *
     * <p>第一次只把「正在导出地图…」放行到屏幕上，下一帧才真开始导出——屏幕上那一帧已经提交，
     * 阻塞的几秒里玩家看到的就是这行提示。</p>
     *
     * @return 这一帧刚跑完的导出结果；没跑就是 {@code null}
     */
    public static String tickExport(Minecraft minecraft) {
        if (noticeKind != NOTICE_RUNNING && !notice.isEmpty() && System.nanoTime() > noticeUntil) {
            notice = "";
        }
        if (!requested) {
            return null;
        }
        if (!requestDrawn) {
            requestDrawn = true;
            return null;
        }
        requested = false;
        if (System.nanoTime() - requestNanos > REQUEST_TIMEOUT_NANOS) {
            notice = "";
            return null;
        }
        Result result;
        try {
            result = export(minecraft, requestedMode);
        } catch (RuntimeException | LinkageError e) {
            result = new Result(false, "导出失败：" + e);
        }
        if (result.ok()) {
            openExportFolder(minecraft);
        }
        notice = result.ok() ? "导出成功：" + result.message() : result.message();
        noticeKind = result.ok() ? NOTICE_OK : NOTICE_ERROR;
        noticeUntil = System.nanoTime() + NOTICE_NANOS;
        return result.message();
    }

    /** 现在该显示的提示；空串表示不显示。 */
    public static String notice() {
        return notice;
    }

    public static int noticeKind() {
        return noticeKind;
    }

    /** 一次导出的结果：成功时 {@code message} 是文件名。 */
    public record Result(boolean ok, String message) {
    }

    /**
     * 把当前屏幕上看到的那一块地图导出到存档的 {@code province/exports/} 下。
     *
     * @return 给人看的结果说明，成功时是文件名
     */
    public static Result export(Minecraft minecraft, ExportMode mode) {
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null || !session.isUsable()) {
            return new Result(false, "地图还没准备好，稍后再试");
        }
        MapProcessor processor = session.getMapProcessor();
        if (processor == null) {
            return new Result(false, "地图还没准备好，稍后再试");
        }
        double pixelsPerBlock = scale / (screenScale <= 0.0 ? 1.0 : screenScale);
        if (!(pixelsPerBlock > 0.0)) {
            return new Result(false, "地图缩放异常，无法导出");
        }

        int leftChunk;
        int topChunk;
        int rightChunk;
        int bottomChunk;
        if (mode == ExportMode.WORLD) {
            // 全图：范围就是世界地图上已经加载出地形的那些区块。自己算出来而不是让 Xaero 传 null，
            // 是为了叠区划时知道这张图盖住了哪些方块。
            int[] bounds = loadedChunkBounds(processor);
            // 只按「已点亮」裁不够：玩家画的区划常常超出走过的地方，那样边界的区划会被切掉一截。
            // 两块范围取并集，才是「包住全部内容的最小矩形」。
            int[] provinceBounds = provinceChunkBounds();
            if (bounds == null) {
                bounds = provinceBounds;
            } else if (provinceBounds != null) {
                bounds = new int[] {
                        Math.min(bounds[0], provinceBounds[0]), Math.min(bounds[1], provinceBounds[1]),
                        Math.max(bounds[2], provinceBounds[2]), Math.max(bounds[3], provinceBounds[3])};
            }
            if (bounds == null) {
                return new Result(false, "世界地图上还没加载出任何区域");
            }
            // 四周多带两圈地图：区划常常正好画到「走过的地方」的边缘，不多带一点，描边和
            // 名字会被裁掉半截。多带的这块是地形（未探索处就是地图自己的空白），不是纸边。
            leftChunk = bounds[0] - EXPORT_PADDING_CHUNKS;
            topChunk = bounds[1] - EXPORT_PADDING_CHUNKS;
            rightChunk = bounds[2] + EXPORT_PADDING_CHUNKS;
            bottomChunk = bounds[3] + EXPORT_PADDING_CHUNKS;
        } else if (mode == ExportMode.RANGE) {
            long width = (long) rangeMaxX - rangeMinX + 1;
            long height = (long) rangeMaxZ - rangeMinZ + 1;
            if (width <= 0 || height <= 0) {
                return new Result(false, "指定范围无效：请检查 X / Z 两个方向");
            }
            if (width > MAX_RANGE_BLOCKS || height > MAX_RANGE_BLOCKS) {
                return new Result(false, "范围太大：单边最多 " + MAX_RANGE_BLOCKS + " 格（"
                        + width + " × " + height + "）");
            }
            // 范围是含端点的方块坐标，落到覆盖它的最小区块集上；后面再裁回精确矩形。
            leftChunk = Math.floorDiv(rangeMinX, 16);
            topChunk = Math.floorDiv(rangeMinZ, 16);
            rightChunk = Math.floorDiv(rangeMaxX, 16);
            bottomChunk = Math.floorDiv(rangeMaxZ, 16);
        } else {
            if (!Double.isFinite(cameraX) || !Double.isFinite(cameraZ)) {
                return new Result(false, "地图视角异常，无法导出");
            }
            // 屏幕四角反投影回世界坐标，再落到区块格上——Xaero 的导出选区就是以区块为单位的。
            int guiWidth = minecraft.getWindow().getGuiScaledWidth();
            int guiHeight = minecraft.getWindow().getGuiScaledHeight();
            double halfW = guiWidth / 2.0 / pixelsPerBlock;
            double halfZ = guiHeight / 2.0 / pixelsPerBlock;
            leftChunk = (int) Math.floor((cameraX - halfW) / 16.0);
            topChunk = (int) Math.floor((cameraZ - halfZ) / 16.0);
            rightChunk = (int) Math.floor((cameraX + halfW) / 16.0);
            bottomChunk = (int) Math.floor((cameraZ + halfZ) / 16.0);
            if (rightChunk < leftChunk || bottomChunk < topChunk) {
                return new Result(false, "屏幕范围太小，无法导出");
            }
        }

        Path directory = ProvinceData.exportDir(minecraft);
        Set<Path> existing = listPngs(directory);
        PNGExportResult result;
        try {
            Files.createDirectories(directory);
            MapTileSelection selection = new MapTileSelection(leftChunk, topChunk);
            selection.setEnd(rightChunk, bottomChunk);
            result = new PNGExporter(directory).export(processor, processor.worldBiomeRegistry,
                    processor.getWorldDimensionTypeRegistry(), selection, WorldMap.oldFormatSupport);
        } catch (IOException e) {
            return new Result(false, "建目录失败：" + e.getMessage());
        } catch (IllegalAccessException | RuntimeException | LinkageError e) {
            // IllegalArgumentException 属于 RuntimeException，一并落在这里。
            return new Result(false, "导出失败：" + e);
        }
        if (result.getType() != PNGExportResultType.SUCCESS) {
            return new Result(false, "导出失败：" + result.getType().name());
        }

        Path image = findNewPng(directory, existing);
        if (image == null) {
            return new Result(false, "导出似乎成功了，但没找到生成的图片");
        }
        if (mode == ExportMode.RANGE) {
            // PNGExporter 只能按区块切：先按整区块导，再裁到玩家要的那个矩形，
            // 否则图会大出最多 15 格，和填进去的坐标对不上。
            if (!cropToRange(image, leftChunk, topChunk, rightChunk, bottomChunk)) {
                return new Result(false, "导出图的裁剪失败");
            }
            if (ProvinceSettings.get().exportWithProvinces) {
                overlayProvinces(image, rangeMinX, rangeMinZ, rangeMaxX + 1.0, rangeMaxZ + 1.0, pixelsPerBlock);
            }
        } else if (ProvinceSettings.get().exportWithProvinces) {
            overlayProvinces(image, leftChunk * 16.0, topChunk * 16.0,
                    (rightChunk + 1) * 16.0, (bottomChunk + 1) * 16.0, pixelsPerBlock);
        }
        return new Result(true, image.getFileName().toString());
    }

    /**
     * 世界地图上已经加载出地形的区块范围；一片都没有就返回 {@code null}。
     *
     * <p>只认 level 0 的 region：更高层的分支 region 坐标单位不一样，混进来范围会算飞。
     * 口径和 Xaero 自己「导出全部」时用的那套一致。</p>
     *
     * @return {@code {leftChunk, topChunk, rightChunk, bottomChunk}}
     */
    private static int[] loadedChunkBounds(MapProcessor processor) {
        MapWorld world = processor.getMapWorld();
        if (world == null) {
            return null;
        }
        MapDimension dimension = world.getCurrentDimension();
        if (dimension == null || dimension.getLayeredMapRegions() == null) {
            return null;
        }
        int caveLayer = processor.getCurrentCaveLayer();
        int minRegionX = Integer.MAX_VALUE;
        int minRegionZ = Integer.MAX_VALUE;
        int maxRegionX = Integer.MIN_VALUE;
        int maxRegionZ = Integer.MIN_VALUE;

        for (LeveledRegion<?> region : dimension.getLayeredMapRegions().getUnsyncedSet()) {
            if (region.getLevel() != 0 || region.getCaveLayer() != caveLayer) {
                continue;
            }
            if (!(region instanceof MapRegion mapRegion) || !mapRegion.hasHadTerrain()) {
                continue;
            }
            minRegionX = Math.min(minRegionX, region.getRegionX());
            minRegionZ = Math.min(minRegionZ, region.getRegionZ());
            maxRegionX = Math.max(maxRegionX, region.getRegionX());
            maxRegionZ = Math.max(maxRegionZ, region.getRegionZ());
        }
        for (RegionDetection detection : detectedRegions(dimension, caveLayer)) {
            if (!detection.isHasHadTerrain()) {
                continue;
            }
            minRegionX = Math.min(minRegionX, detection.getRegionX());
            minRegionZ = Math.min(minRegionZ, detection.getRegionZ());
            maxRegionX = Math.max(maxRegionX, detection.getRegionX());
            maxRegionZ = Math.max(maxRegionZ, detection.getRegionZ());
        }
        if (minRegionX > maxRegionX || minRegionZ > maxRegionZ) {
            return null;
        }
        // 一个 region 覆盖 SIDE_LENGTH 个区块：第 r 个 region = 区块 r*n … r*n+n-1。
        int side = MapRegion.SIDE_LENGTH;
        return new int[] {minRegionX * side, minRegionZ * side,
                (maxRegionX + 1) * side - 1, (maxRegionZ + 1) * side - 1};
    }

    /** 所有区划（含飞地）盖到的区块范围；一个区划都没有时返回 null。 */
    private static int[] provinceChunkBounds() {
        ProvinceStore store = ProvinceClient.data().store();
        if (store == null) {
            return null;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (Province province : store.renderOrder()) {
            for (VectorShape part : province.shape().components()) {
                VectorShape.Bounds bounds = part.bounds();
                if (bounds == null) {
                    continue;
                }
                minX = Math.min(minX, bounds.minX());
                minZ = Math.min(minZ, bounds.minZ());
                maxX = Math.max(maxX, bounds.maxX());
                maxZ = Math.max(maxZ, bounds.maxZ());
            }
        }
        if (minX > maxX || minZ > maxZ) {
            return null;
        }
        return new int[] {(int) Math.floor(minX / 16.0), (int) Math.floor(minZ / 16.0),
                (int) Math.floor((maxX - 1.0E-6) / 16.0), (int) Math.floor((maxZ - 1.0E-6) / 16.0)};
    }

    /**
     * 导出图上地名的字号：按「每方块几个像素」定，约 8 个方块高，再用图本身的尺寸兜一次底。
     *
     * <p>为什么按 perBlock 而不是按像素上限：区划名是画在地图上的注记，它该跟地形的尺度走，
     * 而不是跟图片分辨率走。同一片地，导出 4 千像素和 8 千像素的图，字号只要差一倍就够了；
     * 但区块数翻倍时地名也得跟着变大，否则满图小字没法看。</p>
     */
    private static int exportFontSize(double perBlockX, int imageWidth, int imageHeight) {
        // 主尺是图的短边：全图导出时一张 9936x5808 的图铺开上千个方块，每方块只有一个像素，
        // 按方块尺度算字号会小到看不清。地名是画在这张纸上的注记，尺寸该跟着纸走。
        int byImage = (int) Math.round(Math.min(imageWidth, imageHeight) / 45.0);
        // 局部导出时图可能很小、但每方块像素很高，这时按方块保个底（至少 6 个方块高）。
        int byBlock = Math.round((float) (perBlockX * 6.0));
        int size = Math.max(byImage, byBlock);
        return Math.max(12, Math.min(240, size));
    }

    /** 按优先级挑一个能用的中文字体；全都拿不到就退回 SansSerif（会走系统合成字形）。 */
    private static java.awt.Font pickExportFont(int size) {
        String[] preferred = {"SimSun", "宋体", "NSimSun", "Microsoft YaHei", "微软雅黑",
                "Noto Sans CJK SC", "Source Han Sans SC", "WenQuanYi Micro Hei"};
        for (String name : preferred) {
            java.awt.Font font = new java.awt.Font(name, java.awt.Font.PLAIN, size);
            // 求字体名拿回来的是本地化 family（SimSun → 宋体、Microsoft YaHei → 微软雅黑），
            // 直接比字符串在中文 Windows 上永远不相等。用「不是 Dialog 兜底 + 真能画出汉字」认定命中。
            if (!font.getFamily().equals("Dialog") && font.canDisplay('中')) {
                return font;
            }
        }
        return new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, size);
    }

    /** 存档里被标记过「有地形」的 region；正在用世界存档时以存档那份为准。 */
    private static Iterable<RegionDetection> detectedRegions(MapDimension dimension, int caveLayer) {
        if (dimension.isUsingWorldSave()) {
            return dimension.getLinkedWorldSaveDetectedRegions();
        }
        MapLayer layer = dimension.getLayeredMapRegions().getLayer(caveLayer);
        if (layer == null) {
            return List.of();
        }
        return layer.getLinkedCompleteWorldSaveDetectedRegions();
    }

    /** 导出完把生成目录打开，省得玩家自己翻存档文件夹；打不开也不影响导出本身。 */
    private static void openExportFolder(Minecraft minecraft) {
        try {
            Util.getPlatform().openFile(ProvinceData.exportDir(minecraft).toFile());
        } catch (RuntimeException e) {
            // 静默跳过：文件夹打不开不是导出失败。
        }
    }

    /** 导出图的像素大小和选区方块范围成正比，按这个比例把区划画上去。 */
    private static void overlayProvinces(Path image, double blockMinX, double blockMinZ,
            double blockMaxX, double blockMaxZ, double screenPixelsPerBlock) {
        BufferedImage base;
        try {
            base = ImageIO.read(image.toFile());
        } catch (IOException e) {
            return;
        }
        if (base == null || base.getWidth() <= 0 || base.getHeight() <= 0) {
            return;
        }
        double perBlockX = base.getWidth() / (blockMaxX - blockMinX);
        double perBlockZ = base.getHeight() / (blockMaxZ - blockMinZ);
        // 导出图每方块像素 ÷ 屏幕上每方块像素：字号、虚线长度照它放大，画出来才和屏幕上是一个比例。
        double exportScale = screenPixelsPerBlock > 1.0E-9 ? perBlockX / screenPixelsPerBlock : 1.0;

        ProvinceSettings settings = ProvinceSettings.get();
        ProvinceStore store = ProvinceClient.data().store();
        int alpha = settings.fillAlphaByte();
        Graphics2D graphics = base.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        try {
            List<Province> drawn = new ArrayList<>();
            for (Province province : store.renderOrder()) {
                // 导出的就是「地图上看得见的东西」：显示状态已经在载入/改级别时铺好了。
                if (!province.visible() || province.shape().isEmpty()) {
                    continue;
                }
                Path2D.Double path = toPath(province.shape(), blockMinX, blockMinZ, perBlockX, perBlockZ);
                if (path == null) {
                    continue;
                }
                int rgb = province.color() & 0xFFFFFF;
                if (settings.showFilled) {
                    graphics.setColor(new java.awt.Color(rgb | (alpha << 24), true));
                    graphics.fill(path);
                }
                if (settings.borderStyle != BorderStyle.OFF) {
                    graphics.setColor(new java.awt.Color(rgb | 0xFF000000, true));
                    graphics.setStroke(borderStroke(settings, perBlockX, exportScale));
                    graphics.draw(path);
                }
                // 字牌只画在真的落了墨的块上，所以边画边攒，不用再遍历一遍 store。
                drawn.add(province);
            }
            if (settings.showNames) {
                paintNames(graphics, drawn, settings.nameFormat,
                        blockMinX, blockMinZ, perBlockX, perBlockZ,
                        exportScale, base.getWidth(), base.getHeight());
            }
        } finally {
            graphics.dispose();
        }
        try {
            ImageIO.write(base, "png", image.toFile());
        } catch (IOException e) {
            // 叠不上去就把 Xaero 导出的原图留着，不算致命。
        } finally {
            graphics.dispose();
        }
    }

    /**
     * 把整区块导出的图裁到指定方块矩形，覆盖写回原文件。
     *
     * <p>PNGExporter 的选区以区块为单位，而玩家填的是方块坐标：先导最外圈的区块，
     * 再按图上的每方块像素裁掉多出来的边——裁完这张图才真的是 X1..X2 / Z1..Z2。</p>
     *
     * @return 裁剪成功（或本来就不需要裁）返回 true
     */
    private static boolean cropToRange(Path image, int leftChunk, int topChunk, int rightChunk, int bottomChunk) {
        BufferedImage base;
        try {
            base = ImageIO.read(image.toFile());
        } catch (IOException e) {
            return false;
        }
        if (base == null || base.getWidth() <= 0 || base.getHeight() <= 0) {
            return false;
        }
        double perBlockX = base.getWidth() / ((rightChunk + 1 - leftChunk) * 16.0);
        double perBlockZ = base.getHeight() / ((bottomChunk + 1 - topChunk) * 16.0);
        int x0 = (int) Math.round((rangeMinX - leftChunk * 16.0) * perBlockX);
        int z0 = (int) Math.round((rangeMinZ - topChunk * 16.0) * perBlockZ);
        int width = (int) Math.round(((long) rangeMaxX - rangeMinX + 1) * perBlockX);
        int height = (int) Math.round(((long) rangeMaxZ - rangeMinZ + 1) * perBlockZ);
        x0 = Math.max(0, Math.min(base.getWidth() - 1, x0));
        z0 = Math.max(0, Math.min(base.getHeight() - 1, z0));
        width = Math.max(1, Math.min(base.getWidth() - x0, width));
        height = Math.max(1, Math.min(base.getHeight() - z0, height));
        if (x0 == 0 && z0 == 0 && width == base.getWidth() && height == base.getHeight()) {
            return true;
        }
        BufferedImage cropped = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = cropped.createGraphics();
        try {
            graphics.drawImage(base, 0, 0, width, height, x0, z0, x0 + width, z0 + height, null);
        } finally {
            graphics.dispose();
        }
        try {
            ImageIO.write(cropped, "png", image.toFile());
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /** 导出时在内容四周多带几圈地图（区块）：留白边不如多带一圈地形自然。 */
    private static final int EXPORT_PADDING_CHUNKS = 2;

    /** 地名允许缩到的最小字号；再小就没法读了。 */
    private static final int MIN_NAME_FONT = 20;

    /** 边框的粗细与虚实：屏幕上 1px 到这里就是 {@code exportScale} 像素，虚线周期同理。 */
    private static BasicStroke borderStroke(ProvinceSettings settings, double perBlockX, double exportScale) {
        // 屏幕上边框是固定几个 UI 像素（borderWidthPixels），不随缩放变；导出时就该按
        // 「导出图每方块像素 ÷ 屏幕每方块像素」同一个倍率放大。以前写的是 perBlockX / 16，
        // 那是拿每方块像素当缩放比、又莫名除了 16，全图导出时算出来只剩 1px，所以看着比预览细。
        float width = Math.max(1.0F, (float) (settings.borderWidthPixels() * exportScale * 2.0 / 3.0));
        float unit = (float) Math.max(1.0, exportScale);
        float[] dash = switch (settings.borderStyle) {
            case DASH -> new float[] {6.0F * unit, 4.0F * unit};
            case DASH_DOT -> new float[] {9.0F * unit, 4.0F * unit, 1.5F * unit, 4.0F * unit};
            default -> null;
        };
        if (dash == null) {
            return new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
        }
        return new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0F, dash, 0.0F);
    }

    /**
     * 往画布上写地名，返回真正画上去的块数。
     *
     * <p>字用的是游戏里那套像素字（{@link PixelFont}）：8 像素一格的点阵，汉字取自 unifont，
     * 和屏幕上看到的是同一套字形——之前这里是 Java2D 的矢量字，叠在像素风的地图上很突兀。</p>
     *
     * <p>规则跟屏幕上那套（{@code ProvinceRenderer.drawNames}）一致：逐块画，落点先试包围盒中心、
     * 不行再在包围盒里采样，只要求「落点在这块区划里」，整行字允许探出去一点。</p>
     *
     * <p>公开是为了让自检能在一块内存画布上直接验一遍；导出走的也是这个方法。</p>
     */
    public static int paintNames(Graphics2D graphics, List<Province> provinces, NameFormat format,
            double blockMinX, double blockMinZ, double perBlockX, double perBlockZ,
            double exportScale, int imageWidth, int imageHeight) {
        // 导出图是高分辨率位图，用系统字体（宋体／雅黑）落墨，不走 unifont 点阵：
        // 点阵字在几千像素宽的全图上要放大十几倍，中文会变成一块块方格拼起来的样子。
        java.awt.Font baseFont = pickExportFont(exportFontSize(perBlockX, imageWidth, imageHeight))
                .deriveFont(java.awt.Font.BOLD);
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS,
                java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        int slack = ProvinceSettings.get().nameSlack;
        List<int[]> taken = new ArrayList<>();
        int drawn = 0;
        int baseSize = baseFont.getSize();
        // 图像覆盖的世界矩形：区划只要和它有交集，就说明图上确实出现了它的色块。
        double worldMaxX = blockMinX + imageWidth / perBlockX;
        double worldMaxZ = blockMinZ + imageHeight / perBlockZ;
        for (Province province : provinces) {
            String label = province.displayName(format);
            if (label.isEmpty() || !touches(province.shape(), blockMinX, blockMinZ, worldMaxX, worldMaxZ)) {
                continue;
            }
            // 每个区划至少要有一个名字。先在基准字号下找一块放得下的地，摆不下就把这一条整体
            // 缩小再试；缩到 MIN_NAME_FONT 还没地方，就直接落在最大的一块上——宁可挤，不留空。
            boolean placed = false;
            for (int attempt = 0; attempt < 6 && !placed; attempt++) {
                float size = Math.max(MIN_NAME_FONT, baseSize * (float) Math.pow(0.8, attempt));
                java.awt.Font font = baseFont.deriveFont(size);
                graphics.setFont(font);
                java.awt.FontMetrics metrics = graphics.getFontMetrics();
                int textWidth = metrics.stringWidth(label);
                int lineHeight = metrics.getAscent() + metrics.getDescent();
                int unit = Math.max(1, Math.round(size / 8.0F));
                // 最后一轮：不再因为「撞车」放弃，挑面积最大的那块强制落墨。
                boolean force = attempt >= 5;
                VectorShape bestPart = null;
                double bestArea = -1.0;
                for (VectorShape part : province.shape().components()) {
                    VectorShape.Bounds partBounds = part.bounds();
                    if (partBounds == null) {
                        continue;
                    }
                    double area = partBounds.width() * partBounds.height();
                    if (area > bestArea) {
                        bestArea = area;
                        bestPart = part;
                    }
                    double boxW = partBounds.width() * perBlockX;
                    // 只卡横向：纵向很窄的细长地块照样要留名字，落点压在条带上、两端探出去一点。
                    if (!force && boxW + slack * unit < textWidth) {
                        continue;
                    }
                    if (paintOne(graphics, metrics, label, province.shape(), part, partBounds, blockMinX, blockMinZ,
                            perBlockX, perBlockZ, textWidth, lineHeight, unit,
                            imageWidth, imageHeight, taken, force)) {
                        placed = true;
                        drawn++;
                        break;
                    }
                }
                if (!placed && force && bestPart != null) {
                    VectorShape.Bounds partBounds = bestPart.bounds();
                    if (partBounds != null && paintOne(graphics, metrics, label, province.shape(), bestPart, partBounds,
                            blockMinX, blockMinZ, perBlockX, perBlockZ, textWidth, lineHeight, unit,
                            imageWidth, imageHeight, taken, true)) {
                        placed = true;
                        drawn++;
                    }
                }
            }
            if (!placed) {
                // 色块明明在图上，只是它包围盒的中心被裁到图外了（大区划只露一角就是这样），
                // 上面那套「按包围盒中心找落点」怎么试都失败。退一步：在可见范围里随便找一个
                // 属于它的点，把名字压上去——宁可贴边、宁可撞别人，也不能让色块没名字。
                graphics.setFont(baseFont.deriveFont((float) MIN_NAME_FONT));
                java.awt.FontMetrics metrics = graphics.getFontMetrics();
                if (paintNearest(graphics, metrics, label, province.shape(), blockMinX, blockMinZ,
                        perBlockX, perBlockZ, imageWidth, imageHeight, taken)) {
                    drawn++;
                }
            }
        }
        return drawn;
    }

    /** 在单块地上摆一个名字；返回 true 表示落墨了。 */
    private static boolean paintOne(Graphics2D graphics, java.awt.FontMetrics metrics, String label,
            VectorShape whole, VectorShape part, VectorShape.Bounds bounds, double blockMinX, double blockMinZ,
            double perBlockX, double perBlockZ, int textWidth, int lineHeight, int unit,
            int imageWidth, int imageHeight, List<int[]> taken, boolean ignoreClash) {
        // 半宽半高按世界坐标给，导出图和屏幕上用的是同一套摆位规则。
        // 最后那个参数是「整块地的形状」，nameAnchorForText 内部会拿它判断落点是否落在区划里；
        // 传 null 会在 contains 上直接 NPE（导出失败的那个 bug 就是这么来的）。
        double[] anchor = ProvinceRenderer.nameAnchorForText(part, bounds,
                textWidth / perBlockX / 2.0, lineHeight / perBlockZ / 2.0, whole);
        if (anchor == null) {
            return false;
        }
        int px = (int) Math.round((anchor[0] - blockMinX) * perBlockX);
        int py = (int) Math.round((anchor[1] - blockMinZ) * perBlockZ);
        if (px < -textWidth || px > imageWidth + textWidth
                || py < -lineHeight || py > imageHeight + lineHeight) {
            return false;
        }
        int left = px - textWidth / 2;
        int top = py - lineHeight / 2;
        if (!ignoreClash && overlaps(taken, left, top, textWidth, lineHeight)) {
            return false;
        }
        taken.add(new int[] {left, top, textWidth, lineHeight});
        drawLabel(graphics, metrics, label, left, top, unit);
        return true;
    }

    /** 区划和导出图覆盖的世界矩形有没有交集；没有就是压根不在这张图上。 */
    private static boolean touches(VectorShape shape, double minX, double minZ, double maxX, double maxZ) {
        VectorShape.Bounds bounds = shape.bounds();
        return bounds != null && bounds.maxX() > minX && bounds.minX() < maxX
                && bounds.maxZ() > minZ && bounds.minZ() < maxZ;
    }

    /**
     * 保底摆名：在可见范围里按像素网格找属于这块地的点，取离它包围盒中心最近的一处落墨。
     *
     * <p>只有 {@code paintOne} 那套「包围盒中心 + 采样」完全摆不上去时才会走到这里，
     * 也就是色块在图上、锚点却在图外的情况。找不到落在形状里的点（只剩一条比网格还细的边）就放弃。</p>
     */
    private static boolean paintNearest(Graphics2D graphics, java.awt.FontMetrics metrics, String label,
            VectorShape shape, double blockMinX, double blockMinZ, double perBlockX, double perBlockZ,
            int imageWidth, int imageHeight, List<int[]> taken) {
        VectorShape.Bounds bounds = shape.bounds();
        if (bounds == null) {
            return false;
        }
        int textWidth = metrics.stringWidth(label);
        int lineHeight = metrics.getAscent() + metrics.getDescent();
        int unit = Math.max(1, Math.round(metrics.getFont().getSize() / 8.0F));
        // 网格粗一点也不会漏：再细就只是白烧 CPU，96 格已经能扫到任何成片的色块。
        int stepX = Math.max(8, imageWidth / 96);
        int stepZ = Math.max(8, imageHeight / 96);
        double centerX = bounds.centerX();
        double centerZ = bounds.centerZ();
        List<double[]> spots = new ArrayList<>();
        for (int py = 0; py <= imageHeight; py += stepZ) {
            double worldZ = blockMinZ + py / perBlockZ;
            for (int px = 0; px <= imageWidth; px += stepX) {
                if (!shape.contains(blockMinX + px / perBlockX, worldZ)) {
                    continue;
                }
                double dx = px - (centerX - blockMinX) * perBlockX;
                double dz = py - (centerZ - blockMinZ) * perBlockZ;
                spots.add(new double[] {px, py, dx * dx + dz * dz});
            }
        }
        if (spots.isEmpty()) {
            return false;
        }
        spots.sort((a, b) -> Double.compare(a[2], b[2]));
        int limit = Math.min(spots.size(), 12);
        for (int i = 0; i < limit; i++) {
            double[] spot = spots.get(i);
            int left = clampInt((int) Math.round(spot[0] - textWidth / 2.0), 0,
                    Math.max(0, imageWidth - textWidth));
            int top = clampInt((int) Math.round(spot[1] - lineHeight / 2.0), 0,
                    Math.max(0, imageHeight - lineHeight));
            // 前几处都跟别人的名字撞了也没关系：最后一处强制落墨，保底比空着强。
            if (i < limit - 1 && overlaps(taken, left, top, textWidth, lineHeight)) {
                continue;
            }
            taken.add(new int[] {left, top, textWidth, lineHeight});
            drawLabel(graphics, metrics, label, left, top, unit);
            return true;
        }
        return false;
    }

    /** 阴影照 MC 的老规矩：右下各挪一格，画成半透明黑。 */
    private static void drawLabel(Graphics2D graphics, java.awt.FontMetrics metrics, String label,
            int left, int top, int unit) {
        int baseline = top + metrics.getAscent();
        graphics.setColor(new java.awt.Color(0xB0000000, true));
        graphics.drawString(label, left + unit, baseline + unit);
        graphics.setColor(java.awt.Color.WHITE);
        graphics.drawString(label, left, baseline);
    }

    private static boolean overlaps(List<int[]> taken, int left, int top, int w, int h) {
        for (int[] box : taken) {
            if (left < box[0] + box[2] && box[0] < left + w
                    && top < box[1] + box[3] && box[1] < top + h) {
                return true;
            }
        }
        return false;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Path2D.Double toPath(VectorShape shape, double blockMinX, double blockMinZ,
            double perBlockX, double perBlockZ) {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        boolean any = false;
        // 一块区划可能是好几座互不相连的岛（飞地、海外部分），也可能带洞。按 components 逐个建
        // 子路径、每个自己闭合——以前是把所有段堆在一起只 closePath 一次，两座岛之间会被连出
        // 一条假边，导出图上看着就像边界漏了一段。
        for (VectorShape part : shape.components()) {
            List<double[]> segments = part.segments();
            if (segments.isEmpty()) {
                continue;
            }
            double lastX = Double.NaN;
            double lastZ = Double.NaN;
            boolean started = false;
            for (double[] segment : segments) {
                if (segment.length < 4) {
                    continue;
                }
                double x0 = (segment[0] - blockMinX) * perBlockX;
                double z0 = (segment[1] - blockMinZ) * perBlockZ;
                double x1 = (segment[2] - blockMinX) * perBlockX;
                double z1 = (segment[3] - blockMinZ) * perBlockZ;
                // 用容差比较端点：浮点算出来的同一坐标未必逐位相等，直接 != 会把折线切碎。
                if (!started || Math.abs(x0 - lastX) > 1.0E-6 || Math.abs(z0 - lastZ) > 1.0E-6) {
                    path.moveTo(x0, z0);
                }
                path.lineTo(x1, z1);
                lastX = x1;
                lastZ = z1;
                started = true;
                any = true;
            }
            if (started) {
                path.closePath();
            }
        }
        return any ? path : null;
    }

    private static Set<Path> listPngs(Path directory) {
        Set<Path> found = new HashSet<>();
        if (!Files.isDirectory(directory)) {
            return found;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".png")).forEach(found::add);
        } catch (IOException e) {
            // 目录读不了就当它是空的，后面找不到新文件会给出提示。
        }
        return found;
    }

    private static Path findNewPng(Path directory, Set<Path> existing) {
        Path newest = null;
        long newestTime = Long.MIN_VALUE;
        for (Path path : listPngs(directory)) {
            if (existing.contains(path)) {
                continue;
            }
            try {
                long time = Files.getLastModifiedTime(path).toMillis();
                if (time > newestTime) {
                    newestTime = time;
                    newest = path;
                }
            } catch (IOException e) {
                // 拿不到时间戳就跳过这一个。
            }
        }
        return newest;
    }
}
