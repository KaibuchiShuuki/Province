package com.mcprovince.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 1.x 线的取值兜底。
 *
 * <p>26.2 的 {@code CompoundTag} 有一整套 {@code getIntOr / getStringOr / getBooleanOr /
 * getListOrEmpty / getCompoundOrEmpty}，1.21.1 上这些都不存在，只有
 * {@code contains(key, type)} + 裸取值。这里把同样的语义补齐，调用点的写法保持不变。</p>
 */
public final class NbtCompat {

    private NbtCompat() {
    }

    public static int intOr(CompoundTag tag, String key, int fallback) {
        return tag.contains(key, Tag.TAG_INT) ? tag.getInt(key) : fallback;
    }

    public static String stringOr(CompoundTag tag, String key, String fallback) {
        return tag.contains(key, Tag.TAG_STRING) ? tag.getString(key) : fallback;
    }

    public static boolean boolOr(CompoundTag tag, String key, boolean fallback) {
        // putBoolean 落盘的类型是 TAG_BYTE。
        return tag.contains(key, Tag.TAG_BYTE) ? tag.getBoolean(key) : fallback;
    }

    public static ListTag listOr(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_LIST) ? tag.getList(key, Tag.TAG_COMPOUND) : new ListTag();
    }

    public static CompoundTag compoundOr(ListTag list, int index) {
        return index >= 0 && index < list.size() ? list.getCompound(index) : new CompoundTag();
    }

    public static byte[] byteArrayOr(CompoundTag tag, String key, byte[] fallback) {
        return tag.contains(key, Tag.TAG_BYTE_ARRAY) ? tag.getByteArray(key) : fallback;
    }
}
