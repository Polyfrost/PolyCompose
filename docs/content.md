# Minecraft content and images

## Items, faces and sprites

```kotlin
McItemIcon(ItemStack(Items.DIAMOND_SWORD), size = 32.dp)
McPlayerFace(player.uuid, size = 24.dp)
McGuiSprite("hud/heart/full", size = 9.dp)
McSprite("textures/block/stone.png", size = 32.dp)
```

- `McItemIcon` draws the item the way the inventory does, durability bar and count included. Pass `decorations = false` to leave the bar and count out, or `countOverride` to show your own text. If you animate the icon's scale (a pickup bounce or a hover zoom), pass the largest scale it reaches as `maxScale`. The icon is then rendered at that resolution from the start, so it stays sharp through the animation without being rendered again
- `McPlayerFace` shows a player's skin face, with the hat layer unless you pass `hat = false`. Pass a `McHeadStyle` for the other head styles: `LAYERED` draws the hat at 9/8 the size of the face like the player model, reaching `size / 16` past the face on every side. `McPlayerFaces.drawHead` draws the same styles from a canvas
- `McGuiSprite` draws a GUI sprite and follows the sprite's own nine-slice or tiling rules, so panels and bars stretch the way vanilla stretches them
- `McSprite` takes a texture path, GUI sprite or block atlas sprite (`kind`), and can draw part of it with `region`

Each of these has a `Node` version (`NodeMcItemIcon` and so on) for code built on PolyCompose's node API.

## Entity and player previews

```kotlin
PlayerPreview(player.uuid, Modifier.size(80.dp, 160.dp), yaw = 30f)
EntityPreview(entity, Modifier.size(80.dp, 160.dp), showNameTag = true)
```

The preview renders the live entity. `headYaw` and `headPitch` turn the head, and `followBodyYaw` makes the head track the body. Pass `live = false` for a still picture that only redraws when its inputs change. `fallback` shows while the entity isn't available, like before a world is loaded.

## Minecraft text

Use `MinecraftFontFamily` with `minecraftFontSize()` to get the game font at the right size, and `MinecraftTextShadow` for the vanilla drop shadow:

```kotlin
Text(
    "Hello",
    fontFamily = MinecraftFontFamily,
    fontSize = minecraftFontSize(),
    style = TextStyle(shadow = MinecraftTextShadow),
)
```

## Images

`rememberImage` loads an image in the background and gives you its state:

```kotlin
when (val image = rememberImage(ImageSource.Url("https://example.com/banner.png"))) {
    is ImageState.Loaded -> Image(image.bitmap.asComposeImageBitmap(), null)
    ImageState.Loading -> CircularProgressIndicator()
    is ImageState.Failed -> Text("Couldn't load the banner")
}
```

`ImageSource` can be a `Url`, a `File`, a `Resource` from your jar, or raw `Bytes`. Loaded images are cached, and a failed URL retries later with a growing delay.

If your mod gets images from somewhere special (a CDN with auth, or a custom scheme), register an `ImageFetcher` with `ImageLoader.registerFetcher`. Return `null` from `fetch` for URLs you don't handle.

Pass your mod's class loader as the owner when you add images to the cache yourself (`ImageLoader.put(owner, key, bitmap)`), and call `ImageLoader.clear(owner)` to drop only your own entries. `ImageLoader.clear()` with no owner clears everyone's cache, so avoid it.

`BlurredImage(bitmap, blurRadius = 8.dp)` draws a blurred copy of an image, which works well behind cards and banners.

## Chroma and emoji

`Modifier.chromaBackground()` and `Modifier.chromaBorder(2.dp)` give you the cycling rainbow look. `chromaBrush()` works anywhere Compose takes a `Brush`, text included.

`CustomEmoji.register(source)` adds your own emoji to all PolyCompose text. `EmojiSource.atlas(bitmap, cell) { codepoint -> index }` cuts them out of a sprite sheet. Close the returned `AutoCloseable` to remove them again.
