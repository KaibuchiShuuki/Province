package com.mcprovince.core;

import java.util.Locale;

/**
 * 面积怎么写给人看。
 *
 * <p>默认按「平方格」显示。设置里打开「启用平方千格」之后，大于 {@link #KILO_THRESHOLD}
 * 平方格的区划改用「平方千格」，保留三位小数、四舍五入——一百万平方格写成
 * {@code 1000000 平方格}，谁也一眼数不清有几个零。</p>
 */
public final class AreaFormat {

    /** 1 平方千格 = 1000 x 1000 平方格。 */
    public static final double PER_KILO = 1_000_000.0;
    /** 严格大于这个面积（平方格）才换算成平方千格。 */
    public static final double KILO_THRESHOLD = PER_KILO;

    private AreaFormat() {
    }

    /**
     * @param area    面积，单位是平方格
     * @param useKilo 设置里那一项开着没有
     * @return 例如 {@code "12 平方格"} 或 {@code "1.234 平方千格"}
     */
    public static String format(double area, boolean useKilo) {
        if (useKilo && area > KILO_THRESHOLD) {
            // 固定 Locale：某些语言下 "%.3f" 的小数点是逗号，界面上会变成 1,234。
            return String.format(Locale.ROOT, "%.3f 平方千格", area / PER_KILO);
        }
        return Math.round(area) + " 平方格";
    }
}
