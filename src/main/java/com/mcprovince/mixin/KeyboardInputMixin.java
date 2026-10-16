package com.mcprovince.mixin;

import com.mcprovince.client.ProvinceMapHook;
import net.minecraft.client.KeyboardHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 键盘事件挂到原版的 {@code KeyboardHandler} 上。
 *
 * <p>为什么不直接混入 {@code xaero.map.gui.GuiMap}：那个类里有个 5019 条指令的 {@code render}，
 * 而 Mixin 的 {@code TreeTransformer} 写回类时会用 ASM 给<b>整个类</b>的每个方法重算栈帧，
 * 于是不管注入点有多小，都会撞上 {@code LimitExceededException: Too many operations}。
 * 原版类没有这种超长方法，而且 Fabric API 自己也是这么注入键盘的。</p>
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardInputMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void mcprovince$keyPress(long window, int key, int scanCode, int action, int modifiers,
            CallbackInfo ci) {
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT) {
            return;
        }
        if (ProvinceMapHook.handleRawKey(key, scanCode, modifiers)) {
            ci.cancel();
        }
    }
}
