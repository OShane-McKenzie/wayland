# Virdin Compose Wayland Shell

A Kotlin/JVM library that lets Jetpack Compose Desktop applications render directly onto Wayland layer-shell surfaces. Build docks, panels, desktop backgrounds, lock screens, on-screen displays, and application menus with native Wayland integration.

[![](https://jitpack.io/v/OShane-McKenzie/wayland.svg)](https://jitpack.io/#OShane-McKenzie/wayland)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

---

## License

This project is licensed under the **GNU General Public License v3.0**.
See the full license text at [https://www.gnu.org/licenses/gpl-3.0](https://www.gnu.org/licenses/gpl-3.0).

---

## Features

- Native Wayland layer-shell integration via `zwlr_layer_shell_v1`
- Full Jetpack Compose Desktop support with state, animations, and interactivity
- Zero runtime native dependencies. The `wayland-helper` binary is bundled inside the JAR and extracted automatically
- Multiple surface types: Dock, Panel, OSD, App Menu, Context Menu, Desktop Background, Lock Screen
- Vsync-paced rendering driven by compositor frame callbacks
- Full keyboard support via xkbcommon with layout-aware keysyms and modifier state
- Flexible configuration: anchor, layer, exclusive zone, keyboard mode, margins
- HiDPI support via `GDK_SCALE` / `QT_SCALE_FACTOR` environment variables

---

## Requirements

- Linux with Wayland
- Compositor supporting `zwlr_layer_shell_v1` (Sway, Hyprland, KWin 5.27+)
- Java 17 or higher
- Gradle 8.0 or higher

---

## How It Works

A small C binary (`wayland-helper`) is bundled inside the JAR and extracted at runtime. It connects to the Wayland compositor via `zwlr_layer_shell_v1`, creates a shared-memory framebuffer, and communicates with the JVM over a Unix domain socket. The JVM renders Compose frames into the shared memory using Skia (`ImageComposeScene`) and signals the binary to commit each frame. Frame commits are paced to the compositor vsync via `wl_surface.frame` callbacks.

```
JVM (Compose/Skia) --socket--> wayland-helper --Wayland--> Compositor
        ^                                                        |
        +-------------- shared memory (pixels) <----------------+
```

---

## Installation

Add JitPack to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.OShane-McKenzie:wayland:TAG")
}
```

Replace `TAG` with the latest release version shown in the badge above.

---

## Quick Start

The entry point must run on the Swing EDT so Compose's coroutine dispatcher has a running event pump:

```kotlin
fun main() {
    val done = CompletableDeferred<Unit>()
    SwingUtilities.invokeLater {
        val scope = CoroutineScope(Dispatchers.Swing + SupervisorJob())
        scope.launch {
            try {
                myDock(scope)
            } finally {
                done.complete(Unit)
            }
        }
    }
    runBlocking { done.await() }
}

suspend fun myDock(scope: CoroutineScope) {
    val bridge = waylandDock(
        position = ContentPosition.BOTTOM,
        size     = 64,
        scope    = scope
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xCC1E1E2E)),
            contentAlignment = Alignment.Center
        ) {
            var label by remember { mutableStateOf("My Dock") }
            Button({ label = "Clicked!" }) {
                Text("$label", color = Color.White)
            }
        }
    }
    bridge.awaitClose()
}
```

---

## Surface Types

### Dock

Reserves screen space so windows do not overlap it. Focuses keyboard on click.

```kotlin
val bridge = waylandDock(
    position  = ContentPosition.BOTTOM,
    size      = 64,
    margins   = Margins.NONE,
    namespace = "my-dock",
    density   = screenDensity(),
    scope     = scope
) { /* Compose content */ }
```

### Panel

Same as dock but with no keyboard focus. Ideal for status bars and taskbars.

```kotlin
val bridge = waylandPanel(
    position  = ContentPosition.TOP,
    size      = 32,
    namespace = "my-panel",
    scope     = scope
) { /* Compose content */ }
```

### OSD (On-Screen Display)

Floating, centred, no exclusive zone. Perfect for volume and brightness indicators.

```kotlin
suspend fun showVolumeOsd(scope: CoroutineScope) {
    val bridge = waylandOsd(width = 280, height = 80, scope = scope) {
        Box(
            Modifier.fillMaxSize().background(Color(0xEE000000)),
            contentAlignment = Alignment.Center
        ) {
            Text("Volume 75%", color = Color.White)
        }
    }
    delay(2000)
    bridge.close()
}
```

Trigger it from inside a dock button:

```kotlin
val scope = rememberCoroutineScope()

Button(onClick = {
    scope.launch { showVolumeOsd(scope) }
}) {
    Text("Volume")
}
```

### Context Menu

Automatically positions itself at the cursor and flips its anchor when near screen edges.

```kotlin
suspend fun showContextMenu(cursorX: Float, cursorY: Float, scope: CoroutineScope) {
    contextMenu(
        cursorX      = cursorX,
        cursorY      = cursorY,
        menuWidth    = 200f,
        menuHeight   = 200f,
        screenWidth  = 1920f,
        screenHeight = 1080f,
        scope        = scope
    ) { dismiss ->
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF2A2A2A))
                .padding(8.dp)
        ) {
            TextButton(onClick = dismiss) { Text("Open",     color = Color.White) }
            TextButton(onClick = dismiss) { Text("Settings", color = Color.White) }
            Divider(color = Color.White.copy(alpha = 0.2f))
            TextButton(onClick = dismiss) { Text("Quit",     color = Color.Red) }
        }
    }
}
```

Trigger from a right-click:

```kotlin
val scope = rememberCoroutineScope()
var cursorX by remember { mutableStateOf(0f) }
var cursorY by remember { mutableStateOf(0f) }

Box(
    modifier = Modifier
        .fillMaxSize()
        .onPointerEvent(PointerEventType.Move) {
            cursorX = it.changes.first().position.x
            cursorY = it.changes.first().position.y
        }
        .onPointerEvent(PointerEventType.Press) {
            if (it.buttons.isSecondaryPressed) {
                scope.launch {
                    showContextMenu(cursorX, cursorY - dockHeight, scope)
                }
            }
        }
) { /* dock content */ }
```

### App Menu

> **Important:** use `mutableStateOf` rather than `lateinit var` to hold the bridge reference inside composable content. The composable runs during scene construction before `waylandAppMenu` returns, so `lateinit` will throw `UninitializedPropertyAccessException`.

```kotlin
suspend fun demoAppMenu(scope: CoroutineScope) {
    val bridgeRef = mutableStateOf<WaylandBridge?>(null)
    val bridge = waylandAppMenu(
        position = ContentPosition.BOTTOM,
        width    = 500,
        height   = 350,
        scope    = scope
    ) {
        CompositionLocalProvider(LocalWaylandBridge provides bridgeRef.value) {
            AppMenuContent()
        }
    }
    bridgeRef.value = bridge
    bridge.awaitClose()
}

@Composable
fun AppMenuContent() {
    val bridge = LocalWaylandBridge.current
    Column(
        Modifier.fillMaxSize().background(Color(0xFF1E1E2E)).padding(16.dp)
    ) {
        listOf("Files", "Terminal", "Browser", "Settings").forEach { app ->
            TextButton(onClick = { bridge?.close() }) {
                Text(app, color = Color.White)
            }
        }
    }
}
```

### Desktop Background

```kotlin
val bridge = waylandDesktopBackground(scope = scope) {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0D1117)),
        contentAlignment = Alignment.Center
    ) {
        Text("My Desktop", color = Color(0xFF58A6FF))
    }
}
```

### Lock Screen

```kotlin
val bridge = waylandLockScreen(scope = scope) { LockScreenContent() }
```

Placed on the `OVERLAY` layer with an exclusive keyboard grab.

### Custom Surface

```kotlin
val bridge = waylandSurface(
    config = WindowConfig(
        layer         = WindowLayer.TOP,
        anchor        = Anchor.RIGHT or Anchor.TOP,
        exclusiveZone = 0,
        keyboardMode  = KeyboardMode.ON_DEMAND,
        width         = 320,
        height        = 480,
        margins       = Margins(top = 8, right = 8),
        namespace     = "my-widget"
    ),
    scope = scope
) { /* content */ }
```

---

## Spawning Multiple Surfaces

Every surface is an independent bridge. Spawn OSDs, context menus, and other surfaces from within any composable using `rememberCoroutineScope`:

```kotlin
val scope = rememberCoroutineScope()

Button(onClick = {
    scope.launch { showVolumeOsd(scope) }
}) {
    Text("Volume")
}
```

> Avoid creating a raw `CoroutineScope(Dispatchers.IO)` inside a composable. It will never be cancelled and will leak. Use `rememberCoroutineScope()` instead, which is cancelled automatically when the composable leaves composition.

---

## Configuration Reference

### WindowConfig

| Field | Type | Default | Description |
|---|---|---|---|
| `layer` | `WindowLayer` | `TOP` | Compositor layer |
| `anchor` | `Int` (bitfield) | `0` | Screen edges to anchor to |
| `exclusiveZone` | `Int` | `0` | Pixels to reserve along anchored edge |
| `keyboardMode` | `KeyboardMode` | `NONE` | Keyboard focus behaviour |
| `width` | `Int` | `0` | Width in logical px (0 = fill axis) |
| `height` | `Int` | `0` | Height in logical px (0 = fill axis) |
| `margins` | `Margins` | `Margins.NONE` | Gap between surface and screen edges |
| `namespace` | `String` | `"virdin-surface"` | Compositor debug identifier |
| `density` | `Density` | `screenDensity()` | Pixel density for Compose rendering |

### WindowLayer

| Value | Description |
|---|---|
| `BACKGROUND` | Behind all windows |
| `BOTTOM` | Below normal windows |
| `TOP` | Above normal windows |
| `OVERLAY` | Above everything |

### Anchor

```kotlin
Anchor.TOP
Anchor.BOTTOM
Anchor.LEFT
Anchor.RIGHT
Anchor.ALL     // all four edges, full screen
Anchor.NONE    // floating, compositor centres the surface
```

Combine with `or`:

```kotlin
anchor = Anchor.TOP or Anchor.LEFT or Anchor.RIGHT   // full-width top bar
anchor = Anchor.BOTTOM or Anchor.RIGHT               // bottom-right corner widget
```

### KeyboardMode

| Value | Description |
|---|---|
| `NONE` | No keyboard focus |
| `ON_DEMAND` | Focus when clicked |
| `EXCLUSIVE` | Grab all keyboard input |

### Margins

Gap between the surface and the screen edge on each side.

```kotlin
Margins.NONE              // no margins (default)
Margins.all(8)            // 8px on all sides
Margins.horizontal(24)    // left and right only
Margins.vertical(4)       // top and bottom only
Margins(top = 4, right = 8)  // per-side
```

### ContentPosition

```kotlin
enum class ContentPosition { TOP, BOTTOM, LEFT, RIGHT }
```

### ScreenSize

```kotlin
data class ScreenSize(val width: Float, val height: Float)
```

Used by `contextMenu` and `ContextMenuConfig` for edge-aware positioning.

### MenuAnchor

Resolved automatically by `ContextMenuConfig.resolvedAnchor`.

```kotlin
MenuAnchor.TOP_LEFT      // opens right and down (default)
MenuAnchor.TOP_RIGHT     // opens left and down (near right edge)
MenuAnchor.BOTTOM_LEFT   // opens right and up (near bottom edge)
MenuAnchor.BOTTOM_RIGHT  // opens left and up (near bottom-right corner)
```

---

## Keyboard Events

```kotlin
data class KeyEvent(
    val keycode:  Int,   // raw evdev keycode
    val state:    Int,   // 0=released  1=pressed  2=repeated
    val modifiers: Int,  // bit0=shift  bit1=ctrl  bit2=alt  bit3=super
    val keysym:   Int    // XKB keysym, layout-aware and unicode-aware
)
```

Use `keysym` for text input (it reflects the user's keyboard layout). Use `keycode` for layout-independent shortcuts such as game controls.

---

## Bridge Lifecycle

```kotlin
bridge.state          // StateFlow<BridgeState>
bridge.actualWidth    // compositor-confirmed width in px
bridge.actualHeight   // compositor-confirmed height in px
bridge.close()        // destroy surface and free resources
bridge.awaitClose()   // suspend until close() is called
```

`BridgeState` transitions: `IDLE -> STARTING -> CONFIGURED -> RUNNING -> CLOSED / ERROR`

---

## Utilities

```kotlin
// Pass the bridge into nested composables
val LocalWaylandBridge: ProvidableCompositionLocal<WaylandBridge?>

// Detect HiDPI scale from GDK_SCALE / QT_SCALE_FACTOR, falls back to AWT
fun screenDensity(): Density

// Override binary source
BinarySource.Bundled                            // default, extracted from JAR
BinarySource.Path("/your/path/wayland-helper")  // use an installed binary
```

---

## HiDPI

`screenDensity()` reads `GDK_SCALE` or `QT_SCALE_FACTOR` environment variables set by most Wayland desktops, then falls back to AWT DPI detection. Override per surface:

```kotlin
waylandDock(density = Density(2f)) { ... }
```

---

## Known Limitations

- Single monitor only. Multi-monitor output selection is not yet implemented.
- No server-side window decorations. Layer shell surfaces cannot have title bars or window chrome. This is a Wayland protocol constraint; decorations are only available on `xdg_toplevel` surfaces.
- Requires compositor support for `zwlr_layer_shell_v1`
- `ImageComposeScene` is `@ExperimentalComposeUiApi` and may change across CMP versions

---

## Troubleshooting

**Surface not appearing**

Check that your compositor supports `zwlr_layer_shell_v1`. Confirmed working: Sway, Hyprland, KWin 5.27+.

**Blurry rendering on HiDPI**

Set `GDK_SCALE=2` (or your scale factor) in your launch script, or pass `density = Density(2f)` explicitly.

**App crashes on rapid clicks**

Wrap your content in `MaterialTheme { }` to give the Compose ripple system a proper context. This is a known CMP issue with `ImageComposeScene`.

**Build fails with `xkbcommon not found`**

Install the development package:

```bash
# Arch / Manjaro
sudo pacman -S libxkbcommon

# Ubuntu / Debian
sudo apt install libxkbcommon-dev

# Fedora
sudo dnf install libxkbcommon-devel
```

---

## Contributing

Contributions are welcome. Please open an issue or submit a pull request.