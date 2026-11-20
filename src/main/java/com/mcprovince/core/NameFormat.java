package com.mcprovince.core;

/**
 * 区划名字的显示格式。「母语名 + 级别名 / 英文名 + 级别名 / 只有母语名 / 只有英文名」四种。
 *
 * <p>0.2.3 之前这两栏叫「中文名」「英文名」。现在界面语言可以单独切，非中文玩家也会往
 * 第一栏填自己的母语，所以显示名改成「母语 / 英语」，存的键（{@code zh_full} 等）保持不变，
 * 老存档里的设置照样能读。</p>
 */
public enum NameFormat {
    ZH_FULL("zh_full"),
    EN_FULL("en_full"),
    ZH_ONLY("zh_only"),
    EN_ONLY("en_only");

    private final String key;

    NameFormat(String key) {
        this.key = key;
    }

    public String key() {
        return this.key;
    }

    public String label() {
        return Lang.t("province.name_format." + this.key);
    }

    public String description() {
        return Lang.t("province.name_format." + this.key + ".desc");
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
        String nativeName = province.nameZh();
        String en = province.nameEn();
        return switch (this) {
            case ZH_FULL -> concat(nativeName, nativeSuffix(province.levelKey()));
            case EN_FULL -> concatSpaced(en, AdminLevels.en(province.levelKey()));
            case ZH_ONLY -> nativeName;
            case EN_ONLY -> en;
        };
    }

    /**
     * 母语那套后缀：界面切到哪种语言，后缀就用那种语言的级别名。
     *
     * <p>「（县级）市」这类括号限定语落到地图上会被 {@link #visibleSuffix(String)}
     * 剪掉，只留通名。</p>
     */
    private static String nativeSuffix(String levelKey) {
        return AdminLevels.plainLabel(AdminLevels.byKey(levelKey));
    }

    /**
     * 拼到地名上的那段后缀。级别名可以带括号限定语（「（县级）市」），
     * 括号只用来在级别列表里区分同类建制，落到地图上只该留「市」，
     * 免得拼出「重庆（县级）市」这种念不通的名字。
     */
    static String visibleSuffix(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return "";
        }
        int close = Math.max(suffix.lastIndexOf('）'), suffix.lastIndexOf(')'));
        if (close >= 0 && close < suffix.length() - 1) {
            String tail = suffix.substring(close + 1).trim();
            if (!tail.isEmpty()) {
                return tail;
            }
        }
        return suffix;
    }

    private static String concat(String name, String suffix) {
        suffix = visibleSuffix(suffix);
        if (suffix.isEmpty()) {
            return name;
        }
        if (name.isEmpty()) {
            return suffix;
        }
        // 中日韩的级别名直接贴在地名后面；拉丁／西里尔／希腊字母那一侧是一个词，中间得留空格。
        boolean cjk = switch (Lang.current()) {
            case ZH_CN, ZH_TW, JA_JP, KO_KR -> true;
            default -> false;
        };
        return cjk ? name + suffix : name + " " + suffix;
    }

    private static String concatSpaced(String name, String suffix) {
        suffix = visibleSuffix(suffix);
        if (suffix.isEmpty()) {
            return name;
        }
        if (name.isEmpty()) {
            return suffix;
        }
        return name + " " + suffix;
    }
}
