package com.mcprovince.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 模组自己的文案表。
 *
 * <p>不能直接用原版的 {@code I18n}：原版只认「游戏语言」，而设置里可以单独把模组界面
 * 切成别的语言。所以这些文案从模组 jar 的 {@code assets/mc-province/lang/*.json} 里
 * 自己读一份，查表时按「当前语言 → 英文 → 键名」回退。</p>
 *
 * <p>本体自带简体中文与英文；其余语言由可选的「Province Translation」提供——它也是从
 * {@code assets/mc-province/lang/} 下放文件，所以 {@link ClassLoader} 能直接读到，
 * 换语言只要重新 {@link #setLanguage(Language)}，不需要重启游戏。</p>
 */
public final class Lang {

    private static final Logger LOGGER = LoggerFactory.getLogger("mc-province");
    private static final String PATH = "/assets/mc-province/lang/";
    /** 英文是默认语言：没有翻译模组时界面就落在这里。 */
    private static final Language FALLBACK = Language.EN_US;
    /** 翻译模组的 mod id，用来判断要不要给出「跟随系统」那一项。 */
    private static final String TRANSLATION_MOD_ID = "province-translation";

    private static Language current = FALLBACK;
    private static Map<String, String> table = Map.of();
    private static Map<String, String> fallback = Map.of();
    private static Boolean translationPack;

    private Lang() {
    }

    public static Language current() {
        return current;
    }

    /** 切换界面语言并重新读表；读不到（没装翻译模组）就退回本体自带的英文。 */
    public static synchronized void setLanguage(Language language) {
        Language target = language == null ? FALLBACK : language;
        if (fallback.isEmpty()) {
            fallback = load(FALLBACK);
        }
        current = target;
        table = target == FALLBACK ? fallback : load(target);
        if (table.isEmpty() && target != FALLBACK) {
            LOGGER.warn("语言文件 {} 读不到（没装 Province Translation？），界面回退成英文", target.code());
            table = fallback;
        }
    }

    /**
     * 取一条文案。
     *
     * <p>参数用 {@code {0}}、{@code {1}} 占位，不用 {@code String.format}——
     * 文案里有「50%」这种百分号，走格式化会直接炸掉。</p>
     */
    public static String t(String key, Object... args) {
        String text = table.get(key);
        if (text == null) {
            text = fallback.get(key);
        }
        if (text == null) {
            return key;
        }
        if (args.length == 0) {
            return text;
        }
        String out = text;
        for (int i = 0; i < args.length; i++) {
            out = out.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return out;
    }

    /** 这条键在当前语言里有没有译文。自检和缺键排查用。 */
    public static boolean has(String key) {
        return table.containsKey(key) || fallback.containsKey(key);
    }

    /** 这套语言有没有现成的文案文件：本体带简体中文与英文，其余看装没装翻译模组。 */
    public static boolean available(Language language) {
        if (language == null) {
            return false;
        }
        return language.builtin()
                || Lang.class.getResource(PATH + language.code() + ".json") != null;
    }

    /**
     * 装没装翻译模组。
     *
     * <p>它决定设置里有没有「跟随系统」这一项：只装本体时语言只有两种，跟随系统没有意义，
     * 干脆不出现。拿 loader 的 mod 列表判断，不依赖任何类，纯资源包也能认出来。</p>
     */
    public static boolean translationPackInstalled() {
        Boolean cached = translationPack;
        if (cached != null) {
            return cached;
        }
        boolean installed;
        try {
            installed = FabricLoader.getInstance().isModLoaded(TRANSLATION_MOD_ID);
        } catch (Throwable ignored) {
            // 没有 loader（自检、单元测试）时按「有翻译」处理：多出几项总比崩掉好。
            installed = true;
        }
        translationPack = installed;
        return installed;
    }

    /** 自检用：翻译模组的探测结果没法在同一个 JVM 里换，允许清掉。 */
    public static void clearTranslationCache() {
        translationPack = null;
    }

    private static Map<String, String> load(Language language) {
        String path = PATH + language.code() + ".json";
        try (InputStream in = Lang.class.getResourceAsStream(path)) {
            if (in == null) {
                return Map.of();
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> map = new HashMap<>(root.size() * 2);
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    map.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            return map;
        } catch (Exception e) {
            LOGGER.error("读取语言文件失败：{}", path, e);
            return Map.of();
        }
    }
}
