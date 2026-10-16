package com.mcprovince.edit;

/**
 * 行政区划编辑器里的工具。
 *
 * <p>这里没有「边框」这个概念了：所有工具都是【选区工具】，画出来的直接就是一块面积。
 * 选区笔和画笔是同一样东西，选区擦就是橡皮擦，魔棒对应画图软件里的油漆桶 / 魔棒。</p>
 */
public enum EditTool {

    SELECT_BRUSH("select_brush", "选区笔", "按住拖动，划到哪儿就选中哪儿"),
    SELECT_LINE("select_line", "直线", "两点之间拉一条选区"),
    SELECT_RECT("select_rect", "矩形", "拖出一块实心矩形选区"),
    SELECT_ELLIPSE("select_ellipse", "圆形", "拖出一块实心椭圆选区"),
    ERASER("eraser", "选区擦", "把已经选中的地方擦掉"),
    MAGIC("magic", "魔棒", "点一下，自动选中围出来的那一整块");

    private final String key;
    private final String label;
    private final String description;

    EditTool(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description;
    }

    public String key() {
        return this.key;
    }

    public String label() {
        return this.label;
    }

    public String description() {
        return this.description;
    }

    /** 一按下就要拖拽成形的工具（其余是自由累积或单击生效）。 */
    public boolean isDragShape() {
        return this == SELECT_LINE || this == SELECT_RECT || this == SELECT_ELLIPSE;
    }

    /** 这一笔是往选区里加，还是从选区里减。 */
    public boolean isErasing() {
        return this == ERASER;
    }

    /** 单击即生效，不需要拖拽。 */
    public boolean isClickOnly() {
        return this == MAGIC;
    }

}
