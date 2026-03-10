# Virdin Compose Wayland Shell


A Kotlin/JVM library that enables Jetpack Compose Desktop applications to interact with Wayland compositors using the **wlr-layer-shell** protocol. Create docks, panels, desktop backgrounds, lock screens, and on-screen displays (OSDs) with native Wayland integration.

## Features

- 🎯 **Native Wayland Support** - Direct integration with Wayland compositors via wlr-layer-shell
- 🪟 **Multiple Window Types** - Dock, Panel, Desktop Background, Lock Screen, OSD, App Menu
- 🎨 **Jetpack Compose Desktop** - Build beautiful UIs with Compose
- ⚡ **Lightweight** - Minimal dependencies, native helper binary bundled in JAR
- 🔧 **Flexible Configuration** - Customize position, size, layer, margins, and keyboard interactivity
- 🐧 **Linux First** - Built specifically for Linux desktop environments

## Requirements

- **Operating System**: Linux with Wayland
- **Compositor**: Must support `wlr-layer-shell-unstable-v1` protocol (Sway, Hyprland, river, wayfire, and most wlroots-based compositors)
- **JVM**: Java 17 or higher
- **Gradle**: 8.0 or higher

> **Note:** GNOME Shell and KDE Plasma do not support `wlr-layer-shell` by default.

## Installation

### Using JitPack

Add JitPack repository to your `settings.gradle.kts`:

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
    implementation("com.github.OShane-McKenzie:wayland:2.0.3-ALPHA")
}
```

## Quick Start

### Basic Dock Example

```kotlin
import pkg.virdin.wayland.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import javax.swing.SwingUtilities

fun main() {
    val done = CompletableDeferred<Unit>()

    SwingUtilities.invokeLater {
        val scope = CoroutineScope(Dispatchers.Swing + SupervisorJob())
        scope.launch {
            val bridge = waylandDock(
                position = ContentPosition.BOTTOM,
                size     = 64,
                scope    = scope
            ) {
                // Your dock UI here
                YourDockContent()
            }
            bridge.awaitClose()
            done.complete(Unit)
        }
    }

    kotlinx.coroutines.runBlocking { done.await() }
}
```

> **Important:** always launch from `SwingUtilities.invokeLater` with a `CoroutineScope(Dispatchers.Swing)`. Compose's internal machinery requires a running AWT event pump. Using `runBlocking` directly on the main thread starts no AWT loop and produces blank frames.

## Window Types

### 1. Dock

Create an application dock at any screen edge:

```kotlin
val bridge = waylandDock(
    position = ContentPosition.BOTTOM, // TOP, BOTTOM, LEFT, RIGHT
    size     = 64,
    margins  = Margins.all(8),         // optional — gives a floating look
    scope    = scope
) {
    YourDockContent()
}
```

**Features:**
- Reserves screen space (exclusive zone)
- Interactive with on-demand keyboard focus
- Positioned at screen edges

### 2. Panel

Create a system panel (taskbar/top bar):

```kotlin
val bridge = waylandPanel(
    position = ContentPosition.TOP,
    size     = 32,
    scope    = scope
) {
    YourPanelContent()
}
```

**Features:**
- Reserves screen space
- No keyboard interaction by default
- Positioned at screen edges

### 3. Desktop Background

Create a desktop wallpaper/background:

```kotlin
val bridge = waylandDesktopBackground(scope = scope) {
    AnimatedGradientBackground()
}
```

**Features:**
- Sits behind all other windows
- Full screen coverage
- No keyboard interaction
- No exclusive zone (doesn't push other windows)

### 4. Lock Screen

Create a lock screen overlay:

```kotlin
val bridge = waylandLockScreen(scope = scope) {
    LockScreenContent()
}
```

**Features:**
- Overlay layer (above everything)
- Captures all keyboard input exclusively
- Full screen coverage
- Exclusive zone -1 (captures all input)

### 5. OSD (On-Screen Display)

Create notifications or temporary overlays:

```kotlin
val bridge = waylandOsd(width = 300, height = 100, scope = scope) {
    VolumeIndicator()
}
delay(2000)
bridge.close()
```

**Features:**
- Overlay layer
- Centered on screen (no anchoring)
- No keyboard interaction
- No exclusive zone
- Perfect for volume/brightness indicators

### 6. App Menu / Launcher

Create an app launcher anchored to a screen edge:

```kotlin
// Use mutableStateOf — NOT lateinit — to hold the bridge reference inside
// the composable. The composable runs during scene construction, before
// waylandAppMenu() returns, so lateinit will throw.
val bridgeRef = mutableStateOf<WaylandBridge?>(null)

val bridge = waylandAppMenu(
    position = ContentPosition.BOTTOM,
    width    = 600,
    height   = 400,
    scope    = scope
) {
    CompositionLocalProvider(LocalWaylandBridge provides bridgeRef.value) {
        AppMenuContent()
    }
}
bridgeRef.value = bridge
bridge.awaitClose()
```

**Features:**
- Overlay layer
- Anchored to a screen edge at an explicit size
- Keyboard focus on demand

## Configuration Options

### ContentPosition Enum

```kotlin
enum class ContentPosition {
    TOP,     // Top edge of screen
    BOTTOM,  // Bottom edge of screen
    LEFT,    // Left edge of screen
    RIGHT    // Right edge of screen
}
```

### Margins

Add gaps between the surface and the screen edge for a floating look:

```kotlin
Margins.NONE                // no margins (default)
Margins.all(8)              // 8px on all sides
Margins.horizontal(12)      // left + right only
Margins.vertical(4)         // top + bottom only
Margins(top = 8, right = 8) // per-side
```

### Layer Shell Protocols

The library uses the wlr-layer-shell protocol with these layers (from bottom to top):

1. `LAYER_BACKGROUND` - Desktop backgrounds
2. `LAYER_BOTTOM` - Below normal windows
3. `LAYER_TOP` - Panels and docks (default)
4. `LAYER_OVERLAY` - Lock screens, OSDs, above everything

### Keyboard Interactivity

- `KEYBOARD_INTERACTIVITY_NONE` - No keyboard input
- `KEYBOARD_INTERACTIVITY_EXCLUSIVE` - Captures all keyboard input
- `KEYBOARD_INTERACTIVITY_ON_DEMAND` - Keyboard on focus (default for docks)

## Advanced Usage

### Manual Configuration

For complete control, use `waylandSurface` with a `WindowConfig`:

```kotlin
val config = WindowConfig(
    layer         = WindowLayer.OVERLAY,
    anchor        = Anchor.RIGHT or Anchor.TOP,
    exclusiveZone = 0,
    keyboardMode  = KeyboardMode.ON_DEMAND,
    width         = 320,
    height        = 480,
    margins       = Margins(top = 8, right = 8),
    namespace     = "my-custom-surface"
)

val bridge = waylandSurface(config = config, scope = scope) {
    MySurfaceContent()
}
```

### BinarySource

The `wayland-helper` binary is bundled inside the JAR and extracted automatically at runtime. You can also point to a binary installed on your system:

```kotlin
// Default — extract from JAR (works out of the box)
waylandDock(binary = BinarySource.Bundled, ...) { ... }

// Use a binary installed on the system
waylandDock(binary = BinarySource.Path("/usr/local/bin/wayland-helper"), ...) { ... }
```

### HiDPI / Screen Density

On Wayland, AWT always reports 96 DPI regardless of actual HiDPI scale. Use `screenDensity()`, which reads `GDK_SCALE` and `QT_SCALE_FACTOR` environment variables automatically:

```kotlin
val bridge = waylandDock(
    density = screenDensity(),
    ...
)
```

This is the default for all preset functions, so you only need to pass it explicitly when building a `WindowConfig` manually.

### CompositionLocal

Access the bridge from deeply nested composables without prop-drilling:

```kotlin
CompositionLocalProvider(LocalWaylandBridge provides bridge) {
    MyContent() // call LocalWaylandBridge.current anywhere inside
}
```

## Known Issues

### Gradient caveat — desktop Skia vs Android Skia

`Brush.linearGradient` with `end = Offset(Float.MAX_VALUE, Float.MAX_VALUE)` is a common Android pattern for a diagonal gradient that fills any bounds. Android's Skia fork handles infinite coordinates silently. Desktop Skia does not — it returns a null shader pointer and throws `RuntimeException: Can't wrap nullptr` at draw time, crashing the entire render frame.

```kotlin
// ❌ crashes on desktop Skia
Brush.linearGradient(
    colors,
    start = Offset.Zero,
    end   = Offset(Float.MAX_VALUE, Float.MAX_VALUE)
)

// ✅ works everywhere — Compose defaults to top-left → bottom-right
Brush.linearGradient(colors)
```

## Troubleshooting

### Window Not Appearing

1. Verify your compositor supports `wlr-layer-shell-unstable-v1`
2. Ensure you are launching from `SwingUtilities.invokeLater` with `Dispatchers.Swing`
3. Check compositor logs for errors

### JNA Native Library Issues

Make sure `libwayland-client.so` is installed:
```bash
# Arch Linux
sudo pacman -S wayland

# Ubuntu/Debian
sudo apt install libwayland-client0

# Fedora
sudo dnf install wayland
```

### Building the Native Binary

The bundled binary is pre-compiled for `linux-x86_64` and `linux-aarch64`. To compile from source:

```bash
./build_native.sh
```

Requires `gcc` and `libwayland-dev` (`libwayland-devel` on Fedora).

## Examples

See the [examples](examples/) directory for complete working examples:

- **Simple Dock** - Basic dock at screen bottom
- **Top Panel** - System panel with clock and indicators
- **Desktop Background** - Animated gradient background
- **Lock Screen** - Password entry overlay
- **Volume OSD** - Pop-up volume indicator
- **App Menu** - Application launcher

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

### Development Setup

```bash
git clone https://github.com/OShane-McKenzie/wayland.git
cd compose-wayland-interop
./gradlew build
```

## License

This project is licensed under the GPL 3 License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Built on top of [wlr-layer-shell protocol](https://wayland.app/protocols/wlr-layer-shell-unstable-v1)
- Uses [Skiko](https://github.com/JetBrains/skiko) for offscreen Compose rendering
- Inspired by other Wayland layer shell implementations

## Links

- [JitPack Repository](https://jitpack.io/#OShane-McKenzie/compose-wayland-interop)
- [Issue Tracker](https://github.com/OShane-McKenzie/compose-wayland-interop/issues)
- [wlr-layer-shell Protocol](https://wayland.app/protocols/wlr-layer-shell-unstable-v1)
- [Jetpack Compose Desktop](https://www.jetbrains.com/lp/compose-desktop/)

---

**Made with ❤️ for the Linux Desktop**