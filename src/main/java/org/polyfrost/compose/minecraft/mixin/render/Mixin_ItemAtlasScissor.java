package org.polyfrost.compose.minecraft.mixin.render;

//? if > 1.8.9 {
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.polyfrost.compose.minecraft.render.GuiTargetRedirect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

//? if >= 1.21.8 {
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

//? if < 1.21.8 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphics;
*///?}

//~ if < 1.21.8 'GuiRenderer' -> 'GuiGraphics'
@Mixin(GuiRenderer.class)
public class Mixin_ItemAtlasScissor {
    //? if >= 1.21.8 {
    @Expression("16 * ?")
    @ModifyExpressionValue(method = "prepareItemElements", at = @At("MIXINEXTRAS:EXPRESSION"))
    private int polycompose$itemAtlasSlotSize(int original) {
        int requested = GuiTargetRedirect.itemRenderSizePx;
        return requested > 0 ? requested : original;
    }
    //?}

    //? if >= 1.21.8 {
    @Inject(method = "enableScissor", at = @At("HEAD"), cancellable = true, order = 900)
    private void polycompose$targetScissorBeforeScalers(ScreenRectangle rectangle, RenderPass pass, CallbackInfo ci) {
        GuiTargetRedirect.ScissorTransform transform = GuiTargetRedirect.scissorTransform;
        if (transform == null) {
            return;
        }
        int[] mapped = polycompose$mapScissor(transform, rectangle);
        pass.enableScissor(mapped[0], mapped[1], mapped[2], mapped[3]);
        ci.cancel();
    }
    //?} else {
    /*@WrapOperation(
        method = "applyScissor",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;enableScissor(IIII)V")
    )
    private void polycompose$targetScissor(int x, int y, int width, int height, Operation<Void> original, ScreenRectangle rectangle) {
        GuiTargetRedirect.ScissorTransform transform = GuiTargetRedirect.scissorTransform;
        if (transform == null) {
            original.call(x, y, width, height);
            return;
        }
        int[] mapped = polycompose$mapScissor(transform, rectangle);
        original.call(mapped[0], mapped[1], mapped[2], mapped[3]);
    }
    *///?}

    @Unique
    private static int[] polycompose$mapScissor(GuiTargetRedirect.ScissorTransform transform, ScreenRectangle rectangle) {
        int[] mapped = transform.map(rectangle.left(), rectangle.top(), rectangle.right(), rectangle.bottom());
        RenderTarget target = GuiTargetRedirect.target;
        if (target == null) {
            return mapped;
        }
        int x0 = polycompose$clamp(mapped[0], 0, target.width - 1);
        int y0 = polycompose$clamp(mapped[1], 0, target.height - 1);
        int x1 = polycompose$clamp(mapped[0] + mapped[2], x0 + 1, target.width);
        int y1 = polycompose$clamp(mapped[1] + mapped[3], y0 + 1, target.height);
        mapped[0] = x0;
        mapped[1] = y0;
        mapped[2] = x1 - x0;
        mapped[3] = y1 - y0;
        return mapped;
    }

    @Unique
    private static int polycompose$clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
//?}
