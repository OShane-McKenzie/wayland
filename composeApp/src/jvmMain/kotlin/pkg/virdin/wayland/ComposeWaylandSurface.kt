package pkg.virdin.wayland

import androidx.compose.runtime.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing

/**
 * Returns the screen density. On Wayland, AWT always reports 96 DPI regardless
 * of the actual HiDPI scale, so we read the GDK/Qt scale env vars that most
 * Wayland desktops set, then fall back to AWT.
 */
fun screenDensity(): Density {
    // Most Wayland compositors set one of these for apps that don't speak HiDPI natively
    val fromEnv = System.getenv("GDK_SCALE")?.toFloatOrNull()
        ?: System.getenv("QT_SCALE_FACTOR")?.toFloatOrNull()
        ?: System.getenv("XCURSOR_SIZE")?.let { null } // don't use XCURSOR_SIZE
    if (fromEnv != null && fromEnv > 0f) return Density(fromEnv)

    // Check wl_output scale via env var set by some launchers
    System.getenv("WAYLAND_DISPLAY")?.let {
        // On typical 2x HiDPI Wayland setups the output scale is 2
        // We can't query wl_output from here without a display connection,
        // so fall back to AWT which at least works on X11/XWayland
    }

    return try {
        val dpi = java.awt.Toolkit.getDefaultToolkit().screenResolution
        Density(dpi / 96f)
    } catch (e: Exception) {
        Density(1f)
    }
}

// ── Generic / fully-custom surface ────────────────────────────────────────────

/**
 * Create a Wayland layer-shell surface with full control over every parameter.
 *
 * @param config   The complete [WindowConfig].  Use [SurfacePresets] or build one from scratch.
 * @param binary   Where to find the wayland-helper binary.
 *                 Defaults to [BinarySource.Bundled] (extracted from the JAR).
 *                 Pass [BinarySource.Path] to use a binary you have installed yourself.
 * @param scope    Coroutine scope that owns the bridge lifetime.
 * @param content  Your composable UI content.
 */
suspend fun waylandSurface(
    config:  WindowConfig,
    binary:  BinarySource   = BinarySource.Bundled,
    scope:   CoroutineScope = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content: @Composable () -> Unit
): WaylandBridge {
    val bridge = WaylandBridge(scope, binary)
    bridge.configure(config, content)
    return bridge
}

suspend fun waylandDock(
    position:  ContentPosition = ContentPosition.BOTTOM,
    size:      Int             = 64,
    namespace: String          = "virdin-dock",
    density:   Density         = screenDensity(),
    binary:    BinarySource    = BinarySource.Bundled,
    scope:     CoroutineScope  = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:   @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config  = SurfacePresets.dock(position, size, namespace).copy(density = density),
    binary  = binary, scope = scope, content = content
)

suspend fun waylandPanel(
    position:  ContentPosition = ContentPosition.TOP,
    size:      Int             = 32,
    namespace: String          = "virdin-panel",
    density:   Density         = screenDensity(),
    binary:    BinarySource    = BinarySource.Bundled,
    scope:     CoroutineScope  = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:   @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config  = SurfacePresets.panel(position, size, namespace).copy(density = density),
    binary  = binary, scope = scope, content = content
)

suspend fun waylandDesktopBackground(
    namespace: String         = "virdin-background",
    density:   Density        = screenDensity(),
    binary:    BinarySource   = BinarySource.Bundled,
    scope:     CoroutineScope = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:   @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config  = SurfacePresets.desktopBackground(namespace).copy(density = density),
    binary  = binary, scope = scope, content = content
)

suspend fun waylandLockScreen(
    namespace: String         = "virdin-lockscreen",
    density:   Density        = screenDensity(),
    binary:    BinarySource   = BinarySource.Bundled,
    scope:     CoroutineScope = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:   @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config  = SurfacePresets.lockScreen(namespace).copy(density = density),
    binary  = binary, scope = scope, content = content
)

suspend fun waylandOsd(
    width:     Int            = 300,
    height:    Int            = 100,
    namespace: String         = "virdin-osd",
    density:   Density        = screenDensity(),
    binary:    BinarySource   = BinarySource.Bundled,
    scope:     CoroutineScope = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:   @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config  = SurfacePresets.osd(width, height, namespace).copy(density = density),
    binary  = binary, scope = scope, content = content
)

suspend fun waylandAppMenu(
    position:  ContentPosition = ContentPosition.BOTTOM,
    width:     Int             = 600,
    height:    Int             = 400,
    namespace: String          = "virdin-appmenu",
    density:   Density         = screenDensity(),
    binary:    BinarySource    = BinarySource.Bundled,
    scope:     CoroutineScope  = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:   @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config  = SurfacePresets.appMenu(position, width, height, namespace).copy(density = density),
    binary  = binary, scope = scope, content = content
)

// ── Convenience ───────────────────────────────────────────────────────────────

suspend fun WaylandBridge.awaitClose() {
    // Wait for RUNNING first (scene creation is async via invokeLater)
    while (state.value != WaylandBridge.BridgeState.RUNNING &&
        state.value != WaylandBridge.BridgeState.CLOSED  &&
        state.value != WaylandBridge.BridgeState.ERROR) delay(50)
    // Then wait until closed
    while (state.value != WaylandBridge.BridgeState.CLOSED) delay(100)
}

val LocalWaylandBridge: ProvidableCompositionLocal<WaylandBridge?> =
    staticCompositionLocalOf { null }