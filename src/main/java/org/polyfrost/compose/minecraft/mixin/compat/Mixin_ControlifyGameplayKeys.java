package org.polyfrost.compose.minecraft.mixin.compat;

//? if > 1.8.9 {
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.isxander.controlify.bindings.KeyMappingHandle;
import org.polyfrost.compose.minecraft.compat.controlify.GameplayCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "dev.isxander.controlify.bindings.output.KeyMappingEmulationOutput", remap = false)
public class Mixin_ControlifyGameplayKeys {
    @WrapWithCondition(
            method = "push",
            at = @At(value = "INVOKE", target = "Ldev/isxander/controlify/bindings/KeyMappingHandle;controlify$setPressed(Z)V")
    )
    private boolean polycompose$blockCapturedPress(KeyMappingHandle handle, boolean pressed) {
        return !pressed || !GameplayCapture.blocksGameplay();
    }
}
//?}
