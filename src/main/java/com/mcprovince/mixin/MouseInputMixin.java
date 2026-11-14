package com.mcprovince.mixin;

import com.mcprovince.client.ProvinceMapHook;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 鼠标按下 / 松开挂到原版的 {@code MouseHandler}（理由见 {@link KeyboardInputMixin}）。 */
@Mixin(MouseHandler.class)
public abstract class MouseInputMixin {

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void mcprovince$onButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (action != InputConstants.PRESS && action != InputConstants.RELEASE) {
            return;
        }
        if (ProvinceMapHook.handleRawMouse(info.button(), action == InputConstants.PRESS)) {
            ci.cancel();
        }
    }
}
