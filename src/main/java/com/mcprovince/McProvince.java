package com.mcprovince;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主类同时实现 ModInitializer（双端通用入口）与 ClientModInitializer（仅客户端入口）。
 * 具体逻辑都在客户端那半边。
 */
public class McProvince implements ModInitializer, ClientModInitializer {
    public static final String MOD_ID = "mc-province";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // 纯客户端模组，公共入口没有要做的事。
    }

    @Override
    public void onInitializeClient() {
        // 本模组是纯客户端模组（fabric.mod.json 里 environment = client），
        // 所以这里可以直接引用客户端专用类。
        // 玩家自己加的级别名要在任何界面能打开之前读进来。
        com.mcprovince.core.AdminLevels.load();
        com.mcprovince.client.ProvinceClient.init();
        if (ProvinceSelfTest.isEnabled()) {
            ProvinceSelfTest.run(LOGGER);
        }
    }
}
