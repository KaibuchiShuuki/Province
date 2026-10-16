package com.mcprovince.data;

import com.mcprovince.core.ProvinceStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 玩家的行政区划数据。存在客户端配置目录里，按「存档 / 服务器」分开存放，
 * 和 Xaero 的路径点一样属于玩家自己的资料。
 */
public final class ProvinceData {

    private static final Logger LOGGER = LoggerFactory.getLogger("mc-province");
    private static final String DIR_NAME = "province";
    private static final String WORLD_DIR = "worlds";
    private static final String EXPORT_DIR = "exports";

    private ProvinceStore store = new ProvinceStore();
    private Path file;
    private String worldKey;
    private boolean dirty;

    public ProvinceStore store() {
        return this.store;
    }

    public Path file() {
        return this.file;
    }

    public String worldKey() {
        return this.worldKey;
    }

    public void markDirty() {
        this.dirty = true;
    }

    /** 每 tick 调用：世界换了就把数据切过去。 */
    public void checkWorld(Minecraft minecraft) {
        String key = worldKey(minecraft);
        if (key.equals(this.worldKey)) {
            return;
        }
        saveNow();
        this.worldKey = key;
        this.file = dataDir(minecraft).resolve(dimensionKey(minecraft) + ".nbt");
        load();
    }

    /**
     * 数据目录。
     *
     * <p>单人存档放在存档文件夹自己的 {@code province/} 里，跟世界一起搬走；
     * 连服务器时没有存档文件夹可写，退回配置目录，按服务器地址分开存。</p>
     */
    public static Path dataDir(Minecraft minecraft) {
        IntegratedServer integrated = minecraft == null ? null : minecraft.getSingleplayerServer();
        if (integrated != null) {
            Path root = integrated.getWorldPath(LevelResource.ROOT);
            if (root != null) {
                return root.resolve(DIR_NAME);
            }
        }
        return FabricLoader.getInstance().getConfigDir()
                .resolve(DIR_NAME)
                .resolve(WORLD_DIR)
                .resolve(minecraft == null ? "default" : baseKey(minecraft));
    }

    /** 地图导出图片的落地目录。 */
    public static Path exportDir(Minecraft minecraft) {
        return dataDir(minecraft).resolve(EXPORT_DIR);
    }

    /** 整份数据的深拷贝快照，撤销 / 重做用。 */
    public CompoundTag snapshot() {
        return ProvinceNbt.write(this.store);
    }

    /** 用快照整体替换当前数据。 */
    public void applySnapshot(CompoundTag snapshot) {
        ProvinceStore fresh = new ProvinceStore();
        ProvinceNbt.read(snapshot.copy(), fresh);
        this.store = fresh;
        this.dirty = true;
    }

    public void load() {
        this.store = new ProvinceStore();
        this.dirty = false;
        if (this.file == null || !Files.exists(this.file)) {
            return;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(this.file, NbtAccounter.defaultQuota());
            boolean legacy = ProvinceNbt.read(tag, this.store);
            LOGGER.info("载入 {} 个行政区划（{}）", this.store.size(), this.worldKey);
            if (legacy) {
                // 老存档的曲线边界已经吸附到方块格线上了，立刻回写，省得每次进存档都重算一遍。
                this.dirty = true;
                saveNow();
                LOGGER.info("已把旧版曲线边界吸附到方块格线上（{}）", this.worldKey);
            }
        } catch (IOException e) {
            LOGGER.error("读取行政区划数据失败：{}", this.file, e);
        }
    }

    public void save() {
        if (this.dirty) {
            saveNow();
        }
    }

    public void saveNow() {
        if (this.file == null) {
            return;
        }
        try {
            Path parent = this.file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            NbtIo.writeCompressed(ProvinceNbt.write(this.store), this.file);
            this.dirty = false;
        } catch (IOException e) {
            LOGGER.error("保存行政区划数据失败：{}", this.file, e);
        }
    }

    /**
     * 当前所处的世界 / 服务器 + 维度，用来决定数据存哪个文件。
     *
     * <p>主世界、下界、末地各存各的：同一串坐标在三个维度里根本不是一回事，
     * 在其中一个维度画的区划不该跑到另外两个里去。</p>
     */
    public static String worldKey(Minecraft minecraft) {
        if (minecraft == null) {
            return "default";
        }
        String base = baseKey(minecraft);
        // 主世界 / 下界 / 末地各存各的：同一套坐标在三个维度里根本不是一回事。
        return base + "__" + dimensionKey(minecraft);
    }

    private static String baseKey(Minecraft minecraft) {
        ServerData server = minecraft.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return "server_" + sanitize(server.ip);
        }
        IntegratedServer integrated = minecraft.getSingleplayerServer();
        if (integrated != null) {
            Path directory = integrated.getServerDirectory();
            if (directory != null && directory.getFileName() != null) {
                return "world_" + sanitize(directory.getFileName().toString());
            }
        }
        return "default";
    }

    /** 当前维度在文件名里的短名。 */
    public static String dimensionKey(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return "unknown";
        }
        return sanitize(level.dimension().identifier().toString());
    }

    private static String sanitize(String raw) {
        StringBuilder builder = new StringBuilder(raw.length());
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean keep = Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_';
            builder.append(keep ? c : '_');
        }
        return builder.toString();
    }
}
