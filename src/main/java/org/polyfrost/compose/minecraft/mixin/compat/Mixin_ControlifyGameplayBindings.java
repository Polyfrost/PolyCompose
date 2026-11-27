package org.polyfrost.compose.minecraft.mixin.compat;

//? if > 1.8.9 {
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.isxander.controlify.api.bind.InputBinding;
import org.polyfrost.compose.minecraft.compat.controlify.GameplayCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "dev.isxander.controlify.bindings.InputBindingImpl", remap = false)
public class Mixin_ControlifyGameplayBindings {
    @ModifyExpressionValue(
            method = "pushState",
            at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;noneMatch(Ljava/util/function/Predicate;)Z")
    )
    private boolean polycompose$suppressCapturedGameplay(boolean original) {
        return original || GameplayCapture.suppresses((InputBinding) (Object) this);
    }
}
//?}
