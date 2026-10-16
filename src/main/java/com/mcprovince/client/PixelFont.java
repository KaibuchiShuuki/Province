package com.mcprovince.client;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 纯 CPU（java.awt）复刻 Minecraft 原版像素字体的渲染器，用于把地名标签画进导出的 PNG 地图。
 *
 * <p>字形来源与度量规则全部照抄 26.2 客户端：
 * <ul>
 *   <li>ASCII（U+0020..U+007E）取自 {@code minecraft:textures/font/ascii.png}，16x16 网格、每格 8x8，
 *       第 3 行第 1 格起是 U+0020（{@code include/default.json} 的 {@code ascent: 7}，这里只用到字形本身）。
 *       advance 与 {@code BitmapProvider} 一致：{@code (int)(0.5 + actualWidth * scale) + 1}，
 *       其中 {@code actualWidth} 是该格非透明像素的最右列 + 1，{@code scale = 1}。</li>
 *   <li>其它字符取自 {@code minecraft:font/unifont.zip} 内的 GNU Unifont hex 文件。
 *       度量与 {@code UnihexProvider} 一致：{@code left}/{@code right} 来自命中的 {@code size_overrides}，
 *       否则由 16 行位图按位或后裁剪左右空列得到；{@code width = right - left + 1}；
 *       显示宽度为 {@code width / 2} 逻辑像素（{@code getOversample() == 2}），
 *       advance 为 {@code width / 2f + 1f}。</li>
 * </ul>
 *
 * <p>本类不触碰 OpenGL、不依赖渲染线程，也不在类加载期读文件：资源只在首次 {@link #load(Minecraft)} 时读取一次并缓存。
 * 资源读不到时 {@link #ready()} 返回 {@code false}，{@link #drawString} 静默不画，调用方可退回旧实现。
 */
public final class PixelFont {

    /** ascii.png 的网格列数与行数。 */
    private static final int ASCII_GRID = 16;
    /** ascii.png 中 ASCII 区的起始行（前两行是 \u0000 占位）。 */
    private static final int ASCII_FIRST_ROW = 2;
    /** ASCII 可打印区间的两端。 */
    private static final int ASCII_FIRST_CODEPOINT = 0x20;
    private static final int ASCII_LAST_CODEPOINT = 0x7E;
    /** 每个 ASCII 格子的边长（像素），也是逻辑行高的一部分。 */
    private static final int ASCII_CELL = 8;
    /** Unifont 位图固定 16 行。 */
    private static final int UNIFONT_HEIGHT = 16;
    /** {@code UnihexProvider.GlyphBitmap.getOversample()} == 2，即 16px 位图显示为 8 逻辑像素。 */
    private static final int OVERSAMPLE = 2;
    /** 逻辑行高（像素）：ASCII 格高 8，汉字 16/2 = 8。 */
    private static final int LINE_HEIGHT = 8;
    /** {@code include/space.json} 给空格的 advance（ascii.png 自身只有 1）。 */
    private static final float SPACE_ADVANCE = 4.0f;
    /** 找不到字形时占位的推进量。 */
    private static final float MISSING_ADVANCE = 4.0f;

    private static final ResourceLocation ASCII_TEXTURE = new ResourceLocation("textures/font/ascii.png");
    private static final ResourceLocation UNIFONT_ARCHIVE = new ResourceLocation("font/unifont.zip");

    /**
     * unifont.json 里 unihex provider（hex_file = minecraft:font/unifont.zip）的 size_overrides，按 json 顺序。
     * 每项为 {from, to, left, right}；重叠区间由先出现者优先（与 {@code UnihexProvider.Definition.loadData} 一致）。
     * unifont_jp.zip 的日文补丁 provider 被忽略。
     */
    private static final int[][] SIZE_OVERRIDES = {
        {0x3001, 0x30FF, 0, 15},
        {0x3200, 0x9FFF, 0, 15},
        {0x1100, 0x11FF, 0, 15},
        {0x3130, 0x318F, 0, 15},
        {0xA960, 0xA97F, 0, 15},
        {0xD7B0, 0xD7FF, 0, 15},
        {0xAC00, 0xD7AF, 1, 15},
        {0xF900, 0xFAFF, 0, 15},
        {0xFF01, 0xFF5E, 0, 15},
    };

    private static final Object LOCK = new Object();

    private static volatile boolean loaded;
    private static String status = "pixel-font: not loaded";

    // ---- ASCII 位图（8x8 一格，按位打包成 long：bit (y * 8 + x)） ----
    private static boolean asciiTried;
    private static boolean asciiReady;
    private static long[] asciiBits = new long[0];
    private static short[] asciiAdvance = new short[0];
    private static int asciiGlyphs;
    private static String asciiError;

    // ---- Unifont（原始 hex 字节 + 码点索引，字形按需解码） ----
    private static boolean unifontTried;
    private static boolean unifontReady;
    private static byte[] hexData = new byte[0];
    private static TreeMap<Integer, int[]> hexIndex = new TreeMap<>();
    private static String unifontError;

    private PixelFont() {
    }

    /** 幂等；首次调用时从 Minecraft 资源管理器读出字形数据并缓存。可在任意线程调用。 */
    public static void load(Minecraft minecraft) {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            String failure = null;
            try {
                if (minecraft == null) {
                    failure = "Minecraft 实例为 null";
                } else {
                    ResourceManager resources = minecraft.getResourceManager();
                    if (resources == null) {
                        failure = "资源管理器为 null";
                    } else {
                        if (!asciiTried) {
                            loadAscii(resources);
                        }
                        if (!unifontTried) {
                            loadUnifont(resources);
                        }
                    }
                }
            } catch (Throwable t) {
                failure = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            loaded = asciiReady || unifontReady;
            status = buildStatus(failure);
        }
    }

    /**
     * 真要画字之前调一次：还没成功就试一次，而且失败不锁死。
     *
     * <p>客户端初始化最早期（比如开发自检）资源包还没加载完，那时读文件必然落空；
     * 等资源就绪后再调一次，它只补做没成功的那部分，已经成功的不会重来。</p>
     */
    public static void ensureLoaded(Minecraft minecraft) {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            asciiTried = asciiReady;
            unifontTried = unifontReady;
        }
        load(minecraft);
    }

    /** 是否已成功加载（否则调用方会退回旧实现）。 */
    public static boolean ready() {
        return loaded;
    }

    /** 文本的逻辑像素宽度（未乘 scale）。等于各字符 advance 之和，与 {@code Font#width} 的口径一致。 */
    public static int textWidth(String text) {
        if (text == null || text.isEmpty() || !loaded) {
            return 0;
        }
        int[] masks = new int[UNIFONT_HEIGHT];
        int[] size = new int[2];
        float width = 0.0f;
        for (int i = 0; i < text.length(); ) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);
            if (codepoint == '\n' || codepoint == '\r') {
                continue;
            }
            float advance = advanceOf(codepoint, masks, size);
            width += advance > 0.0f ? advance : MISSING_ADVANCE;
        }
        return (int) Math.ceil(width);
    }

    /** 字形行高（逻辑像素），即一个汉字字形的高度。 */
    public static int lineHeight() {
        return LINE_HEIGHT;
    }

    /**
     * 在 x,y（文本左上角）处绘制文本，每个字体像素放大 scale 倍（scale >= 1，用整数方块填充保证像素感）。
     * 只画前景色；阴影由调用方自己画两次实现。无法显示的字符画问号，问号也缺失时跳过。
     */
    public static void drawString(Graphics2D graphics, String text, int x, int y, int scale, Color color) {
        if (graphics == null || text == null || text.isEmpty() || !loaded) {
            return;
        }
        int pixelScale = Math.max(1, scale);
        Color previous = graphics.getColor();
        graphics.setColor(color == null ? Color.WHITE : color);
        int[] masks = new int[UNIFONT_HEIGHT];
        int[] size = new int[2];
        float cursor = 0.0f;
        for (int i = 0; i < text.length(); ) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);
            if (codepoint == '\n' || codepoint == '\r') {
                continue;
            }
            int penX = x + (int) Math.floor(cursor * pixelScale);
            float advance = drawGlyph(graphics, codepoint, penX, y, pixelScale, masks, size);
            cursor += advance > 0.0f ? advance : MISSING_ADVANCE;
        }
        graphics.setColor(previous);
    }

    /** 返回一段人类可读的状态描述，例如 "pixel-font: ascii=95 unifont=114432 loaded" 或失败原因。 */
    public static String describe() {
        return status;
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    private static void loadAscii(ResourceManager resources) {
        asciiTried = true;
        try {
            Optional<Resource> resource = resources.getResource(ASCII_TEXTURE);
            if (resource.isEmpty()) {
                asciiError = ASCII_TEXTURE + " 不存在";
                return;
            }
            try (InputStream in = resource.get().open()) {
                BufferedImage image = ImageIO.read(in);
                if (image == null) {
                    asciiError = "ascii.png 解码失败";
                    return;
                }
                int gridWidth = image.getWidth() / ASCII_GRID;
                int gridHeight = image.getHeight() / ASCII_GRID;
                if (gridWidth <= 0 || gridHeight <= 0) {
                    asciiError = "ascii.png 尺寸异常(" + image.getWidth() + "x" + image.getHeight() + ")";
                    return;
                }
                int imageWidth = image.getWidth();
                int[] pixels = image.getRGB(0, 0, imageWidth, image.getHeight(), null, 0, imageWidth);
                int count = ASCII_LAST_CODEPOINT - ASCII_FIRST_CODEPOINT + 1;
                long[] bits = new long[count];
                short[] advances = new short[count];
                int columns = Math.min(gridWidth, ASCII_CELL);
                int rows = Math.min(gridHeight, ASCII_CELL);
                for (int index = 0; index < count; index++) {
                    int codepoint = ASCII_FIRST_CODEPOINT + index;
                    int row = ASCII_FIRST_ROW + index / ASCII_GRID;
                    int col = index % ASCII_GRID;
                    if (row * gridHeight + rows > image.getHeight()) {
                        break;
                    }
                    long cell = 0L;
                    for (int y = 0; y < rows; y++) {
                        int base = (row * gridHeight + y) * imageWidth + col * gridWidth;
                        for (int x = 0; x < columns; x++) {
                            if (((pixels[base + x] >>> 24) & 0xFF) != 0) {
                                cell |= 1L << (y * ASCII_CELL + x);
                            }
                        }
                    }
                    bits[index] = cell;
                    int actualWidth = actualAsciiWidth(pixels, imageWidth, gridWidth, gridHeight, col, row);
                    // BitmapProvider：advance = (int)(0.5 + actualWidth * scale) + 1，scale = json height / glyphHeight = 1
                    advances[index] = (short) ((int) (0.5D + (double) (actualWidth * 1.0f)) + 1);
                }
                // include/space.json 把空格覆盖为 4
                advances[0] = (short) SPACE_ADVANCE;
                asciiBits = bits;
                asciiAdvance = advances;
                asciiGlyphs = count;
                asciiReady = true;
                asciiError = null;
            }
        } catch (Throwable t) {
            asciiError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 与 {@code BitmapProvider.getActualGlyphWidth} 等价：非透明像素的最右列 + 1，整格全空为 0。 */
    private static int actualAsciiWidth(int[] pixels, int imageWidth, int gridWidth, int gridHeight, int col, int row) {
        for (int x = gridWidth - 1; x >= 0; x--) {
            int column = col * gridWidth + x;
            for (int y = 0; y < gridHeight; y++) {
                if (((pixels[(row * gridHeight + y) * imageWidth + column] >>> 24) & 0xFF) != 0) {
                    return x + 1;
                }
            }
        }
        return 0;
    }

    private static void loadUnifont(ResourceManager resources) {
        unifontTried = true;
        try {
            Optional<Resource> resource = resources.getResource(UNIFONT_ARCHIVE);
            if (resource.isEmpty()) {
                unifontError = UNIFONT_ARCHIVE + " 不存在";
                return;
            }
            byte[] data;
            try (InputStream in = resource.get().open()) {
                data = readHexEntry(in);
            }
            if (data == null) {
                unifontError = "unifont.zip 内没有 .hex 文件";
                return;
            }
            TreeMap<Integer, int[]> index = indexHex(data);
            if (index.isEmpty()) {
                unifontError = "unifont hex 内容为空";
                return;
            }
            hexData = data;
            hexIndex = index;
            unifontReady = true;
            unifontError = null;
        } catch (Throwable t) {
            unifontError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 在 zip 流里找到第一个以 .hex 结尾的条目并整体读出（LICENSE.txt 之类的必须先跳过）。 */
    private static byte[] readHexEntry(InputStream in) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().endsWith(".hex")) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 23);
                    byte[] buffer = new byte[1 << 16];
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                    return out.toByteArray();
                }
            }
        }
        return null;
    }

    /** 建立「码点 -> {hex 段偏移, 位图位宽}」索引；位图本身留到绘制时再解码。 */
    private static TreeMap<Integer, int[]> indexHex(byte[] data) {
        TreeMap<Integer, int[]> index = new TreeMap<>();
        int position = 0;
        int length = data.length;
        while (position < length) {
            int lineEnd = position;
            while (lineEnd < length && data[lineEnd] != '\n') {
                lineEnd++;
            }
            int end = lineEnd;
            while (end > position && (data[end - 1] == '\r' || data[end - 1] == ' ')) {
                end--;
            }
            if (end > position) {
                int colon = -1;
                for (int i = position; i < end; i++) {
                    if (data[i] == ':') {
                        colon = i;
                        break;
                    }
                }
                if (colon > position) {
                    int codepoint = parseHex(data, position, colon);
                    if (codepoint >= 0) {
                        int hexLength = end - colon - 1;
                        int bitWidth = hexLength / 4;
                        if (bitWidth == 8 || bitWidth == 16) {
                            index.put(codepoint, new int[] {colon + 1, bitWidth});
                        }
                    }
                }
            }
            position = lineEnd + 1;
        }
        return index;
    }

    private static int parseHex(byte[] data, int from, int to) {
        int value = 0;
        for (int i = from; i < to; i++) {
            int digit = hexValue(data[i]);
            if (digit < 0) {
                return -1;
            }
            value = (value << 4) | digit;
            if (value > 0x10FFFF) {
                return -1;
            }
        }
        return to > from ? value : -1;
    }

    private static int hexValue(byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 度量与绘制
    // ------------------------------------------------------------------

    private static float advanceOf(int codepoint, int[] masks, int[] size) {
        if (codepoint == ' ') {
            return SPACE_ADVANCE;
        }
        if (asciiReady && codepoint >= ASCII_FIRST_CODEPOINT && codepoint <= ASCII_LAST_CODEPOINT) {
            return asciiAdvance[codepoint - ASCII_FIRST_CODEPOINT];
        }
        int[] meta = unifontReady ? hexIndex.get(codepoint) : null;
        if (meta != null && decodeUnifont(codepoint, meta, masks, size)) {
            int width = size[1] - size[0] + 1;
            return width / (float) OVERSAMPLE + 1.0f;
        }
        return 0.0f;
    }

    /** 画一个字形并返回它的 advance；返回 0 表示这个字符没有字形。 */
    private static float drawGlyph(Graphics2D graphics, int codepoint, int x, int y, int scale, int[] masks, int[] size) {
        if (codepoint == ' ') {
            return SPACE_ADVANCE;
        }
        if (asciiReady && codepoint >= ASCII_FIRST_CODEPOINT && codepoint <= ASCII_LAST_CODEPOINT) {
            int index = codepoint - ASCII_FIRST_CODEPOINT;
            long cell = asciiBits[index];
            if (cell != 0L) {
                for (int py = 0; py < ASCII_CELL; py++) {
                    for (int px = 0; px < ASCII_CELL; px++) {
                        if ((cell & (1L << (py * ASCII_CELL + px))) != 0L) {
                            graphics.fillRect(x + px * scale, y + py * scale, scale, scale);
                        }
                    }
                }
            }
            return asciiAdvance[index];
        }
        int[] meta = unifontReady ? hexIndex.get(codepoint) : null;
        if (meta != null && decodeUnifont(codepoint, meta, masks, size)) {
            int left = size[0];
            int right = size[1];
            int bitWidth = meta[1];
            int width = right - left + 1;
            int columns = (width + OVERSAMPLE - 1) / OVERSAMPLE;
            for (int dy = 0; dy < LINE_HEIGHT; dy++) {
                int bitmapY = dy * OVERSAMPLE;
                for (int dx = 0; dx < columns; dx++) {
                    int bitmapX = left + dx * OVERSAMPLE;
                    if (anyPixel(masks, bitWidth, bitmapX, bitmapY)) {
                        graphics.fillRect(x + dx * scale, y + dy * scale, scale, scale);
                    }
                }
            }
            return width / (float) OVERSAMPLE + 1.0f;
        }
        if (codepoint != '?') {
            return drawGlyph(graphics, '?', x, y, scale, masks, size);
        }
        return 0.0f;
    }

    /** oversample = 2：2x2 位图块中只要有墨水就点亮该逻辑像素。 */
    private static boolean anyPixel(int[] masks, int bitWidth, int x, int y) {
        for (int sy = 0; sy < OVERSAMPLE; sy++) {
            int py = y + sy;
            if (py < 0 || py >= UNIFONT_HEIGHT) {
                continue;
            }
            for (int sx = 0; sx < OVERSAMPLE; sx++) {
                int px = x + sx;
                if (px >= 0 && px < bitWidth && (masks[py] & (1 << (31 - px))) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 解码一个 unifont 字形：填出 16 行位图与 left/right/位宽。
     * left/right 优先取命中的 size_overrides，否则按内容裁剪（最左 = bit31）。
     */
    private static boolean decodeUnifont(int codepoint, int[] meta, int[] masks, int[] size) {
        byte[] data = hexData;
        int offset = meta[0];
        int bitWidth = meta[1];
        int digitsPerRow = bitWidth / 4;
        if (offset + bitWidth * 4 > data.length) {
            return false;
        }
        int union = 0;
        for (int y = 0; y < UNIFONT_HEIGHT; y++) {
            int value = 0;
            int base = offset + y * digitsPerRow;
            for (int d = 0; d < digitsPerRow; d++) {
                int digit = hexValue(data[base + d]);
                if (digit < 0) {
                    return false;
                }
                value = (value << 4) | digit;
            }
            int mask = value << (32 - bitWidth);
            masks[y] = mask;
            union |= mask;
        }
        int left;
        int right;
        int[] override = overrideFor(codepoint);
        if (override != null) {
            left = override[2];
            right = override[3];
        } else if (union == 0) {
            left = 0;
            right = bitWidth;
        } else {
            left = Integer.numberOfLeadingZeros(union);
            right = 32 - Integer.numberOfTrailingZeros(union) - 1;
        }
        size[0] = left;
        size[1] = right;
        return true;
    }

    private static int[] overrideFor(int codepoint) {
        for (int[] range : SIZE_OVERRIDES) {
            if (codepoint >= range[0] && codepoint <= range[1]) {
                return range;
            }
        }
        return null;
    }

    private static String buildStatus(String failure) {
        StringBuilder builder = new StringBuilder("pixel-font: ");
        builder.append("ascii=").append(asciiReady ? asciiGlyphs : 0);
        builder.append(" unifont=").append(unifontReady ? hexIndex.size() : 0);
        builder.append(loaded ? " loaded" : " FAILED");
        if (failure != null) {
            builder.append(" [load: ").append(failure).append(']');
        }
        if (asciiError != null) {
            builder.append(" [ascii: ").append(asciiError).append(']');
        }
        if (unifontError != null) {
            builder.append(" [unifont: ").append(unifontError).append(']');
        }
        return builder.toString();
    }
}
