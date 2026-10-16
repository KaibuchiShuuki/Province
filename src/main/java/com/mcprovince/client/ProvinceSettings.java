package com.mcprovince.client;

import com.mcprovince.core.BorderStyle;
import com.mcprovince.core.NameFormat;
import com.mcprovince.core.Province;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** 全局显示设置，世界地图 / 小地图 / 进入提示共用一份。 */
public final class ProvinceSettings {

    private static final Logger LOGGER = LoggerFactory.getLogger("mc-province");
    private static final ProvinceSettings INSTANCE = new ProvinceSettings();

    // —— 世界地图 ——
    public boolean worldMapEnabled;
    // —— 小地图 ——
    /** 在小地图上也叠一层区划；跟世界地图各管各的。 */
    public boolean minimapEnabled;
    // —— 通用显示 ——
    /** 查看地图时只显示这一级；编辑时另说（编辑会额外把父级半透明铺出来）。 */
    public int shownTier = Province.MIN_TIER;
    /**
     * 树状图里被收起来的那几块（记的是区划 id）。
     *
     * <p>收起来的那块自己照常显示，只是它的下级暂时不铺在树状图里——纯外观上的偏好，
     * 跟地图上显不显示没有半点关系。改一次就存一次盘，下次打开树状图还是收着的。</p>
     */
    public final Set<Integer> collapsed = new HashSet<>();
    public boolean showNames;
    /** 地名余量滑条的两端（GUI 像素）。 */
    public static final int NAME_SLACK_MIN = -12;
    public static final int NAME_SLACK_MAX = 12;
    /**
     * 地名允许比地块宽出多少（GUI 像素，可以填负数）。
     *
     * <p>正数就是「字大、地小也照画」，负数反过来要求地块比字宽出一截才画。设置界面里是一条
     * 从 {@link #NAME_SLACK_MIN} 拖到 {@link #NAME_SLACK_MAX} 的滑条，默认 0——也就是
     * 「装得下就画、装不下就不画」。一个汉字宽 9 个像素，拿它估大小最方便。</p>
     */
    public int nameSlack;
    public NameFormat nameFormat = NameFormat.ZH_FULL;
    public boolean showFilled = true;
    /** 填充色的不透明度，百分比。渲染时再换算成 0~255 的 alpha。 */
    public int fillOpacityPercent = 50;
    /**
     * 填充时是否允许按缩放倍率自动隔行采样。
     *
     * <p>开着的时候，缩得越小、轮廓越复杂，扫描线就隔得越开，代价是极小的比例尺下
     * 颜色块的边缘会略糙；关掉就每个屏幕像素都扫一遍，大面积区划上会非常卡。</p>
     */
    public boolean smartFill;
    /**
     * 描边时是否允许按缩放倍率自动降精度。
     *
     * <p>和 {@link #smartFill} 各管各的：填充的代价随面积涨，边框的代价随轮廓长度涨，
     * 两者想省的东西不是一回事，所以给了两个开关。开着的时候缩得越小、边越多，短边被并得
     * 越狠、采样间距也拉得越稀；关掉就一条边都不并、每个屏幕像素都采，精确刻画所有边界，
     * 代价是轮廓一复杂就卡。</p>
     */
    public boolean smartStroke;
    /** 选区笔 / 选区擦在屏幕上的粗细（像素）。只影响这两个工具。 */
    public int brushPixels = 9;
    /**
     * 序号标记：地名在屏幕上挤不下、被整块藏起来的区划，改在它身上标一个带圈数字，
     * 屏幕角落再列出这些数字分别是谁。默认关。
     */
    public boolean serialMarks;
    /** 序号注释贴哪个角。 */
    public SerialCorner serialCorner = SerialCorner.TOP_RIGHT;
    /**
     * 面积显示是否改用「平方千格」。
     *
     * <p>开着的时候，面积大于 100 万平方格（也就是 1 平方千格）的区划按千格写、保留三位小数；
     * 小块的写法不变。默认关。</p>
     */
    public boolean areaInKilo;
    /** 查看时自动描的外框。 */
    public BorderStyle borderStyle = BorderStyle.LINE;
    public int borderWidth = 1;

    // —— 导出地图 ——
    /** 导出时是否把行政区划叠上去；两种导出共用这一项。 */
    public boolean exportWithProvinces = true;

    // —— 进入行政区划时的标题提示 ——
    public boolean titleEnabled;
    /** 主标题下面再挂一条灰色的父级名；默认关。 */
    public boolean titleSubtitle;
    public int titleTier = Province.MIN_TIER;
    public NameFormat titleFormat = NameFormat.ZH_FULL;
    public int titleFadeIn = 10;
    public int titleStay = 40;
    public int titleFadeOut = 10;

    private ProvinceSettings() {
    }

    public static ProvinceSettings get() {
        return INSTANCE;
    }

    public int borderWidthPixels() {
        return Math.max(1, Math.min(8, this.borderWidth));
    }

    /** 填充不透明度换算成 0~255 的 alpha；渲染和导出共用这一份换算。 */
    public int fillAlphaByte() {
        return Math.round(clamp(this.fillOpacityPercent, 0, 100) * 255.0F / 100.0F);
    }

    public Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("mc-province").resolve("settings.nbt");
    }

    public void load() {
        Path path = file();
        if (!Files.exists(path)) {
            return;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.defaultQuota());
            this.worldMapEnabled = tag.getBooleanOr("worldMapEnabled", this.worldMapEnabled);
            this.minimapEnabled = tag.getBooleanOr("minimapEnabled", this.minimapEnabled);
            // 不再兼容旧的 highestTierShown：那一版默认值是最高级，留着会把新默认值第一级顶掉。
            this.shownTier = clamp(tag.getIntOr("shownTier", this.shownTier),
                    Province.MIN_TIER, Province.MAX_TIER);
            this.collapsed.clear();
            for (String part : tag.getStringOr("collapsed", "").split(",")) {
                if (!part.isEmpty() && part.chars().allMatch(Character::isDigit)) {
                    this.collapsed.add(Integer.parseInt(part));
                }
            }
            this.showNames = tag.getBooleanOr("showNames", this.showNames);
            this.nameSlack = clamp(tag.getIntOr("nameSlack", this.nameSlack), NAME_SLACK_MIN, NAME_SLACK_MAX);
            this.nameFormat = NameFormat.byKey(tag.getStringOr("nameFormat", this.nameFormat.key()));
            this.showFilled = tag.getBooleanOr("showFilled", this.showFilled);
            this.fillOpacityPercent = clamp(tag.getIntOr("fillOpacity", this.fillOpacityPercent), 0, 100);
            this.smartFill = tag.getBooleanOr("smartFill", this.smartFill);
            this.smartStroke = tag.getBooleanOr("smartStroke", this.smartStroke);
            this.brushPixels = clamp(tag.getIntOr("brushPixels", this.brushPixels), 2, 48);
            this.serialMarks = tag.getBooleanOr("serialMarks", this.serialMarks);
            this.serialCorner = SerialCorner.byName(tag.getStringOr("serialCorner", this.serialCorner.name()));
            this.areaInKilo = tag.getBooleanOr("areaInKilo", this.areaInKilo);
            this.borderStyle = BorderStyle.byKey(tag.getStringOr("borderStyle", this.borderStyle.key()));
            this.borderWidth = clamp(tag.getIntOr("borderWidth", this.borderWidth), 1, 8);
            this.exportWithProvinces = tag.getBooleanOr("exportWithProvinces", this.exportWithProvinces);
            this.titleEnabled = tag.getBooleanOr("titleEnabled", this.titleEnabled);
            this.titleSubtitle = tag.getBooleanOr("titleSubtitle", this.titleSubtitle);
            this.titleTier = clamp(tag.getIntOr("titleTier", this.titleTier), Province.MIN_TIER, Province.MAX_TIER);
            this.titleFormat = NameFormat.byKey(tag.getStringOr("titleFormat", this.titleFormat.key()));
            this.titleFadeIn = clamp(tag.getIntOr("titleFadeIn", this.titleFadeIn), 0, 200);
            this.titleStay = clamp(tag.getIntOr("titleStay", this.titleStay), 10, 600);
            this.titleFadeOut = clamp(tag.getIntOr("titleFadeOut", this.titleFadeOut), 0, 200);
        } catch (IOException e) {
            LOGGER.error("读取设置失败：{}", path, e);
        }
    }

    public void save() {
        Path path = file();
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("worldMapEnabled", this.worldMapEnabled);
        tag.putBoolean("minimapEnabled", this.minimapEnabled);
        tag.putInt("shownTier", this.shownTier);
        // 收起来的块记成一串 id；数量不多，一个字符串就够了。
        StringBuilder collapsedIds = new StringBuilder();
        for (int id : this.collapsed) {
            if (collapsedIds.length() > 0) {
                collapsedIds.append(',');
            }
            collapsedIds.append(id);
        }
        tag.putString("collapsed", collapsedIds.toString());
        tag.putBoolean("showNames", this.showNames);
        tag.putInt("nameSlack", this.nameSlack);
        tag.putString("nameFormat", this.nameFormat.key());
        tag.putBoolean("showFilled", this.showFilled);
        tag.putInt("fillOpacity", this.fillOpacityPercent);
        tag.putBoolean("smartFill", this.smartFill);
        tag.putBoolean("smartStroke", this.smartStroke);
        tag.putInt("brushPixels", this.brushPixels);
        tag.putBoolean("serialMarks", this.serialMarks);
        tag.putString("serialCorner", this.serialCorner.name());
        tag.putBoolean("areaInKilo", this.areaInKilo);
        tag.putString("borderStyle", this.borderStyle.key());
        tag.putInt("borderWidth", this.borderWidth);
        tag.putBoolean("exportWithProvinces", this.exportWithProvinces);
        tag.putBoolean("titleEnabled", this.titleEnabled);
        tag.putBoolean("titleSubtitle", this.titleSubtitle);
        tag.putInt("titleTier", this.titleTier);
        tag.putString("titleFormat", this.titleFormat.key());
        tag.putInt("titleFadeIn", this.titleFadeIn);
        tag.putInt("titleStay", this.titleStay);
        tag.putInt("titleFadeOut", this.titleFadeOut);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            NbtIo.writeCompressed(tag, path);
        } catch (IOException e) {
            LOGGER.error("保存设置失败：{}", path, e);
        }
    }

    /** 把数值夹进区间。设置界面的加减按钮也用这个，两边口径保持一致。 */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
