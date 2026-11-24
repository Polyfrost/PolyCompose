# Controllers

If the player has Controlify installed, every `ComposeScreen` works with a controller. This needs 1.21.1 or newer, since Controlify doesn't exist on 1.8.9.

## What you get for free

| Input | What it does |
| --- | --- |
| Left stick, d-pad | Moves focus that way. If there's nothing to move to, the focused element gets an arrow key instead |
| A | Sends Enter to the focused element. Text fields open Controlify's on-screen keyboard |
| B | Sends Esc, so `BackStack` entries close first, then the screen |
| X | Runs the focused element's X action, or sends the Menu key so keyboard context menus open |
| Bumpers, triggers | Run the focused element's action for that button, or move focus to the next or previous element |
| Right stick | Scrolls whatever is under the focused element |

When focus can't move, the focused element gets the arrow key, so menus that move their own highlight with Up and Down work without extra code.

Widgets that need the d-pad for themselves, such as sliders, carousels or a cursor that nudges something, mark it with `Modifier.controllerArrows`. They then get the arrow keys first, and focus only moves when they don't use one:

```kotlin
Modifier.controllerArrows(ControllerAxes.Horizontal).onKeyEvent { ... }.focusable()
```

Clickable things (buttons, `Modifier.clickable`, text fields) can be reached automatically. If something can't be reached, make it focusable or clickable.

## Button actions

`Modifier.controllerAction` runs code when a button is pressed while the element, or something inside it, has focus:

```kotlin
Row(
    Modifier
        .clickable { open(note) }
        .controllerAction(ControllerButton.Y, label = "Pin") { togglePin(note) }
) { ... }
```

The label shows in Controlify's button guide at the bottom of the screen while the action is reachable. When elements nested inside each other both handle the same button, the inner one wins.

Pass `requireFocus = false` for an action that works anywhere on the screen, such as a search shortcut. Focused actions still win over it.

You can use X, Y, the bumpers, the triggers, both stick clicks, Back and Start. A and B always mean Enter and Esc.

## Sticks on custom widgets

`Modifier.controllerPan { delta -> }` hands the right stick to a focused element instead of scrolling the page. Use it for things you'd normally drag, such as turning a 3D preview or panning a zoomed image. `delta` is in the same units as a scroll wheel event.

## Telling a controller apart

- `Controllers.isActive` is `true` while the player is using a controller. It's Compose state, so reading it in a composable updates the UI when the player switches between mouse and controller.
- `KeyEvent.isFromController` is `true` for key events PolyCompose sent on behalf of a controller. Keybind fields use it to stop recording A as Enter.
- `ComposeOptions.onInputKind(owner, kind)` is called with `InputKind.Controller` when the player navigates with a controller, so you can show your own focus styling. Override `drawsControllerFocusRing` to hide the default ring.

## Actions outside screens

`Controllers.register` adds a rebindable action to Controlify's controls screen, which is useful for opening your menu from gameplay:

```kotlin
val openMenu = Controllers.register("mymod:open_menu", "mymod.controller.open_menu", radial = true)
openMenu.onPress { McScreens.display(MyScreen()) }
```

Register actions while the game starts. `radial = true` also offers the action in Controlify's radial menu. Read `isPressed` or `justPressed` yourself when you need more than `onPress`.

For overlays that the player steers with a stick, such as radial wheels:

- `Controllers.rightStick` and `Controllers.leftStick` give the current stick position, from -1 to 1 on each axis.
- `Controllers.captureLook()` stops the right stick from turning the camera until you close the returned `AutoCloseable`.
