package com.mcprovince.client;

import com.mcprovince.core.Lang;

/**
 * 序号注释贴在屏幕的哪个角。
 *
 * <p>四个角都可能压着 Xaero 自己的东西（工具条、坐标、缩放按钮），所以做成可调，
 * 主人觉得哪儿碍事就挪走。</p>
 */
public enum SerialCorner {

    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT;

    public String label() {
        return Lang.t("province.serial_corner." + this.name());
    }

    /** 循环移位：设置界面上的 - / + 就是这么翻的。 */
    public SerialCorner shift(int delta) {
        SerialCorner[] values = values();
        return values[(this.ordinal() + delta + values.length) % values.length];
    }

    /** 存档里存的是枚举名；认不出来就退回右上。 */
    public static SerialCorner byName(String name) {
        for (SerialCorner value : values()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return TOP_RIGHT;
    }
}
