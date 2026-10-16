package com.mcprovince.core;

import com.mcprovince.data.NbtCompat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行政区划「级别名」表——命名时用户的第三部分就是从这里挑的。
 *
 * <p>分两半：表里写死的是内置的那些建制名，古今中外常见与不那么常见的都有；
 * 另一半是玩家自己加的，存在 {@code config/mc-province/levels.nbt} 里，
 * 想删就删，内置的那些动不了。</p>
 *
 * <p>显示时按当前格式拼接（例如 重庆 + 市 =「重庆市」，Chongqing + City =
 * 「Chongqing City」）。{@link #NONE_KEY} 是个空后缀，选它就只显示区划名本身。</p>
 */
public final class AdminLevels {

    /**
     * @param key  存档用的稳定标识（不要随意改动）
     * @param zh   中文名，也是级别格子里显示的那一个
     * @param en   英文名
     * @param note 只在界面上跟在名字后面的注释（哪个国家 / 哪一套体系），显示成「县（日本）」；
     *             地图上的地名**不带**它，免得「重庆（中国）」这种写法跑到地图上去
     */
    public record Name(String key, String zh, String en, String note) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("mc-province");

    /** 内置项。玩家动不了这些。 */
    private static final List<Name> BUILTIN = new ArrayList<>();
    private static final Map<String, Name> BUILTIN_BY_KEY = new LinkedHashMap<>();
    /** 玩家自己加的，按添加顺序排在后面。 */
    private static final List<Name> USER = new ArrayList<>();
    private static final Map<String, Name> USER_BY_KEY = new LinkedHashMap<>();

    /** 什么都不加：区划名后面没有后缀。 */
    public static final String NONE_KEY = "none";
    public static final String DEFAULT_KEY = "province";
    /** 玩家自建项的键前缀；靠它认出「哪些能删」。 */
    public static final String USER_PREFIX = "user_";

    /** 中文名和英文名都空着的时候，界面上拿这个当占位。 */
    public static final String NONE_LABEL = "（无后缀）";

    static {
        // ——— 中国现行 ———
        // 第 4 个参数是注释：只在界面上跟在名字后面（「县（日本）」），用来区分不同国家里重名的建制。
        // 地图上的地名是干净的，不会写上「（中国）」。
        add("country", "国", "Country");
        add("province", "省", "Province", "中");
        add("municipality", "直辖市", "Municipality");
        add("autonomous_region", "自治区", "Autonomous Region", "中");
        add("sar", "特别行政区", "Special Administrative Region");
        add("prefecture", "地区", "Prefecture", "中");
        add("prefecture_city", "地级市", "Prefecture-level City");
        add("city", "市", "City", "中");
        add("county_level_city", "县级市", "County-level City");
        add("county", "县", "County", "中");
        add("district", "区", "District", "中");
        add("autonomous_county", "自治县", "Autonomous County");
        add("banner", "旗", "Banner");
        add("league", "盟", "League");
        add("subdistrict", "街道", "Subdistrict");
        add("town", "镇", "Town", "中");
        add("township", "乡", "Township", "中");
        add("community", "社区", "Community");
        add("village", "村", "Village", "中");
        // ——— 中国古代 ———
        add("empire", "帝国", "Empire");
        add("kingdom", "王国", "Kingdom");
        add("metropolis", "都", "Metropolis", "古中国");
        add("commandery", "郡", "Commandery", "古中国");
        add("state_zh", "州", "Zhou", "古中国");
        add("circuit", "道", "Circuit", "古中国");
        add("route", "路", "Route", "古中国");
        add("prefecture_zh", "府", "Fu", "古中国");
        add("army", "军", "Jun", "古中国");
        add("ting", "厅", "Ting", "古中国");
        add("guard", "卫", "Guard", "古中国");
        add("garrison", "所", "Battalion", "古中国");
        add("marquisate", "侯国", "Marquisate");
        add("duchy", "公国", "Duchy");
        add("fief", "藩", "Fief");
        add("tusi", "土司", "Tusi");
        add("tribe", "部落", "Tribe");
        add("bao", "保", "Bao");
        add("jia", "甲", "Jia");
        add("li_zh", "里", "Li", "古中国");
        add("county_zh", "邑", "Yi", "古中国");
        add("shire_zh", "乡", "Xiang", "古中国");
        // ——— 日本 ———（这一组走音译：汉字词直接按读音写，不套中国的意译名）
        add("to", "都", "To", "日");
        add("do", "道", "Do", "日");
        add("fu_jp", "府", "Fu", "日");
        add("ken", "县", "Ken", "日");
        add("gun_jp", "郡", "Gun", "日");
        add("city_jp", "市", "Shi", "日");
        add("ward_jp", "区", "Ku", "日");
        add("machi", "町", "Machi", "日");
        add("mura", "村", "Mura", "日");
        // ——— 朝鲜半岛 ———
        add("province_kr", "道", "Province", "韩");
        add("si", "市", "City", "韩");
        add("gun_kr", "郡", "County", "韩");
        add("eup", "邑", "Town", "韩");
        add("myeon", "面", "Township", "韩");
        add("dong", "洞", "Neighborhood");
        add("ri_kr", "里", "Village", "韩");
        // ——— 欧洲 / 美洲 ———
        add("state_us", "州", "State", "美");
        add("county_us", "县", "County", "美");
        add("parish", "教区", "Parish");
        add("borough", "自治市镇", "Borough");
        add("shire", "郡", "Shire", "英");
        add("hundred", "百户区", "Hundred");
        add("region", "大区", "Region");
        add("department", "省", "Department", "法");
        add("arrondissement", "区", "Arrondissement", "法");
        add("canton", "州", "Canton", "瑞士");
        add("commune", "公社", "Commune");
        add("land", "州", "Land", "德");
        add("federal_state", "联邦州", "Federal State");
        add("voivodeship", "省", "Voivodeship", "波");
        add("powiat", "县", "Powiat", "波");
        add("gmina", "乡", "Gmina", "波");
        add("oblast", "州", "Oblast", "俄");
        add("raion", "区", "Raion", "俄");
        add("okrug", "自治区", "Autonomous Okrug", "俄");
        add("krai", "边疆区", "Krai");
        add("republic", "共和国", "Republic");
        // ——— 其他 / 历史与泛用 ———
        add("caliphate", "哈里发国", "Caliphate");
        add("sultanate", "苏丹国", "Sultanate");
        add("emirate", "酋长国", "Emirate");
        add("khanate", "汗国", "Khanate");
        add("shogunate", "幕府", "Shogunate");
        add("principality", "亲王国", "Principality");
        add("grand_duchy", "大公国", "Grand Duchy");
        add("governorate", "省", "Governorate", "阿拉伯");
        add("territory", "领地", "Territory");
        add("protectorate", "保护国", "Protectorate");
        add("colony", "殖民地", "Colony");
        add("mandate", "委任统治地", "Mandate");
        add("dominion", "自治领", "Dominion");
        add("free_city", "自由市", "Free City");
        add("marches", "边区", "Marches");
        // 空后缀放最后：选它就只显示区划名，不拼任何建制名。
        add(NONE_KEY, "", "");
    }

    private AdminLevels() {
    }

    private static void add(String key, String zh, String en) {
        add(key, zh, en, "");
    }

    /** 带注释的内置项；注释只在界面上跟在名字后面，见 {@link #labelOf(Name)}。 */
    private static void add(String key, String zh, String en, String note) {
        Name name = new Name(key, zh, en, note);
        BUILTIN.add(name);
        BUILTIN_BY_KEY.put(key, name);
    }

    /** 内置项 + 玩家自建项，顺序就是界面里的顺序。 */
    public static List<Name> all() {
        List<Name> out = new ArrayList<>(BUILTIN.size() + USER.size());
        out.addAll(BUILTIN);
        out.addAll(USER);
        return out;
    }

    public static Name byKey(String key) {
        Name name = key == null ? null : BUILTIN_BY_KEY.get(key);
        if (name == null) {
            name = key == null ? null : USER_BY_KEY.get(key);
        }
        // 键找不到（比如自建项被删了）就退回默认，不让显示层拿到 null。
        return name != null ? name : BUILTIN_BY_KEY.get(DEFAULT_KEY);
    }

    public static String zh(String key) {
        return byKey(key).zh();
    }

    public static String en(String key) {
        return byKey(key).en();
    }

    /** 这个键是不是玩家自建的——只有自建的才允许删。 */
    public static boolean isUser(String key) {
        return key != null && USER_BY_KEY.containsKey(key);
    }

    /**
     * 界面上给这一项显示的名字：空后缀那项和两个名字都空的自建项都得有个占位。
     *
     * <p>同名不同体系的内置项会带上注释，例如「县（中国）」和「县（日本）」——
     * 界面上分得清，地图上的地名依旧是干干净净的「XX县」。</p>
     */
    public static String labelOf(Name name) {
        return (name.zh().isEmpty() ? NONE_LABEL : name.zh()) + noteSuffix(name);
    }

    /** 那一小段「（日本）」；没有注释就是空串。 */
    public static String noteSuffix(Name name) {
        return name.note().isEmpty() ? "" : "（" + name.note() + "）";
    }

    /**
     * 加一条玩家自建的级别名。
     *
     * <p>中文名是必填的：那一格就是靠中文名认的，空着没法用。英文名留空就跟着中文走，
     * 免得选了「中文全称」格式却拼出一个半截名字。</p>
     *
     * @return 新加的那一项；中文名为空时返回 {@code null}
     */
    public static Name addUser(String zh, String en) {
        String zhTrim = zh == null ? "" : zh.trim();
        if (zhTrim.isEmpty()) {
            return null;
        }
        String enTrim = en == null ? "" : en.trim();
        if (enTrim.isEmpty()) {
            enTrim = zhTrim;
        }
        Name name = new Name(USER_PREFIX + nextUserSerial(), zhTrim, enTrim, "");
        USER.add(name);
        USER_BY_KEY.put(name.key(), name);
        save();
        return name;
    }

    /**
     * 删掉一条玩家自建的级别名。
     *
     * <p>内置项一律拒绝。已经用了这个后缀的区划不会跟着改——它们的键还在存档里，
     * 只是查不到、显示时退回默认后缀。</p>
     *
     * @return 真的删掉了才返回 {@code true}
     */
    public static boolean removeUser(String key) {
        if (!isUser(key)) {
            return false;
        }
        Name removed = USER_BY_KEY.remove(key);
        if (removed == null) {
            return false;
        }
        USER.remove(removed);
        save();
        return true;
    }

    /** 自建项的序号：接着已有的最大值往下发，删掉中间某条也不会撞键。 */
    private static int nextUserSerial() {
        int max = 0;
        for (Name name : USER) {
            String tail = name.key().substring(USER_PREFIX.length());
            try {
                max = Math.max(max, Integer.parseInt(tail));
            } catch (NumberFormatException ignored) {
                // 手工改过文件留下的怪键，跳过就好。
            }
        }
        return max + 1;
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("mc-province").resolve("levels.nbt");
    }

    /** 读玩家自建项。内置项在代码里，不进这个文件。 */
    public static void load() {
        USER.clear();
        USER_BY_KEY.clear();
        Path path = file();
        if (!Files.exists(path)) {
            return;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.create(8388608L));
            ListTag list = NbtCompat.listOr(tag, "levels");
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = NbtCompat.compoundOr(list, i);
                String key = NbtCompat.stringOr(entry, "key", "");
                String zh = NbtCompat.stringOr(entry, "zh", "");
                String en = NbtCompat.stringOr(entry, "en", "");
                if (key.isEmpty() || zh.isEmpty()) {
                    continue;
                }
                // 就算文件被改坏了，也不许冒充内置键。
                if (BUILTIN_BY_KEY.containsKey(key)) {
                    continue;
                }
                Name name = new Name(key, zh, en, "");
                USER.add(name);
                USER_BY_KEY.put(key, name);
            }
        } catch (IOException e) {
            LOGGER.error("读取自定义级别名失败：{}", path, e);
        }
    }

    public static void save() {
        Path path = file();
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Name name : USER) {
            CompoundTag entry = new CompoundTag();
            entry.putString("key", name.key());
            entry.putString("zh", name.zh());
            entry.putString("en", name.en());
            list.add(entry);
        }
        tag.put("levels", list);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            NbtIo.writeCompressed(tag, path);
        } catch (IOException e) {
            LOGGER.error("保存自定义级别名失败：{}", path, e);
        }
    }
}
