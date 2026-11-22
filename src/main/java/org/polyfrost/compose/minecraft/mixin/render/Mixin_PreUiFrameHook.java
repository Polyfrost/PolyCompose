package org.polyfrost.compose.minecraft.mixin.render;

//? if > 1.8.9 {
import net.minecraft.client.renderer.GameRenderer;
//?} else
//import net.minecraft.client.render.GameRenderer;
import org.polyfrost.compose.minecraft.preview.McFrameHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class Mixin_PreUiFrameHook {

    @Inject(method = "render", at = @At("HEAD"))
    private void polycompose$beforeUi(CallbackInfo ci) {
        McFrameHooks.runBeforeUi();
    }

    //? if >= 26.1 {
    @Inject(method = "extract", at = @At("HEAD"))
    private void polycompose$beforeExtract(CallbackInfo ci) {
        McFrameHooks.runBeforeExtract();
    }
    //?}

}
