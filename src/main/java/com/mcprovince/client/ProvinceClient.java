package com.mcprovince.client;

import com.mcprovince.ProvinceSelfTest;
import com.mcprovince.core.Lang;
import com.mcprovince.core.Language;
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
        // 本体自带简中与英文；翻译模组带的其它语言要等 tick 里读到系统语言才切得过去。
        Language startup = ProvinceSettings.get().effectiveLanguage();
        Lang.setLanguage(Lang.available(startup) ? startup : Language.EN_US);
        ProvinceKeys.register();
        ProvinceMapHook.register();
        ClientTickEvents.END_CLIENT_TICK.register(ProvinceClient::onEndTick);
    }

    public static ProvinceData data() {
        return DATA;
    }

    /**
     * 让文案表跟上界面语言。
     *
     * <p>客户端初始化的那一刻 {@code options.languageCode} 还是默认的 {@code en_us}，
     * 资源包与语言设置要更晚才落地，于是第一局会按英语画界面、重进才正常。
     * 这里每 tick 对一次：手动选的语言直接信，跟随游戏语言则等游戏语言读得出来再跟。</p>
     */
    private static void syncLanguage(Minecraft minecraft) {
        ProvinceSettings settings = ProvinceSettings.get();
        // 「跟随系统」要等游戏把语言设置读出来；那一刻之前先别动当前语言。
        if (Language.byCode(settings.languageCode) == null
                && (minecraft.options == null || minecraft.options.languageCode == null
                        || minecraft.options.languageCode.isEmpty())) {
            return;
        }
        Language picked = settings.effectiveLanguage();
        if (picked != Lang.current() && Lang.available(picked)) {
            Lang.setLanguage(picked);
        }
    }

    private static void onEndTick(Minecraft minecraft) {
        syncLanguage(minecraft);
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
