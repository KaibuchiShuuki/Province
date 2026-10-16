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

    /** 导出的两种范围。 */
    public enum ExportMode {
        /** 屏幕上正显示的那一块。 */
        SCREEN("地图"),
        /** 整个世界地图已经加载过的区域。 */
        WORLD("世界全图");

        private final String label;

        ExportMode(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

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
        requested = true;
        requestedMode = mode;
        requestDrawn = false;
        requestNanos = System.nanoTime();
        notice = "正在导出" + mode.label() + "…";
        noticeKind = NOTICE_RUNNING;
        noticeUntil = Long.MAX_VALUE;
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
            if (bounds == null) {
                return new Result(false, "世界地图上还没加载出任何区域");
            }
            leftChunk = bounds[0];
            topChunk = bounds[1];
            rightChunk = bounds[2];
            bottomChunk = bounds[3];
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
        if (ProvinceSettings.get().exportWithProvinces) {
            overlayProvinces(image, leftChunk, topChunk, rightChunk, bottomChunk, pixelsPerBlock);
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
    private static void overlayProvinces(Path image,
            int leftChunk, int topChunk, int rightChunk, int bottomChunk, double screenPixelsPerBlock) {
        BufferedImage base;
        try {
            base = ImageIO.read(image.toFile());
        } catch (IOException e) {
            return;
        }
        if (base == null || base.getWidth() <= 0 || base.getHeight() <= 0) {
            return;
        }
        double blockMinX = leftChunk * 16.0;
        double blockMinZ = topChunk * 16.0;
        double blockMaxX = (rightChunk + 1) * 16.0;
        double blockMaxZ = (bottomChunk + 1) * 16.0;
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
            for (Province province : store.allSorted()) {
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
        }
    }

    /** 边框的粗细与虚实：屏幕上 1px 到这里就是 {@code exportScale} 像素，虚线周期同理。 */
    private static BasicStroke borderStroke(ProvinceSettings settings, double perBlockX, double exportScale) {
        float width = Math.max(1.0F, (float) (settings.borderWidthPixels() * perBlockX / 16.0));
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
        // 字形来自客户端资源包，真到要落墨的时候才去取（启动自检跑得比资源加载早）。
        PixelFont.ensureLoaded(Minecraft.getInstance());
        // 像素字只能整格放大：屏幕上一格 8 像素，导出图里按同样的观感放大整数倍。
        int scale = Math.max(1, Math.min(16, (int) Math.round(exportScale)));
        int lineHeight = PixelFont.lineHeight() * scale;
        int drawn = 0;
        for (Province province : provinces) {
            String label = province.displayName(format);
            if (label.isEmpty()) {
                continue;
            }
            int textWidth = PixelFont.textWidth(label) * scale;
            for (VectorShape part : province.shape().components()) {
                VectorShape.Bounds bounds = part.bounds();
                if (bounds == null) {
                    continue;
                }
                double boxW = bounds.width() * perBlockX;
                // 只卡横向：纵向很窄的细长地块照样要有名字，落点压在条带上、两端探出去一点。
                // 尺子跟屏幕上那套共用同一个设置值（按导出放大倍数放大）——落点搜索先找宽敞处，
                // 这里只判最终装不装得下。
                if (boxW + ProvinceSettings.get().nameSlack * scale < textWidth) {
                    continue;
                }
                // 半宽半高按世界坐标给，导出图和屏幕上用的是同一套摆位规则。
                double[] anchor = ProvinceRenderer.nameAnchorForText(part, bounds,
                        textWidth / perBlockX / 2.0, lineHeight / perBlockZ / 2.0, province.shape());
                if (anchor == null) {
                    continue;
                }
                int px = (int) Math.round((anchor[0] - blockMinX) * perBlockX);
                int py = (int) Math.round((anchor[1] - blockMinZ) * perBlockZ);
                if (px < -textWidth || px > imageWidth + textWidth
                        || py < -lineHeight || py > imageHeight + lineHeight) {
                    continue;
                }
                int left = px - textWidth / 2;
                int top = py - lineHeight / 2;
                // 阴影照 MC 的老规矩：右下各挪一格，画成半透明黑。
                PixelFont.drawString(graphics, label, left + scale, top + scale, scale,
                        new java.awt.Color(0xB0000000, true));
                PixelFont.drawString(graphics, label, left, top, scale, java.awt.Color.WHITE);
                drawn++;
            }
        }
        return drawn;
    }

    private static Path2D.Double toPath(VectorShape shape, double blockMinX, double blockMinZ,
            double perBlockX, double perBlockZ) {
        List<double[]> segments = shape.segments();
        if (segments.isEmpty()) {
            return null;
        }
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        double lastX = Double.NaN;
        double lastZ = Double.NaN;
        for (double[] segment : segments) {
            if (segment.length < 4) {
                continue;
            }
            double x0 = (segment[0] - blockMinX) * perBlockX;
            double z0 = (segment[1] - blockMinZ) * perBlockZ;
            double x1 = (segment[2] - blockMinX) * perBlockX;
            double z1 = (segment[3] - blockMinZ) * perBlockZ;
            if (x0 != lastX || z0 != lastZ) {
                path.moveTo(x0, z0);
            }
            path.lineTo(x1, z1);
            lastX = x1;
            lastZ = z1;
        }
        path.closePath();
        return path;
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
