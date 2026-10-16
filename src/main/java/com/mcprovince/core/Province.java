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
    }

    public String nameEn() {
        return this.nameEn;
    }

    public void setNameEn(String value) {
        this.nameEn = value == null ? "" : value;
    }

    public String levelKey() {
        return this.levelKey;
    }

    public void setLevelKey(String value) {
        this.levelKey = value == null ? AdminLevels.DEFAULT_KEY : value;
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

    public String displayName(NameFormat format) {
        return format.format(this);
    }

    @Override
    public String toString() {
        return "Province#" + this.id + "[" + NameFormat.ZH_FULL.format(this) + ", tier=" + this.tier
                + ", area=" + Math.round(this.shape.measure()) + "]";
    }
}
