package com.mcprovince.core;

/** 查看地图时给区划自动描的那一圈外框怎么画。 */
public enum BorderStyle {

    OFF("off"),
    LINE("line"),
    DASH("dash"),
    DASH_DOT("dash_dot");

    private final String key;

    BorderStyle(String key) {
        this.key = key;
    }

    public String key() {
        return this.key;
    }

    public String label() {
        return Lang.t("province.border_style." + this.key);
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
