package org.polyfrost.compose.minecraft.mixin.render;

//? if = 1.8.9 {
/*import net.minecraft.client.render.texture.HttpTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HttpTexture.class)
public interface HttpTextureAccessor {
    @Accessor("uploaded")
    boolean polycompose$isUploaded();

    @Accessor("downloader")
    Thread polycompose$getDownloader();
}
*///?}
