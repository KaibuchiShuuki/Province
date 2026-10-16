package com.mcprovince.data;

import com.mcprovince.core.BlockSnap;
import com.mcprovince.core.Province;
import com.mcprovince.core.ProvinceStore;
import com.mcprovince.core.VectorCodec;
import com.mcprovince.core.VectorShape;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** 把一份 {@link ProvinceStore} 写成 NBT，或者从 NBT 读回来。 */
public final class ProvinceNbt {

    /**
     * 数据格式版本。
     *
     * <p>1 → 2：边界从「连续坐标」改成「吸附在方块格线上」。版本 1 的老数据读进来会
     * 自动方块化，{@link ProvinceData} 随后立刻回写一次。</p>
     *
     * <p>版本 2 之后新增的都是可选字段（{@code order}），缺了就按老规矩推一个默认值，
     * 所以不用再抬版本号。</p>
     */
    public static final int DATA_VERSION = 2;

    private static final String TAG_VERSION = "version";
    private static final String TAG_NEXT_ID = "nextId";
    private static final String TAG_PROVINCES = "provinces";

    private static final String TAG_ID = "id";
    private static final String TAG_PARENT = "parent";
    private static final String TAG_TIER = "tier";
    private static final String TAG_NAME_ZH = "nameZh";
    private static final String TAG_NAME_EN = "nameEn";
    private static final String TAG_LEVEL = "level";
    private static final String TAG_COLOR = "color";
    private static final String TAG_VISIBLE = "visible";
    private static final String TAG_ORDER = "order";
    private static final String TAG_SHAPE = "shape";

    private ProvinceNbt() {
    }

    public static CompoundTag write(ProvinceStore store) {
        CompoundTag root = new CompoundTag();
        root.putInt(TAG_VERSION, DATA_VERSION);
        root.putInt(TAG_NEXT_ID, store.nextId());
        ListTag list = new ListTag();
        for (Province province : store.allSorted()) {
            list.add(writeProvince(province));
        }
        root.put(TAG_PROVINCES, list);
        return root;
    }

    private static CompoundTag writeProvince(Province province) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_ID, province.id());
        tag.putInt(TAG_PARENT, province.parentId());
        tag.putInt(TAG_TIER, province.tier());
        tag.putString(TAG_NAME_ZH, province.nameZh());
        tag.putString(TAG_NAME_EN, province.nameEn());
        tag.putString(TAG_LEVEL, province.levelKey());
        tag.putInt(TAG_COLOR, province.color());
        tag.putBoolean(TAG_VISIBLE, province.visible());
        tag.putInt(TAG_ORDER, province.order());
        tag.putByteArray(TAG_SHAPE, VectorCodec.encode(province.shape()));
        return tag;
    }

    /**
     * 从 NBT 读回全部区划。
     *
     * @return 这次是否做过老数据迁移；是的话调用方应当立刻回写一遍，
     *         免得每次进存档都要把全部边界重算一次
     */
    public static boolean read(CompoundTag root, ProvinceStore store) {
        boolean legacy = root.getIntOr(TAG_VERSION, 1) < DATA_VERSION;
        ListTag list = root.getListOrEmpty(TAG_PROVINCES);
        int maxId = 0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompoundOrEmpty(i);
            if (tag.isEmpty()) {
                continue;
            }
            int id = tag.getIntOr(TAG_ID, -1);
            if (id < 0) {
                continue;
            }
            byte[] encoded = tag.getByteArray(TAG_SHAPE).orElse(null);
            VectorShape shape = encoded == null ? VectorShape.empty() : VectorCodec.decode(encoded);
            if (legacy) {
                // 0.2.0 的边界是一堆连续坐标画出来的曲线，进 0.2.1 之前按方块格线重算一遍。
                shape = BlockSnap.snap(shape);
            }
            Province province = new Province(
                    id,
                    tag.getIntOr(TAG_PARENT, Province.NO_PARENT),
                    tag.getIntOr(TAG_TIER, Province.MIN_TIER),
                    tag.getStringOr(TAG_NAME_ZH, ""),
                    tag.getStringOr(TAG_NAME_EN, ""),
                    tag.getStringOr(TAG_LEVEL, "custom"),
                    tag.getIntOr(TAG_COLOR, 0xFFFFFF));
            province.setVisible(tag.getBooleanOr(TAG_VISIBLE, true));
            // 老档案没有这个字段：用 id 顶上，排出来跟以前一模一样。
            province.setOrder(tag.getIntOr(TAG_ORDER, id));
            province.shape().copyFrom(shape);
            store.putRaw(province);
            maxId = Math.max(maxId, id);
        }
        int storedNext = root.getIntOr(TAG_NEXT_ID, maxId + 1);
        store.bumpNextId(Math.max(storedNext, maxId + 1));
        // putRaw 只填数据、不建索引，载入完必须重建一次，
        // 否则 findAt / findFinestAt / isOwnedByOther 这些查询会全部落空。
        store.rebuildIndexes();
        return legacy;
    }
}
