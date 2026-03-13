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
    val fromEnv = System.getenv("GDK_SCALE")?.toFloatOrNull()
        ?: System.getenv("QT_SCALE_FACTOR")?.toFloatOrNull()
    if (fromEnv != null && fromEnv > 0f) return Density(fromEnv)
    return try {
        val dpi = java.awt.Toolkit.getDefaultToolkit().screenResolution
        Density(dpi / 96f)
    } catch (e: Exception) {
        Density(1f)
    }
}

// ── Generic / fully-custom surface ───────────────────────────────────────────

/**
 * Create a Wayland layer-shell surface with full control over every parameter.
 *
 * @param config       The complete [WindowConfig].
 * @param binary       Where to find the wayland-helper binary.
 * @param sceneFactory Optional [VirdinSceneFactory]. Supply one if you hit the
 *                     module-boundary reflection error when consuming the library
 *                     as a JAR. See [VirdinSceneFactory] for a full example.
 *                     Defaults to `null` (library handles injection internally).
 * @param scope        Coroutine scope that owns the bridge lifetime.
 * @param content      Your composable UI content.
 */
suspend fun waylandSurface(
    config:       WindowConfig,
    binary:       BinarySource    = BinarySource.Bundled,
    sceneFactory: VirdinSceneFactory? = null,
    scope:        CoroutineScope  = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:      @Composable () -> Unit
): WaylandBridge {
    val bridge = WaylandBridge(scope, binary)
    bridge.configure(config, sceneFactory, content)
    return bridge
}

// ── Preset surfaces ───────────────────────────────────────────────────────────

suspend fun waylandDock(
    position:     ContentPosition    = ContentPosition.BOTTOM,
    size:         Int                = 64,
    margins:      Margins            = Margins.NONE,
    namespace:    String             = "virdin-dock",
    density:      Density            = screenDensity(),
    binary:       BinarySource       = BinarySource.Bundled,
    sceneFactory: VirdinSceneFactory? = null,
    scope:        CoroutineScope     = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:      @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config       = SurfacePresets.dock(position, size, margins, namespace).copy(density = density),
    binary       = binary,
    sceneFactory = sceneFactory,
    scope        = scope,
    content      = content
)

suspend fun waylandPanel(
    position:     ContentPosition    = ContentPosition.TOP,
    size:         Int                = 32,
    margins:      Margins            = Margins.NONE,
    namespace:    String             = "virdin-panel",
    density:      Density            = screenDensity(),
    binary:       BinarySource       = BinarySource.Bundled,
    sceneFactory: VirdinSceneFactory? = null,
    scope:        CoroutineScope     = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:      @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config       = SurfacePresets.panel(position, size, margins, namespace).copy(density = density),
    binary       = binary,
    sceneFactory = sceneFactory,
    scope        = scope,
    content      = content
)

suspend fun waylandDesktopBackground(
    namespace:    String             = "virdin-background",
    density:      Density            = screenDensity(),
    binary:       BinarySource       = BinarySource.Bundled,
    sceneFactory: VirdinSceneFactory? = null,
    scope:        CoroutineScope     = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:      @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config       = SurfacePresets.desktopBackground(namespace).copy(density = density),
    binary       = binary,
    sceneFactory = sceneFactory,
    scope        = scope,
    content      = content
)

suspend fun waylandLockScreen(
    namespace:    String             = "virdin-lockscreen",
    density:      Density            = screenDensity(),
    binary:       BinarySource       = BinarySource.Bundled,
    sceneFactory: VirdinSceneFactory? = null,
    scope:        CoroutineScope     = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:      @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config       = SurfacePresets.lockScreen(namespace).copy(density = density),
    binary       = binary,
    sceneFactory = sceneFactory,
    scope        = scope,
    content      = content
)

suspend fun waylandOsd(
    width:        Int                = 300,
    height:       Int                = 100,
    namespace:    String             = "virdin-osd",
    density:      Density            = screenDensity(),
    binary:       BinarySource       = BinarySource.Bundled,
    sceneFactory: VirdinSceneFactory? = null,
    scope:        CoroutineScope     = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:      @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config       = SurfacePresets.osd(width, height, namespace).copy(density = density),
    binary       = binary,
    sceneFactory = sceneFactory,
    scope        = scope,
    content      = content
)

suspend fun waylandAppMenu(
    position:     ContentPosition    = ContentPosition.BOTTOM,
    width:        Int                = 600,
    height:       Int                = 400,
    margins:      Margins            = Margins.NONE,
    namespace:    String             = "virdin-appmenu",
    density:      Density            = screenDensity(),
    binary:       BinarySource       = BinarySource.Bundled,
    sceneFactory: VirdinSceneFactory? = null,
    scope:        CoroutineScope     = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:      @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config       = SurfacePresets.appMenu(position, width, height, margins, namespace).copy(density = density),
    binary       = binary,
    sceneFactory = sceneFactory,
    scope        = scope,
    content      = content
)

// ── Utilities ─────────────────────────────────────────────────────────────────

suspend fun WaylandBridge.awaitClose() {
    while (state.value != WaylandBridge.BridgeState.RUNNING &&
        state.value != WaylandBridge.BridgeState.CLOSED  &&
        state.value != WaylandBridge.BridgeState.ERROR) delay(50)
    while (state.value != WaylandBridge.BridgeState.CLOSED) delay(100)
}

val LocalWaylandBridge: ProvidableCompositionLocal<WaylandBridge?> =
    staticCompositionLocalOf { null }