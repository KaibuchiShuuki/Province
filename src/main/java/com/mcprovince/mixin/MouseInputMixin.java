package com.mcprovince.mixin;

import com.mcprovince.client.ProvinceMapHook;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 鼠标按下 / 松开挂到原版的 {@code MouseHandler}（理由同 {@link KeyboardInputMixin}）。 */
@Mixin(MouseHandler.class)
public abstract class MouseInputMixin {

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void mcprovince$onPress(long window, int button, int action, int modifiers, CallbackInfo ci) {
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) {
            return;
        }
        if (ProvinceMapHook.handleRawMouse(button, action == GLFW.GLFW_PRESS)) {
            ci.cancel();
        }
    }
}
