# Virdin Compose Wayland Shell


A Kotlin/JVM library that enables Jetpack Compose Desktop applications to interact with Wayland compositors using the **wlr-layer-shell** protocol. Create docks, panels, desktop backgrounds, lock screens, and on-screen displays (OSDs) with native Wayland integration.

## Features

- 🎯 **Native Wayland Support** - Direct integration with Wayland compositors via wlr-layer-shell
- 🪟 **Multiple Window Types** - Dock, Panel, Desktop Background, Lock Screen, OSD
- 🎨 **Jetpack Compose Desktop** - Build beautiful UIs with Compose
- ⚡ **Lightweight** - Minimal dependencies using JNA for native calls
- 🔧 **Flexible Configuration** - Customize position, size, layer, and keyboard interactivity
- 🐧 **Linux First** - Built specifically for Linux desktop environments

## Requirements

- **Operating System**: Linux with Wayland
- **Compositor**: Must support `wlr-layer-shell-unstable-v1` protocol
- **JVM**: Java 17 or higher
- **Gradle**: 8.0 or higher

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
    implementation("com.github.OShane-McKenzie.wayland:wayland:1.0.3-ALPHA")
}
```

## Quick Start

### Basic Dock Example

```kotlin
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.window.*
import pkg.virdin.wayland.*

fun main() = application {
    val nativeWayland = remember { NativeWaylandCalls() }
    val scope = rememberCoroutineScope()
    
    val waylandBridge = remember {
        if (nativeWayland.isWaylandAvailable() && nativeWayland.initialize()) {
            nativeWayland.getManager()?.let { ComposeWaylandBridge(it) }
        } else null
    }
    
    DisposableEffect(Unit) {
        onDispose {
            waylandBridge?.cleanup()
            nativeWayland.cleanup()
        }
    }
    
    Window(
        onCloseRequest = ::exitApplication,
        title = "My Dock",
        undecorated = true,
        transparent = false,
        alwaysOnTop = true,
        resizable = false
    ) {
        LaunchedEffect(Unit) {
            waylandBridge?.configureAsDock(
                window = window,
                coroutineScope = scope,
                position = ContentPosition.BOTTOM,
                size = 64
            )
        }
        
        MaterialTheme {
            // Your dock UI here
            YourDockContent()
        }
    }
}
```

## Window Types

### 1. Dock

Create an application dock at any screen edge:

```kotlin
waylandBridge.configureAsDock(
    window = window,
    coroutineScope = scope,
    position = ContentPosition.BOTTOM, // TOP, BOTTOM, LEFT, RIGHT
    size = 64
)
```

**Features:**
- Reserves screen space (exclusive zone)
- Interactive with on-demand keyboard focus
- Positioned at screen edges

### 2. Panel

Create a system panel (taskbar/top bar):

```kotlin
waylandBridge.configureAsPanel(
    window = window,
    coroutineScope = scope,
    position = ContentPosition.TOP,
    size = 32
)
```

**Features:**
- Reserves screen space
- No keyboard interaction by default
- Positioned at screen edges

### 3. Desktop Background

Create a desktop wallpaper/background:

```kotlin
waylandBridge.configureAsDesktopBackground(
    window = window,
    coroutineScope = scope
)
```

**Features:**
- Sits behind all other windows
- Full screen coverage
- No keyboard interaction
- No exclusive zone (doesn't push other windows)

### 4. Lock Screen

Create a lock screen overlay:

```kotlin
waylandBridge.configureAsLockScreen(
    window = window,
    coroutineScope = scope
)
```

**Features:**
- Overlay layer (above everything)
- Captures all keyboard input exclusively
- Full screen coverage
- Exclusive zone -1 (captures all input)

### 5. OSD (On-Screen Display)

Create notifications or temporary overlays:

```kotlin
waylandBridge.configureAsOSD(
    window = window,
    coroutineScope = scope,
    width = 300,
    height = 100
)
```

**Features:**
- Overlay layer
- Centered on screen (no anchoring)
- No keyboard interaction
- No exclusive zone
- Perfect for volume/brightness indicators

## Configuration Options

### DockPosition Enum

```kotlin
enum class ContentPosition {
    TOP,     // Top edge of screen
    BOTTOM,  // Bottom edge of screen
    LEFT,    // Left edge of screen
    RIGHT    // Right edge of screen
}
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

For complete control, use the low-level `WaylandWindowManager`:

```kotlin
val manager = WaylandWindowManager()
if (manager.initialize()) {
    val surface = manager.createSurface()
    val layerSurface = manager.createLayerSurface(
        surface = surface,
        layer = LayerShellProtocol.LAYER_OVERLAY,
        anchor = LayerShellProtocol.ANCHOR_TOP or LayerShellProtocol.ANCHOR_LEFT,
        exclusiveZone = 0,
        namespace = "my-custom-surface"
    )
    
    // Configure size, anchor, etc.
    manager.setLayerSurfaceSize(layerSurface, 400, 200)
    manager.commitSurface(surface)
}
```

### Checking Compositor Support

```kotlin
val nativeWayland = NativeWaylandCalls()

if (!nativeWayland.isWaylandAvailable()) {
    println("Not running on Wayland")
} else if (nativeWayland.initialize()) {
    val manager = nativeWayland.getManager()
    if (manager?.isLayerShellSupported() == true) {
        println("Compositor supports wlr-layer-shell")
    } else {
        println("Compositor does not support wlr-layer-shell")
    }
}
```

## Architecture

The library is structured in layers:

```
ComposeWaylandBridge (High-level API)
         ↓
WaylandWindowManager (Mid-level protocol handling)
         ↓
WaylandClientLib (JNA bindings)
         ↓
libwayland-client.so (Native Wayland library)
```

### Key Components

- **`ComposeWaylandBridge`** - High-level API for Compose Desktop
- **`WaylandWindowManager`** - Manages Wayland connections and surfaces
- **`NativeWaylandCalls`** - Initialization and environment detection
- **`LayerShellProtocol`** - Constants for wlr-layer-shell protocol
- **`WaylandClientLib`** - JNA interface to libwayland-client

## Troubleshooting

If running under XWayland, the library will fall back to X11 window positioning.

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

### Window Not Appearing

1. Check compositor logs for errors
2. Verify the window is created before calling configure methods
3. Ensure proper cleanup on application exit
4. Try increasing the delay before configuration:
   ```kotlin
   LaunchedEffect(Unit) {
       delay(200)
       waylandBridge.configureAsDock(...)
   }
   ```

## Examples

See the [examples](examples/) directory for complete working examples:

- **Simple Dock** - Basic dock at screen bottom
- **Top Panel** - System panel with clock and indicators
- **Desktop Background** - Animated gradient background
- **Lock Screen** - Password entry overlay
- **Volume OSD** - Pop-up volume indicator

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

### Development Setup

```bash
git clone https://github.com/OShane-McKenzie/wayland.git
cd compose-wayland-interop
./gradlew build
```

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Built on top of [wlr-layer-shell protocol](https://wayland.app/protocols/wlr-layer-shell-unstable-v1)
- Uses [JNA](https://github.com/java-native-access/jna) for native interop
- Inspired by other Wayland layer shell implementations

## Links

- [JitPack Repository](https://jitpack.io/#OShane-McKenzie/compose-wayland-interop)
- [Issue Tracker](https://github.com/OShane-McKenzie/compose-wayland-interop/issues)
- [wlr-layer-shell Protocol](https://wayland.app/protocols/wlr-layer-shell-unstable-v1)
- [Jetpack Compose Desktop](https://www.jetbrains.com/lp/compose-desktop/)

---

**Made with ❤️ for the Linux Desktop**
