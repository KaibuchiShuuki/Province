package com.mcprovince.core;

import java.util.Locale;

/**
 * 模组界面支持的语言。
 *
 * <p>游戏自带的语言代码比这里多得多（英式英语、墨西哥西语、香港繁体……），
 * 所以进界面前先把游戏语言归到这一条线上：{@link #resolve(String)} 认不出的
 * 语言一律落到英语，和界面文案的回退链保持一致。</p>
 *
 * <p>文言文（{@code lzh}）归到繁体中文——典籍本来就是繁体，用繁体的用词念着更顺，
 * 单独再翻一份没有意义。</p>
 */
public enum Language {

    ZH_CN("zh_cn"),
    ZH_TW("zh_tw"),
    EN_US("en_us"),
    FR_FR("fr_fr"),
    RU_RU("ru_ru"),
    ES_ES("es_es"),
    JA_JP("ja_jp"),
    KO_KR("ko_kr");

    /** 语言文件名，同时也是设置里存的代号。 */
    private final String code;

    Language(String code) {
        this.code = code;
    }

    public String code() {
        return this.code;
    }

    /**
     * 本体自带文案的语言。
     *
     * <p>简体中文与英文打进模组本体，其余语言要装「Province Translation」才有；
     * 设置界面靠这个判断某一种语言能不能选。</p>
     */
    public boolean builtin() {
        return this == ZH_CN || this == EN_US;
    }

    /** 语言名在语言文件里的键，让每种语言用自己的写法称呼自己。 */
    public String nameKey() {
        return "province.language." + this.code;
    }

    /**
     * 命名界面那个切换按钮上的两三个字：中文用自己的「中 / 繁」，
     * 其他语言取代号前两位的大写（{@code EN}、{@code FR}、{@code JA}）。
     */
    public String shortLabel() {
        return switch (this) {
            case ZH_CN -> "中";
            case ZH_TW -> "繁";
            default -> this.code.substring(0, 2).toUpperCase(Locale.ROOT);
        };
    }

    /** 设置里「跟随游戏语言」用的代号。 */
    public static final String AUTO = "auto";

    public Language shift(int delta) {
        Language[] values = values();
        return values[(this.ordinal() + delta + values.length) % values.length];
    }

    /** 设置里存的代号转成语言；{@code auto} 或认不出的返回 {@code null}。 */
    public static Language byCode(String code) {
        if (code == null || code.isEmpty() || AUTO.equals(code)) {
            return null;
        }
        for (Language language : values()) {
            if (language.code.equals(code)) {
                return language;
            }
        }
        return null;
    }

    /**
     * 把游戏语言代码归到支持的语言线上。
     *
     * <p>先认几组容易认错的变体，再按语言主码兜底：{@code en_gb}、{@code en_au}
     * 都是英语，{@code es_mx}、{@code es_ar} 都是西语，{@code zh_hk} 按繁体算。</p>
     */
    public static Language resolve(String gameCode) {
        if (gameCode == null || gameCode.isEmpty()) {
            return EN_US;
        }
        String code = gameCode.toLowerCase(Locale.ROOT).replace('-', '_');
        if (code.equals("lzh") || code.startsWith("lzh_")) {
            return ZH_TW;
        }
        if (code.startsWith("zh_tw") || code.startsWith("zh_hk")
                || code.startsWith("zh_mo") || code.startsWith("zh_hant")) {
            return ZH_TW;
        }
        if (code.startsWith("zh")) {
            return ZH_CN;
        }
        int cut = code.indexOf('_');
        String base = cut < 0 ? code : code.substring(0, cut);
        return switch (base) {
            case "en" -> EN_US;
            case "fr" -> FR_FR;
            case "ru" -> RU_RU;
            case "es" -> ES_ES;
            case "ja" -> JA_JP;
            case "ko" -> KO_KR;
            default -> EN_US;
        };
    }
}
