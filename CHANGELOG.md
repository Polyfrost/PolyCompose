# Changelog

## [Unreleased]

- The d-pad and left stick move focus to the closest element in that direction, keep to the current row or column, and scroll lists to reach elements that are off screen. From a focused pane they move into its links and buttons. Focus stays inside an open popup or panel, and controller actions on the screen behind an open dialog don't fire
- When the d-pad or left stick can't move focus any further, the focused element gets an arrow key instead, so menus that move their own highlight work with a controller
- Added `Modifier.focusEntry`, which makes focus land on the selected item when it moves into a row of choices such as tabs
- Focus comes back to where you were after a menu, dialog or panel closes, or when the focused element disappears, instead of starting over from the top
- Added `ComposeOptions.focusRing` for styling the focus ring, `Modifier.hidesFocusRing` for widgets with their own focus look, `Modifier.focusWhenShown` for panels that should take focus when a controller opens them, `Modifier.controllerFocusScope` for keeping focus inside a panel, and `ComposeScreen.controllerGuide` for moving or hiding the button guide
- Added `Modifier.controllerArrows` for widgets such as sliders that want the d-pad's arrow keys before focus moves
- A sends Enter to the focused element before the screen's own key handling, so screens that react to Enter no longer take presses away from the focused button
- X opens the focused element's context menu when it supports the Menu key
- Added `Modifier.controllerPan`, which hands the right stick to a focused element instead of scrolling, for things like 3D previews and zoomed images
- Added `Modifier.controllerAction` for running code when X, Y, a bumper, a trigger, a stick click, Back or Start is pressed on a focused element. Its label shows in Controlify's button guide. Back has no default button, because View toggles Controlify's cursor
- Added `Controllers` to `polycompose-core`, so mods that only depend on the core can register controller actions, check `Controllers.isActive`, read the sticks and stop the right stick from turning the camera while an overlay is open
- `Controllers.register` takes a `ControllerContext`, so an action can work in game, in screens or both. Actions default to in game only
- Added `Controllers.captureGameplay()` for overlays such as radial wheels. While it's held, Controlify stops moving the player and sending gameplay buttons, but your own actions and the sticks keep working
- In Controlify's mixed input mode, `Controllers.isActive` follows whichever device the player used last
- Added `InputKind` and `ComposeOptions.onInputKind(owner, kind)`, which now reports controller navigation too, and `KeyEvent.isFromController`
- `org.polyfrost.compose.minecraft.compat.controlify.ControllerActions`, `ControllerAction` and `ControllerButton` are deprecated in favour of the new classes in `org.polyfrost.compose.host.controller`

## [3.0.9]

- Fixed enchanted item icons on 1.21.11 and newer being drawn by the game's item renderer instead of the icon atlas, which has PolyCompose's own glint

## [3.0.8]

- Minecraft-font text on 1.8.9 now uses the text renderer's font sheet, widths and unicode mode instead of a bundled copy of the vanilla font, so resource packs with OptiFine or MCPatcher fonts (loaded by Cera) and unicode languages show the right font
- Minecraft-font text on 1.8.9 uses Argentum's exact character widths when Argentum is installed
- Fixed bold unicode characters being too narrow, and the bold and shadow offsets of unicode characters not matching the game
- Fixed large images drawn with linear filtering inside a rounded clip not showing up
- Added `McGlyph.boldAdvance` and `McGlyph.shadow`, set separately from `offset`

## [3.0.7]

- Added `McHeadStyle` and `McPlayerFaces.drawHead` for drawing player heads as only the face, flat, or in 3D with the hat at 9/8 the size of the face like the player model. `McPlayerFace` and `NodeMcPlayerFace` take a style too
- Fixed translucent hats showing the face through them. Each head is now drawn as one image
- Fixed players on a default skin having their skin downloaded when the player list already had it
- Fixed a player's skin being downloaded again after they left the player list
- Fixed 3D heads losing detail on HD skins
- Fixed player faces on 1.8.9 staying on Steve or Alex when the skin was still downloading
- Fixed player faces read by texture keeping old images after a resource reload
- Your own face on 1.8.9 now follows your player's skin, the same as on newer versions

## [3.0.6]

- Fixed hotbar items disappearing with VanillaHUD when a GUI scale mod such as Precise GUI Scale or FineScale is used on 26.1 and newer

## [3.0.5]

- Improved HUD performance: live canvas batches that haven't changed stay in the shared vertex buffer instead of being uploaded again every frame below 1.21.11, and text repaints reuse measured line widths on 1.21.11 and newer
- Animated item icons are drawn by the game's own item renderer on 1.21.11 and newer instead of being rendered into the atlas again every tick
- Fixed enchanted item icons showing a dark square on 1.8.9 when the glint overlay isn't available
- Added `GuiItemRenderer`, which the 1.21.11 and newer renderer implements, for drawing an item through the game's GUI item pass

## [3.0.4]

- Improved HUD performance on every version, most of all on 1.8.9. HUDs that haven't changed are no longer laid out and recorded again each frame, 1.8.9 and 1.16 to 1.21.10 keep the HUD in a cached texture, and text, item icons and both renderers allocate much less per frame
- Enchanted items in HUDs are rendered once with their glint drawn on top, instead of being rendered again every tick
- Fixed HUD item icons lagging a frame behind on 26.1 and newer
- Fixed live canvases in the cached HUD not updating when the state they read changed below 1.21.11
- Fixed characters from `legacy_unicode` fonts not loading below 1.21.11
- Fixed sprites not loading from protected server resource packs
- `PolyGlyphPage.Page.pixels` is now a getter, so mods compiled against the old field need a rebuild
- Added `PolyNode.reportsLayoutChanges`, `liveMeasure` and the `notify*` methods so custom nodes can skip full relayouts, `IntSupplier` overloads of `live` and `mcTextLive`, and `PolyTessellator.submitBuffer`

## [3.0.3]

- Fixed `NodeMcItemIcon` sometimes still drawing the old item after its stack changed
- Improved performance on 1.8.9 by no longer querying and rebinding the main framebuffer every frame to find its ID

## [3.0.2]

- Fixed item icons in HUDs showing up blank for their first frame
- Fixed a crash when a HUD element drew text at an invalid position, which VanillaHUD could trigger
- Fixed faded text, like EvergreenHUD's blinking potion effects, not showing on 1.8.9 after the window was resized
- Fixed item icons being rendered again over and over while their scale animates, like VanillaHUD's hotbar pickup bounce
- Added `maxScale` to `McItemIcon` and `NodeMcItemIcon` for icons that animate their scale, so they're rendered once at full resolution and stay sharp

## [3.0.1]

- Fixed the class warm-up stopping with an error on startup when a mod like LibJF also transforms classes

## [3.0.0]

- First release, split out of OneConfig
