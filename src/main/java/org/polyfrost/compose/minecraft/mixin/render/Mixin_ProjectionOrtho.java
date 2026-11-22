package org.polyfrost.compose.minecraft.mixin.render;

//? if >= 26.1 {
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalFloatRef;
import net.minecraft.client.renderer.Projection;
import org.polyfrost.compose.minecraft.render.GuiTargetRedirect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Projection.class)
public class Mixin_ProjectionOrtho {
    @ModifyVariable(method = "setupOrtho(FFFFZ)V", at = @At("HEAD"), argsOnly = true, ordinal = 2, order = 900)
    private float polycompose$captureOrthoWidth(float width, @Share("orthoWidth") LocalFloatRef shared) {
        shared.set(width);
        return width;
    }

    @ModifyVariable(method = "setupOrtho(FFFFZ)V", at = @At("HEAD"), argsOnly = true, ordinal = 2, order = 1100)
    private float polycompose$restoreOrthoWidth(float width, @Share("orthoWidth") LocalFloatRef shared) {
        return GuiTargetRedirect.target != null ? shared.get() : width;
    }

    @ModifyVariable(method = "setupOrtho(FFFFZ)V", at = @At("HEAD"), argsOnly = true, ordinal = 3, order = 900)
    private float polycompose$captureOrthoHeight(float height, @Share("orthoHeight") LocalFloatRef shared) {
        shared.set(height);
        return height;
    }

    @ModifyVariable(method = "setupOrtho(FFFFZ)V", at = @At("HEAD"), argsOnly = true, ordinal = 3, order = 1100)
    private float polycompose$restoreOrthoHeight(float height, @Share("orthoHeight") LocalFloatRef shared) {
        return GuiTargetRedirect.target != null ? shared.get() : height;
    }
}
//?}
