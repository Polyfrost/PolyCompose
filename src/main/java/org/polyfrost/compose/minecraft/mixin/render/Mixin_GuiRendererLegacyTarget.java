package org.polyfrost.compose.minecraft.mixin.render;

//? if >= 1.21.8 {
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.render.GuiRenderer;
import org.polyfrost.compose.minecraft.render.GuiTargetRedirect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

//? if >= 26.2 {
import net.minecraft.client.renderer.GameRenderer;
//?}

//? if >= 26.1 {
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalFloatRef;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.ModifyArg;
//?}

//? if < 26.2 {
/*import net.minecraft.client.Minecraft;
*///?}

@Mixin(GuiRenderer.class)
public class Mixin_GuiRendererLegacyTarget {
    @ModifyExpressionValue(
            method = "draw",
            //? if >= 26.1 {
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/WindowRenderState;guiScale:I", opcode = Opcodes.GETFIELD)
            //?} else
            //at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getGuiScale()I")
    )
    private int polycompose$itemAtlasProjectionScale(int original) {
        return GuiTargetRedirect.itemRenderSizePx > 0 ? 1 : original;
    }

    //? if >= 26.1 {
    @ModifyExpressionValue(
            method = "draw",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/state/WindowRenderState;width:I",
                    opcode = Opcodes.GETFIELD
            )
    )
    private int polycompose$modifyGuiTargetWidth(int original) {
        RenderTarget override = GuiTargetRedirect.target;
        return override != null ? override.width : original;
    }

    @ModifyExpressionValue(
            method = "draw",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/state/WindowRenderState;height:I",
                    opcode = Opcodes.GETFIELD
            )
    )
    private int polycompose$modifyGuiTargetHeight(int original) {
        RenderTarget override = GuiTargetRedirect.target;
        return override != null ? override.height : original;
    }

    @ModifyArg(
            method = "draw",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Projection;setupOrtho(FFFFZ)V"),
            index = 2,
            order = 900
    )
    private float polycompose$captureProjectionWidth(float original, @Share("projectionWidth") LocalFloatRef width) {
        width.set(original);
        return original;
    }

    @ModifyArg(
            method = "draw",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Projection;setupOrtho(FFFFZ)V"),
            index = 2,
            order = 1100
    )
    private float polycompose$restoreProjectionWidth(float original, @Share("projectionWidth") LocalFloatRef width) {
        return GuiTargetRedirect.target != null ? width.get() : original;
    }

    @ModifyArg(
            method = "draw",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Projection;setupOrtho(FFFFZ)V"),
            index = 3,
            order = 900
    )
    private float polycompose$captureProjectionHeight(float original, @Share("projectionHeight") LocalFloatRef height) {
        height.set(original);
        return original;
    }

    @ModifyArg(
            method = "draw",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Projection;setupOrtho(FFFFZ)V"),
            index = 3,
            order = 1100
    )
    private float polycompose$restoreProjectionHeight(float original, @Share("projectionHeight") LocalFloatRef height) {
        return GuiTargetRedirect.target != null ? height.get() : original;
    }
    //? } else {
    /*@ModifyExpressionValue(
            method = "draw",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getWidth()I")
    )
    private int polycompose$modifyGuiTargetWidth(int original) {
        RenderTarget override = GuiTargetRedirect.target;
        return override != null ? override.width : original;
    }

    @ModifyExpressionValue(
            method = "draw",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getHeight()I")
    )
    private int polycompose$modifyGuiTargetHeight(int original) {
        RenderTarget override = GuiTargetRedirect.target;
        return override != null ? override.height : original;
    }
    *///? }

    //? if >= 26.2 {
    @WrapOperation(
            method = "draw",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;")
    )
    private RenderTarget polycompose$redirectGuiTarget(GameRenderer instance, Operation<RenderTarget> original) {
        RenderTarget override = GuiTargetRedirect.target;
        return override != null ? override : original.call(instance);
    }
    //? } else {
    /*@WrapOperation(
            method = "draw",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getMainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;")
    )
    private RenderTarget polycompose$redirectGuiTarget(Minecraft instance, Operation<RenderTarget> original) {
        RenderTarget override = GuiTargetRedirect.target;
        return override != null ? override : original.call(instance);
    }
    *///? }
}
//? }
