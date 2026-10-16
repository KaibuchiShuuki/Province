package com.mcprovince.core;

/** 查看地图时给区划自动描的那一圈外框怎么画。 */
public enum BorderStyle {

    OFF("off", "不显示"),
    LINE("line", "实线"),
    DASH("dash", "虚线"),
    DASH_DOT("dash_dot", "点划线");

    private final String key;
    private final String label;

    BorderStyle(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return this.key;
    }

    public String label() {
        return this.label;
    }

    public static BorderStyle byKey(String key) {
        for (BorderStyle style : values()) {
            if (style.key.equals(key)) {
                return style;
            }
        }
        return OFF;
    }

    public BorderStyle shift(int delta) {
        BorderStyle[] all = values();
        int index = (this.ordinal() + delta) % all.length;
        if (index < 0) {
            index += all.length;
        }
        return all[index];
    }
}
