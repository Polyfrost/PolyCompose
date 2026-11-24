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

It also works on a container. Put `controllerArrows` and `onKeyEvent` on a parent of focusable children, and while any of them has focus, the arrow key goes to the focused child first and then up to the parent's handler, like any key event. The nearest parent with `controllerArrows` decides which axes it claims. If nothing uses the key, focus moves as usual.

A focused container can be entered with the stick and d-pad. When the focused element has focusable elements inside it, such as a full-screen viewer with buttons along its edges or a scrolling text pane with links, and nothing outside it lies in the pressed direction, focus moves to the visible element inside it nearest the edge you come in from: Down picks the topmost one, Up the bottommost, Right the leftmost and Left the rightmost. If several are level, the one closest to the container's centre wins. Inside a scrolling list or pane, this happens before scrolling: any visible link gets focus, and the pane only scrolls when there isn't one. The same goes for a focused element that scrolls itself, such as a focusable `verticalScroll` pane: the d-pad focuses a visible element inside it, then scrolls it, and only moves focus out once it can't scroll further that way. Containers that claim that direction with `controllerArrows` aren't entered, because they move their own highlight.

Clickable things (buttons, `Modifier.clickable`, text fields) can be reached automatically. If something can't be reached, make it focusable or clickable.

## Button actions

`Modifier.controllerAction` runs code when a button is pressed while the element, or something inside it, has focus:

```kotlin
Row(
    Modifier
        .controllerAction(ControllerButton.Y, label = "Pin") { togglePin(note) }
        .clickable { open(note) }
) { ... }
```

Order matters. `controllerAction` only sees focus from modifiers that come after it and from children, so put it before `clickable`, `focusable` or whatever makes the element focusable. Written as `Modifier.clickable { }.controllerAction(...)`, the action never runs and its label never shows.

The label shows in Controlify's button guide at the bottom of the screen while the action is reachable. When elements nested inside each other both handle the same button, the inner one wins.

Actions only come from the layer that has focus and any popups above it. While a popup or dialog has focus, actions in the screen behind it don't run and don't show in the guide, even with `requireFocus = false`. A popup that can't take focus, such as a notification card, still shows its `requireFocus = false` actions while the screen behind it has focus.

Pass `requireFocus = false` for an action that works anywhere on the screen, such as a search shortcut. Focused actions still win over it.

You can use X, Y, the bumpers, the triggers, both stick clicks and Start. A and B always mean Enter and Esc. Back works too, but PolyCompose's Back binding has no button by default, because the View button already turns Controlify's virtual cursor on and off in screens. Players can bind it in Controlify's controls screen, and an action on Back only shows in the guide once it has a button.

## Choices

When focus moves into a row of choices, such as tabs or a segmented control, it should land on the selected one rather than whichever is closest. Give the selected item a `FocusRequester` and put `Modifier.focusEntry(requester)` on the row:

```kotlin
val selectedFocus = remember { FocusRequester() }
Row(Modifier.focusEntry(selectedFocus)) {
    options.forEach { option ->
        Chip(option, Modifier.then(if (option == selected) Modifier.focusRequester(selectedFocus) else Modifier))
    }
}
```

## Focus scopes

`Modifier.controllerFocusScope()` keeps controller focus inside an area, such as a sidebar or a dialog body. The stick and d-pad only move between elements inside it, and when nothing is left that way the focused element gets the arrow key. The bumpers and triggers cycle through the elements inside it in order, and through the scope itself if it can take focus.

Controller navigation picks the next element itself instead of asking Compose. Because of that, `focusProperties { left = ...; up = ... }`, `FocusRequester.Cancel` and the direction `onEnter` or `onExit` would get don't apply to stick and d-pad moves; `onEnter` sees `FocusDirection.Enter`. Use `controllerFocusScope`, `focusEntry` or `controllerArrows` to steer it. Outside a scope, the bumpers go through Compose, so `next` and `previous` still work there.

## Panels and popups

PolyCompose remembers where focus was. When a menu, dialog or panel closes, or the focused element disappears, focus goes back to the last element that's still there, usually the button that opened it.

Panels that slide in without being a popup should pull focus in when a controller opens them. Put `Modifier.focusWhenShown(open)` on the panel's content.

Widgets that already show focus their own way, like a menu that highlights its current row, can turn off the focus ring with `Modifier.hidesFocusRing()`.

## Button guide

`ComposeScreen.controllerGuide` decides where Controlify's button guide sits: any corner, `BottomCenter`, or `Hidden`. Override it when the default bottom-left corner covers your UI.

## Sticks on custom widgets

`Modifier.controllerPan { delta -> }` hands the right stick to a focused element instead of scrolling the page. Use it for things you'd normally drag, such as turning a 3D preview or panning a zoomed image. `delta` is in the same units as a scroll wheel event. Like `controllerAction`, put it before the modifier that makes the element focusable.

The stick goes straight to the focused element's `controllerPan`, or to the nearest one on a parent of the focused element, so a panel or popup drawn over it doesn't get in the way. When nothing claims it, or `enabled` is `false`, the right stick scrolls whatever is under the focused element as usual.

## Telling a controller apart

- `Controllers.isActive` is `true` while the player is using a controller. It's Compose state, so reading it in a composable updates the UI when the player switches between mouse and controller.
- In Controlify's mixed input mode, where the controller and the mouse and keyboard both work at once, `isActive` follows whichever the player used last. A key press, mouse click, scroll or cursor movement sets it to `false`. Pressing a controller button, moving a stick or trigger away from rest, or navigating with the controller sets it back to `true`. Holding a button or stick that was already down doesn't, so a key press while the player still holds a stick keeps it `false`. When Controlify's virtual mouse is on, it moves and clicks the cursor itself, so for about half a second after the last controller input, cursor movement, clicks and scrolls don't count. With the virtual mouse off, real cursor movement switches back right away, even while a controller button or stick is held.
- `KeyEvent.isFromController` is `true` for key events PolyCompose sent on behalf of a controller, including keys typed on Controlify's on-screen keyboard. Keybind fields use it to stop recording A as Enter.
- `ComposeOptions.onInputKind(owner, kind)` is called with `InputKind.Controller` when the player navigates with a controller, so you can show your own focus styling. Override `ComposeOptions.focusRing` to restyle the focus ring, or `drawsControllerFocusRing` to hide it.

## Actions outside screens

`Controllers.register` adds a rebindable action to Controlify's controls screen, which is useful for opening your menu from gameplay:

```kotlin
val openMenu = Controllers.register("mymod:open_menu", "mymod.controller.open_menu", radial = true)
openMenu.onPress { McScreens.display(MyScreen()) }
```

Register actions while the game starts. `radial = true` also offers the action in Controlify's radial menu. Read `isPressed` or `justPressed` yourself when you need more than `onPress`.

`context` decides when the action can fire:

| Context | Fires |
| --- | --- |
| `ControllerContext.Gameplay` (default) | Only in game with no screen open, so the button keeps its normal job in menus |
| `ControllerContext.Screens` | Only while a screen is open |
| `ControllerContext.Anywhere` | Always, in game and in screens |

```kotlin
val pushToTalk = Controllers.register("mymod:push_to_talk", "mymod.controller.push_to_talk", ControllerButton.LEFT_STICK)
val toggleOverlay = Controllers.register("mymod:toggle_overlay", "mymod.controller.toggle_overlay", context = ControllerContext.Anywhere)
```

Give `radial` actions the `Gameplay` or `Anywhere` context. Controlify's radial menu closes before the picked action runs, so a `Screens` action picked from it never fires.

For overlays that the player steers with a stick, such as radial wheels:

- `Controllers.rightStick` and `Controllers.leftStick` give the current stick position, from -1 to 1 on each axis.
- `Controllers.captureLook()` stops the right stick from turning the camera until you close the returned `AutoCloseable`.
- `Controllers.captureGameplay()` goes further and stops the controller from playing the game: no moving, looking, jumping, sneaking, attacking, using items, hotbar or inventory, until you close the returned `AutoCloseable`. Your own `Controllers.register` actions, including `Gameplay` ones, `leftStick` and `rightStick` keep working, so the overlay can still be steered and closed. Start still opens the pause menu, and nothing is held back while a screen is open. If attack or use was already held when the capture started, it keeps going until the player lets go. Toggled sneak is switched off and stays off after the capture ends.

Close the handle when the overlay closes, including when it closes because a screen opened. Closing it twice is fine. Captures stack, so gameplay comes back once every open handle is closed.

```kotlin
var capture: AutoCloseable? = null

fun openWheel() {
    capture = Controllers.captureGameplay()
}

fun closeWheel() {
    capture?.close()
    capture = null
}
```
