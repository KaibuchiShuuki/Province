package com.mcprovince.client;

import com.mcprovince.ProvinceSelfTest;
import com.mcprovince.data.ProvinceData;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.Objects;

/** 客户端总入口：数据、设置、缓存都在这里。 */
public final class ProvinceClient {

    private static final ProvinceData DATA = new ProvinceData();
    private static final int SAVE_INTERVAL_TICKS = 100;

    private static int saveCountdown;

    private ProvinceClient() {
    }

    public static void init() {
        ProvinceSettings.get().load();
        ProvinceKeys.register();
        ClientTickEvents.END_CLIENT_TICK.register(ProvinceClient::onEndTick);
    }

    public static ProvinceData data() {
        return DATA;
    }

    private static void onEndTick(Minecraft minecraft) {
        String before = DATA.worldKey();
        DATA.checkWorld(minecraft);
        if (!Objects.equals(before, DATA.worldKey())) {
            ProvinceEditor.onWorldChanged();
        }
        if (++saveCountdown >= SAVE_INTERVAL_TICKS) {
            saveCountdown = 0;
            DATA.save();
        }
        ProvinceTitleTracker.tick(minecraft);
        // 自检里延后的那几条（要等资源包加载完）靠这里跑；平时是空转。
        ProvinceSelfTest.onClientTick(minecraft);
        // 自动进世界验证小地图图层；正式游玩时空转。
        ProvinceSelfTest.tickAutoWorld(minecraft);
    }
}
