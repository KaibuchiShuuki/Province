package com.mcprovince.core;

/**
 * 区划名字的显示格式。「重庆市 / Chongqing City / 重庆 / Chongqing」四种。
 */
public enum NameFormat {
    ZH_FULL("zh_full", "中文全称", "中文名 + 级别名"),
    EN_FULL("en_full", "英文全称", "英文名 + 级别名"),
    ZH_ONLY("zh_only", "仅中文名", "只显示中文名"),
    EN_ONLY("en_only", "仅英文名", "只显示英文名");

    private final String key;
    private final String label;
    private final String description;

    NameFormat(String key, String label, String description) {
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

    public static NameFormat byKey(String key) {
        if (key != null) {
            for (NameFormat format : values()) {
                if (format.key.equals(key)) {
                    return format;
                }
            }
        }
        return ZH_FULL;
    }

    /** 按本格式拼出一个区划的显示名。 */
    public String format(Province province) {
        String zh = province.nameZh();
        String en = province.nameEn();
        return switch (this) {
            case ZH_FULL -> concat(zh, AdminLevels.zh(province.levelKey()));
            case EN_FULL -> concatSpaced(en, AdminLevels.en(province.levelKey()));
            case ZH_ONLY -> zh;
            case EN_ONLY -> en;
        };
    }

    private static String concat(String name, String suffix) {
        if (suffix.isEmpty()) {
            return name;
        }
        if (name.isEmpty()) {
            return suffix;
        }
        return name + suffix;
    }

    private static String concatSpaced(String name, String suffix) {
        if (suffix.isEmpty()) {
            return name;
        }
        if (name.isEmpty()) {
            return suffix;
        }
        return name + " " + suffix;
    }
}
