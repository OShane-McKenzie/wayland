package pkg.virdin.wayland

import androidx.compose.runtime.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing

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

suspend fun waylandSurface(
    config:       WindowConfig,
    binary:       BinarySource        = BinarySource.Bundled,
    sceneFactory: VirdinSceneFactory? = null,
    scope:        CoroutineScope      = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:      @Composable () -> Unit
): WaylandBridge {
    val bridge = WaylandBridge(scope, binary)
    bridge.configure(config, sceneFactory, content)
    return bridge
}

suspend fun waylandDock(
    position:     ContentPosition     = ContentPosition.BOTTOM,
    size:         Int                 = 64,
    margins:      Margins             = Margins.NONE,
    namespace:    String              = "virdin-dock",
    density:      Density             = screenDensity(),
    binary:       BinarySource        = BinarySource.Bundled,
    sceneFactory: VirdinSceneFactory? = null,
    scope:        CoroutineScope      = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:      @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config       = SurfacePresets.dock(position, size, margins, namespace).copy(density = density),
    binary       = binary,
    sceneFactory = sceneFactory,
    scope        = scope,
    content      = content
)

suspend fun waylandPanel(
    position:     ContentPosition     = ContentPosition.TOP,
    size:         Int                 = 32,
    margins:      Margins             = Margins.NONE,
    namespace:    String              = "virdin-panel",
    density:      Density             = screenDensity(),
    binary:       BinarySource        = BinarySource.Bundled,
    sceneFactory: VirdinSceneFactory? = null,
    scope:        CoroutineScope      = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:      @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config       = SurfacePresets.panel(position, size, margins, namespace).copy(density = density),
    binary       = binary,
    sceneFactory = sceneFactory,
    scope        = scope,
    content      = content
)

suspend fun waylandDesktopBackground(
    namespace:    String              = "virdin-background",
    density:      Density             = screenDensity(),
    binary:       BinarySource        = BinarySource.Bundled,
    sceneFactory: VirdinSceneFactory? = null,
    scope:        CoroutineScope      = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:      @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config       = SurfacePresets.desktopBackground(namespace).copy(density = density),
    binary       = binary,
    sceneFactory = sceneFactory,
    scope        = scope,
    content      = content
)

suspend fun waylandLockScreen(
    namespace:    String              = "virdin-lockscreen",
    density:      Density             = screenDensity(),
    binary:       BinarySource        = BinarySource.Bundled,
    sceneFactory: VirdinSceneFactory? = null,
    scope:        CoroutineScope      = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:      @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config       = SurfacePresets.lockScreen(namespace).copy(density = density),
    binary       = binary,
    sceneFactory = sceneFactory,
    scope        = scope,
    content      = content
)

suspend fun waylandOsd(
    width:        Int                 = 300,
    height:       Int                 = 100,
    namespace:    String              = "virdin-osd",
    density:      Density             = screenDensity(),
    binary:       BinarySource        = BinarySource.Bundled,
    sceneFactory: VirdinSceneFactory? = null,
    scope:        CoroutineScope      = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:      @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config       = SurfacePresets.osd(width, height, namespace).copy(density = density),
    binary       = binary,
    sceneFactory = sceneFactory,
    scope        = scope,
    content      = content
)

suspend fun waylandAppMenu(
    position:     ContentPosition     = ContentPosition.BOTTOM,
    width:        Int                 = 600,
    height:       Int                 = 400,
    margins:      Margins             = Margins.NONE,
    namespace:    String              = "virdin-appmenu",
    density:      Density             = screenDensity(),
    binary:       BinarySource        = BinarySource.Bundled,
    sceneFactory: VirdinSceneFactory? = null,
    scope:        CoroutineScope      = CoroutineScope(Dispatchers.Swing + SupervisorJob()),
    content:      @Composable () -> Unit
): WaylandBridge = waylandSurface(
    config       = SurfacePresets.appMenu(position, width, height, margins, namespace).copy(density = density),
    binary       = binary,
    sceneFactory = sceneFactory,
    scope        = scope,
    content      = content
)

suspend fun WaylandBridge.awaitClose() {
    while (state.value != WaylandBridge.BridgeState.RUNNING &&
        state.value != WaylandBridge.BridgeState.CLOSED  &&
        state.value != WaylandBridge.BridgeState.ERROR) delay(50)
    while (state.value != WaylandBridge.BridgeState.CLOSED) delay(100)
}

val LocalWaylandBridge: ProvidableCompositionLocal<WaylandBridge?> =
    staticCompositionLocalOf { null }