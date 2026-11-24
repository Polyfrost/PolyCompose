# Screens

Extend `ComposeScreen` and put your UI in `compose()`:

```kotlin
class SettingsScreen : ComposeScreen() {
    @Composable
    override fun compose() {
        var name by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Settings")
            TextField(name, { name = it })
            Button(onClick = { onClose() }) { Text("Done") }
        }
    }
}
```

Open it with `McScreens.display(SettingsScreen())`. You can call that from any thread, and it moves itself onto the render thread. `McScreens.display(screen, frames = 1)` waits a frame before opening it.

## Redrawing

By default a screen only redraws when its state changes, which keeps idle menus cheap. Pass `RenderMode.CONTINUOUS` if you draw something that changes every frame without Compose knowing about it:

```kotlin
class LiveScreen : ComposeScreen(RenderMode.CONTINUOUS)
```

Compose animations already ask for frames on their own, so most animated screens don't need it.

## Things you can override

- `decorate(content)` wraps everything in `compose()`. By default it calls your options' `Decorate`, so put a theme shared by all your screens in your `ComposeOptions` and override this only for one screen.
- `blurAlpha()` returns how strong the game's background blur is, from 0 to 1.
- `drawsPanorama()` decides whether the title screen panorama shows behind the screen when there's no world loaded.
- `densityScale()` scales the whole screen up or down on top of the game's GUI scale.
- `retainsScene` keeps the composition alive when the screen closes, so reopening it is instant and keeps its state. Call `disposeRetainedScene()` when you're done with it for good.
- `drawsControllerFocusRing` turns off the ring drawn around the focused element when a controller is in use, for screens that draw their own focus styling.

## Controllers

If the player has Controlify installed, every `ComposeScreen` works with a controller and you don't have to write anything for it. The d-pad acts like the arrow keys, A clicks, B goes back, and text fields open Controlify's on-screen keyboard docked under your screen with a preview of the field. See [Controllers](controllers.md) for button actions and the rest.

## Stopping the screen from closing

`McScreens.preventReplacement` blocks other screens from replacing yours, for example while there are unsaved changes. Return `false` to block:

```kotlin
private val guard = McScreens.preventReplacement(this) { next -> !hasUnsavedChanges }
```

The guard removes itself once your screen is gone. Close the returned `AutoCloseable` to drop it sooner.

## Esc and back

`BackStack.register { closeDropdown() }` makes Esc (or B on a controller) close your popup before it closes the screen. Close the returned `AutoCloseable` when the popup goes away. The newest entry closes first.
