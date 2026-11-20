package com.mcprovince.edit;

import com.mcprovince.core.Lang;

/**
 * 行政区划编辑器里的工具。
 *
 * <p>这里没有「边框」这个概念了：所有工具都是【选区工具】，画出来的直接就是一块面积。
 * 选区笔和画笔是同一样东西，选区擦就是橡皮擦，魔棒对应画图软件里的油漆桶 / 魔棒。</p>
 */
public enum EditTool {

    SELECT_BRUSH("select_brush"),
    SELECT_LINE("select_line"),
    SELECT_RECT("select_rect"),
    SELECT_ELLIPSE("select_ellipse"),
    ERASER("eraser"),
    MAGIC("magic");

    private final String key;

    EditTool(String key) {
        this.key = key;
    }

    public String key() {
        return this.key;
    }

    public String label() {
        return Lang.t("province.edit_tool." + this.key);
    }

    public String description() {
        return Lang.t("province.edit_tool." + this.key + ".desc");
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
