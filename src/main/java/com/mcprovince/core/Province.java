package com.mcprovince.core;

/**
 * 一档行政区划。
 *
 * <p>层级（{@link #tier()}，1..5）由父区划决定：没有父区划就是第 1 级，
 * 其余等于父的层级 +1。区划的几何是 {@link VectorShape}——矢量表示，边界吸附在方块格线上。</p>
 *
 * <p>{@link #order()} 只管同级之间在树状图里的先后，跟地图上的位置、形状、层级都没关系；
 * {@link #visible()} 是「这一块现在显不显示」，默认跟着设置里的「显示第几级」走，
 * 用户可以逐块改。</p>
 */
public final class Province {

    /** 没有父区划（第 1 级）时用的 parentId。 */
    public static final int NO_PARENT = -1;

    public static final int MIN_TIER = 1;
    public static final int MAX_TIER = 5;

    private final int id;
    private final int parentId;
    private final int tier;
    private String nameZh;
    private String nameEn;
    private String levelKey;
    private int color;
    private boolean visible;
    /** 同级之间的显示顺序，越小越靠前；只影响树状图怎么排。 */
    private int order;
    /** 上一帧有没有把地名画到屏幕上。渲染层写、序号标记读，纯运行期状态。 */
    private boolean nameDrawn = true;
    private final VectorShape shape = VectorShape.empty();

    /**
     * 地名落点的缓存。挑落点要在一块地的形状里撒 144 个采样点、每个点再做十次包含测试，
     * 而形状和字号没变的时候结果也不会变——区划越大 {@link java.awt.geom.Area#contains}
     * 越贵，这一份缓存省下来的正是「大地图卡顿」里最刺眼的那一块。
     */
    private VectorShape anchorPart;
    private double anchorHalfW = Double.NaN;
    private double anchorHalfH = Double.NaN;
    private double[] anchorPoint;
    /** 上一次挑到的落点是不是「九点全中」那一档；只有这一档才敢往更小的字号上复用。 */
    private boolean anchorExact;

    /**
     * 地名文本的缓存，按名称格式各存一份。
     *
     * <p>同一帧里地图渲染要 {@code nameFormat}、进区划提示要 {@code titleFormat}，两个格式交替问
     * 同一块地；只留一个槽位的话每次都被对方冲掉，等于没缓存。格式只有四种，一个数组就够。</p>
     */
    private final String[] labelTexts = new String[NameFormat.values().length];

    private void invalidateLabel() {
        java.util.Arrays.fill(this.labelTexts, null);
    }

    public Province(int id, int parentId, int tier, String nameZh, String nameEn, String levelKey, int color) {
        this.id = id;
        this.parentId = parentId;
        this.tier = tier;
        this.nameZh = nameZh == null ? "" : nameZh;
        this.nameEn = nameEn == null ? "" : nameEn;
        this.levelKey = levelKey == null ? AdminLevels.DEFAULT_KEY : levelKey;
        this.color = color & 0xFFFFFF;
        this.visible = true;
        // 没被挪过的话就按 id 排，跟老版本的表现一致。
        this.order = id;
    }

    public int id() {
        return this.id;
    }

    public int parentId() {
        return this.parentId;
    }

    public int tier() {
        return this.tier;
    }

    public String nameZh() {
        return this.nameZh;
    }

    public void setNameZh(String value) {
        this.nameZh = value == null ? "" : value;
        this.invalidateLabel();
    }

    public String nameEn() {
        return this.nameEn;
    }

    public void setNameEn(String value) {
        this.nameEn = value == null ? "" : value;
        this.invalidateLabel();
    }

    public String levelKey() {
        return this.levelKey;
    }

    public void setLevelKey(String value) {
        this.levelKey = value == null ? AdminLevels.DEFAULT_KEY : value;
        this.invalidateLabel();
    }

    /** 代表色，0xRRGGBB。 */
    public int color() {
        return this.color;
    }

    public void setColor(int rgb) {
        this.color = rgb & 0xFFFFFF;
    }

    public boolean visible() {
        return this.visible;
    }

    public void setVisible(boolean value) {
        this.visible = value;
    }

    public int order() {
        return this.order;
    }

    public void setOrder(int value) {
        this.order = value;
    }

    /** 上一帧这块区划有没有画出地名；没有的话序号标记会顶上来。 */
    public boolean nameDrawn() {
        return this.nameDrawn;
    }

    public void setNameDrawn(boolean value) {
        this.nameDrawn = value;
    }

    /** 几何形状；调用方可以直接改，改完记得通知存档层。 */
    public VectorShape shape() {
        return this.shape;
    }

    /**
     * 上一次算好的地名落点。
     *
     * <p>字号只会随着地图缩放连续地变，而「更小的字」一定还塞得进「更大的字」找出来的那个位置：
     * 九个采样点全在区划里的时候，把它们整体往中心收，收出来的还是一个更小的矩形。
     * 所以只要这一次要的半宽半高不超过上次挑点时用的，这个落点就可以直接用——不必每滚一格
     * 滚轮就把整块地重新撒一遍点。缩小地图（落点最挤、区划最多的情况）走的正是这条路。</p>
     */
    public double[] cachedNameAnchor(VectorShape part, double halfW, double halfH) {
        if (this.anchorPart != part || this.anchorPoint == null) {
            return null;
        }
        // 字号一模一样：什么档次的落点都能直接用。
        if (halfW == this.anchorHalfW && halfH == this.anchorHalfH) {
            return this.anchorPoint;
        }
        // 字号变小（地图缩小）：只有「九点全中」那一档才敢往小字号上推。
        if (this.anchorExact && halfW <= this.anchorHalfW && halfH <= this.anchorHalfH) {
            return this.anchorPoint;
        }
        return null;
    }

    public void setNameAnchor(VectorShape part, double halfW, double halfH, double[] point, boolean exact) {
        this.anchorPart = part;
        this.anchorHalfW = halfW;
        this.anchorHalfH = halfH;
        this.anchorPoint = point;
        this.anchorExact = exact;
    }

    public String displayName(NameFormat format) {
        int slot = format.ordinal();
        String text = this.labelTexts[slot];
        if (text != null) {
            return text;
        }
        text = format.format(this);
        this.labelTexts[slot] = text;
        return text;
    }

    @Override
    public String toString() {
        return "Province#" + this.id + "[" + NameFormat.ZH_FULL.format(this) + ", tier=" + this.tier
                + ", area=" + Math.round(this.shape.measure()) + "]";
    }
}
