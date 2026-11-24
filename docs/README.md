# PolyCompose for mod developers

PolyCompose runs Compose Multiplatform inside Minecraft. You write ordinary `@Composable` functions and it handles drawing, input, controllers and the differences between game versions. Your screens, menus and HUD elements all come out of the same code on 1.8.9 and on 1.21.1 through 26.3.

- [Screens](screens.md)
- [Controllers](controllers.md)
- [HUD layers](hud.md)
- [Minecraft content and images](content.md)
- [Host hooks](hooks.md)

## Setup

Add PolyCompose to `depends` in your `fabric.mod.json` (or `ornithe.mod.json` on 1.8.9) so players get told when it's missing:

```json
"depends": {
  "polycompose": ">=3.0.0"
}
```

PolyCompose ships the Compose runtime itself, so don't bundle Compose in your own jar. Apply the Kotlin Compose compiler plugin with the same Kotlin version PolyCompose is built with (2.4.21), and keep Compose on `compileOnly`.

The examples in these pages use Mojang names from 26.3. Older versions use different class names in places (`GuiGraphics` instead of `GuiGraphicsExtractor`, and Feather names on 1.8.9), but the PolyCompose calls stay the same unless a page says otherwise.

## Turning it off

Players can launch with `-Dpolycompose.ui.disable` to switch every PolyCompose screen and HUD off. Your mod keeps loading, so make sure it still works without its UI.
