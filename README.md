# Virdin Compose Wayland Shell

A Kotlin/JVM library that enables Jetpack Compose Desktop applications to render directly onto Wayland layer-shell surfaces. Create docks, panels, desktop backgrounds, lock screens, on-screen displays, and application menus with native Wayland integration, no JNA, no `libwayland-client.so` dependency.

---
[![](https://jitpack.io/v/OShane-McKenzie/wayland.svg)](https://jitpack.io/#OShane-McKenzie/wayland)
---

## Features

- 🎯 **Native Wayland Support** Direct compositor integration via `zwlr_layer_shell_v1`
- 🪟 **Multiple Surface Types** Dock, Panel, Desktop Background, Lock Screen, OSD, App Menu
- 🎨 **Jetpack Compose Desktop** Full Compose UI with state, animations, and interactivity
- 📦 **Zero native dependencies** `wayland-helper` binary is bundled inside the JAR, extracted at runtime
- 🔧 **Flexible Configuration** Anchor, layer, exclusive zone, keyboard mode
- 🐧 **Linux/Wayland first** Built specifically for wlroots-based and KWin compositors

## Requirements

- **Operating System**: Linux with Wayland
- **Compositor**: Must support `zwlr_layer_shell_v1` (Sway, Hyprland, KWin)
- **JVM**: Java 17 or higher
- **Gradle**: 8.0 or higher

## How it works

A small C binary (`wayland-helper`) is bundled inside the JAR and extracted at runtime. It connects to the Wayland compositor via `zwlr_layer_shell_v1`, creates a shared-memory surface, and communicates with the JVM over a Unix domain socket. The JVM renders Compose frames into the shared memory using Skia (`ImageComposeScene`) and signals the binary to commit them.

```
JVM (Compose/Skia) ──socket──► wayland-helper ──Wayland──► Compositor
        ▲                                                        │
        └──────────────── shared memory (pixels) ◄──────────────┘
```

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

## Quick Start

The entry point must run on the Swing EDT so Compose's coroutine dispatcher has a running event pump:

```kotlin
fun main() {
    val done = CompletableDeferred<Unit>()
    SwingUtilities.invokeLater {
        val scope = CoroutineScope(Dispatchers.Swing + SupervisorJob())
        scope.launch {
            myDock(scope)
            done.complete(Unit)
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
                Text("🚀  $label", color = Color.White)
            }
        }
    }
    bridge.awaitClose()
}
```

## Surface Types

### 1. Dock

```kotlin
val bridge = waylandDock(
    position  = ContentPosition.BOTTOM,  // TOP | BOTTOM | LEFT | RIGHT
    size      = 64,                       // thickness in logical px
    namespace = "my-dock",
    density   = screenDensity(),
    scope     = scope
) { /* Compose content */ }
```

Reserves screen space (`exclusiveZone = size`) so other windows don't overlap.
On-demand keyboard focus, focused when clicked.

### 2. Panel

```kotlin
val bridge = waylandPanel(
    position  = ContentPosition.TOP,
    size      = 32,
    namespace = "my-panel",
    scope     = scope
) { /* Compose content */ }
```

Same as dock but with no keyboard focus by default. Ideal for status bars and taskbars.

### 3. OSD (On-Screen Display)

Floating, centred, no exclusive zone. Perfect for volume/brightness indicators.

```kotlin
val bridge = waylandOsd(width = 280, height = 80, scope = scope) {
    Box(
        Modifier.fillMaxSize().background(Color(0xEE000000)),
        contentAlignment = Alignment.Center
    ) {
        Text("🔊  Volume 75%", color = Color.White)
    }
}
delay(2000)
bridge.close()
```

### 4. App Menu

> **Important:** use `mutableStateOf` (not `lateinit var`) to hold the bridge reference
> inside composable content. The composable runs during scene construction, before
> `waylandAppMenu` returns, so `lateinit` will throw `UninitializedPropertyAccessException`.

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
    bridgeRef.value = bridge  // triggers recomposition with real ref
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

### 5. Desktop Background

```kotlin
val bridge = waylandDesktopBackground(scope = scope) {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0D1117)),
        contentAlignment = Alignment.Center
    ) {
        Text("✨  My Desktop", color = Color(0xFF58A6FF))
    }
}
```

Placed on the `BACKGROUND` layer, behind all other windows.

### 6. Lock Screen

```kotlin
val bridge = waylandLockScreen(scope = scope) { LockScreenContent() }
```

Placed on the `OVERLAY` layer with exclusive keyboard grab, captures all input.

### 7. Fully Custom Surface

```kotlin
val bridge = waylandSurface(
    config = WindowConfig(
        layer         = WindowLayer.TOP,
        anchor        = Anchor.RIGHT or Anchor.TOP,
        exclusiveZone = 0,
        keyboardMode  = KeyboardMode.ON_DEMAND,
        width         = 320,
        height        = 480,
        namespace     = "my-widget"
    ),
    scope = scope
) { /* content */ }
```

## Configuration Reference

### `WindowConfig`

| Field | Type | Default | Description |
|---|---|---|---|
| `layer` | `WindowLayer` | `TOP` | Compositor layer |
| `anchor` | `Int` (bitfield) | `0` | Which screen edges to anchor to |
| `exclusiveZone` | `Int` | `0` | Pixels to reserve along anchored edge |
| `keyboardMode` | `KeyboardMode` | `NONE` | Keyboard focus behaviour |
| `width` | `Int` | `0` | Width in logical px (0 = fill axis) |
| `height` | `Int` | `0` | Height in logical px (0 = fill axis) |
| `namespace` | `String` | `"virdin-surface"` | Compositor debug identifier |
| `density` | `Density` | `screenDensity()` | Pixel density for Compose rendering |

### `WindowLayer`

| Value | Description |
|---|---|
| `BACKGROUND` | Behind all windows, wallpaper |
| `BOTTOM` | Below normal windows |
| `TOP` | Above normal windows, docks, panels |
| `OVERLAY` | Above everything, lock screen, OSD |

### `Anchor`

```kotlin
Anchor.TOP    // top edge
Anchor.BOTTOM // bottom edge
Anchor.LEFT   // left edge
Anchor.RIGHT  // right edge
Anchor.ALL    // all four edges, full screen
Anchor.NONE   // no anchor, compositor centres the surface
```

Combine with `or`:
```kotlin
anchor = Anchor.TOP or Anchor.LEFT or Anchor.RIGHT  // full-width top bar
anchor = Anchor.BOTTOM or Anchor.RIGHT               // bottom-right corner widget
```

### `KeyboardMode`

| Value | Description |
|---|---|
| `NONE` | No keyboard focus, panels, backgrounds |
| `ON_DEMAND` | Focus when clicked, docks, menus |
| `EXCLUSIVE` | Grab all keyboard input, lock screen |

### `ContentPosition`

```kotlin
enum class ContentPosition { TOP, BOTTOM, LEFT, RIGHT }
```

## Bridge Lifecycle

```kotlin
bridge.state          // StateFlow<BridgeState>
bridge.actualWidth    // compositor-confirmed width in px
bridge.actualHeight   // compositor-confirmed height in px
bridge.close()        // destroy surface and free resources
bridge.awaitClose()   // suspend until close() is called
```

`BridgeState`: `IDLE → STARTING → CONFIGURED → RUNNING → CLOSED / ERROR`

## Utilities

```kotlin
// Pass the bridge into nested composables
val LocalWaylandBridge: ProvidableCompositionLocal<WaylandBridge?>

// Detect HiDPI scale (reads GDK_SCALE / QT_SCALE_FACTOR, falls back to AWT)
fun screenDensity(): Density

// Override binary source
BinarySource.Bundled                              // default, extracted from JAR
BinarySource.Path("/your/path/wayland-helper") // use an installed binary
```

## HiDPI / Density

`screenDensity()` reads `GDK_SCALE` or `QT_SCALE_FACTOR` env vars set by most
Wayland desktops, then falls back to AWT DPI detection. On a 2× HiDPI display
with `GDK_SCALE=2`, Compose renders at full physical resolution automatically.

Override per-surface:

```kotlin
waylandDock(density = Density(2f)) { ... }
```

## Known Limitations

- **Single monitor only** multi-monitor output selection not yet implemented
- **Raw keycodes** no xkbcommon integration; key events carry Linux keycodes only
- **No server-side window decorations** layer shell surfaces cannot have title bars or
  window chrome drawn by the compositor. This is a Wayland protocol constraint: decorations
  are only available on `xdg_toplevel` surfaces (normal application windows).
- Requires compositor support for `zwlr_layer_shell_v1`
- `ImageComposeScene` is `@ExperimentalComposeUiApi`, API may change across CMP versions

## Troubleshooting

**Surface not appearing:**
- Check compositor logs for `zwlr_layer_shell_v1` errors
- Verify your compositor supports the protocol (Sway, Hyprland, KWin 5.27+)

**Blurry rendering on HiDPI:**
- Set `GDK_SCALE=2` (or your scale factor) in your launch script
- Or pass `density = Density(2f)` explicitly to your surface function

**App crashes on rapid clicks:**
- Known CMP issue with ripple animations in `ImageComposeScene`, wrap your
  content in `MaterialTheme { }` to provide the ripple system a proper context

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.