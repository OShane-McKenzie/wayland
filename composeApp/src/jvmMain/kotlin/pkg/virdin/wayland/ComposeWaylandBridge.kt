package natives.wayland

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.Toolkit
import javax.swing.SwingUtilities

class ComposeWaylandBridge(
    private val waylandManager: WaylandWindowManager
) {
    private var composeWindow: ComposeWindow? = null
    private var isConfigured = false
    private var eventLoopJob: Job? = null
    private var currentSurface: Pointer? = null
    private var currentLayerSurface: Pointer? = null

    fun configureAsDock(
        window: ComposeWindow,
        coroutineScope: CoroutineScope,
        position: ContentPosition = ContentPosition.BOTTOM,
        size: Int = 64,
        namespace: String
    ) {
        val (anchor, x, y, width, height) = when (position) {
            ContentPosition.BOTTOM -> {
                val screenSize = Toolkit.getDefaultToolkit().screenSize
                Tuple5(
                    LayerShellProtocol.ANCHOR_BOTTOM or LayerShellProtocol.ANCHOR_LEFT or LayerShellProtocol.ANCHOR_RIGHT,
                    0, screenSize.height - size, screenSize.width, size
                )
            }
            ContentPosition.TOP -> {
                val screenSize = Toolkit.getDefaultToolkit().screenSize
                Tuple5(
                    LayerShellProtocol.ANCHOR_TOP or LayerShellProtocol.ANCHOR_LEFT or LayerShellProtocol.ANCHOR_RIGHT,
                    0, 0, screenSize.width, size
                )
            }
            ContentPosition.LEFT -> {
                val screenSize = Toolkit.getDefaultToolkit().screenSize
                Tuple5(
                    LayerShellProtocol.ANCHOR_LEFT or LayerShellProtocol.ANCHOR_TOP or LayerShellProtocol.ANCHOR_BOTTOM,
                    0, 0, size, screenSize.height
                )
            }
            ContentPosition.RIGHT -> {
                val screenSize = Toolkit.getDefaultToolkit().screenSize
                Tuple5(
                    LayerShellProtocol.ANCHOR_RIGHT or LayerShellProtocol.ANCHOR_TOP or LayerShellProtocol.ANCHOR_BOTTOM,
                    screenSize.width - size, 0, size, screenSize.height
                )
            }
        }

        configureWindow(
            window = window,
            coroutineScope = coroutineScope,
            layer = LayerShellProtocol.LAYER_TOP,
            anchor = anchor,
            exclusiveZone = size,
            namespace = namespace,
            x = x, y = y, width = width, height = height,
            keyboardInteractivity = LayerShellProtocol.KEYBOARD_INTERACTIVITY_ON_DEMAND
        )
    }

    fun configureAsPanel(
        window: ComposeWindow,
        coroutineScope: CoroutineScope,
        position: ContentPosition = ContentPosition.TOP,
        size: Int = 32,
        namespace: String
    ) {
        val (anchor, x, y, width, height) = when (position) {
            ContentPosition.TOP -> {
                val screenSize = Toolkit.getDefaultToolkit().screenSize
                Tuple5(
                    LayerShellProtocol.ANCHOR_TOP or LayerShellProtocol.ANCHOR_LEFT or LayerShellProtocol.ANCHOR_RIGHT,
                    0, 0, screenSize.width, size
                )
            }
            ContentPosition.BOTTOM -> {
                val screenSize = Toolkit.getDefaultToolkit().screenSize
                Tuple5(
                    LayerShellProtocol.ANCHOR_BOTTOM or LayerShellProtocol.ANCHOR_LEFT or LayerShellProtocol.ANCHOR_RIGHT,
                    0, screenSize.height - size, screenSize.width, size
                )
            }
            ContentPosition.LEFT -> {
                val screenSize = Toolkit.getDefaultToolkit().screenSize
                Tuple5(
                    LayerShellProtocol.ANCHOR_LEFT or LayerShellProtocol.ANCHOR_TOP or LayerShellProtocol.ANCHOR_BOTTOM,
                    0, 0, size, screenSize.height
                )
            }
            ContentPosition.RIGHT -> {
                val screenSize = Toolkit.getDefaultToolkit().screenSize
                Tuple5(
                    LayerShellProtocol.ANCHOR_RIGHT or LayerShellProtocol.ANCHOR_TOP or LayerShellProtocol.ANCHOR_BOTTOM,
                    screenSize.width - size, 0, size, screenSize.height
                )
            }
        }

        configureWindow(
            window = window,
            coroutineScope = coroutineScope,
            layer = LayerShellProtocol.LAYER_TOP,
            anchor = anchor,
            exclusiveZone = size,
            namespace = namespace,
            x = x, y = y, width = width, height = height,
            keyboardInteractivity = LayerShellProtocol.KEYBOARD_INTERACTIVITY_NONE
        )
    }

    fun configureAsDesktopBackground(
        window: ComposeWindow,
        coroutineScope: CoroutineScope,
        namespace: String
    ) {
        val screenSize = Toolkit.getDefaultToolkit().screenSize

        configureWindow(
            window = window,
            coroutineScope = coroutineScope,
            layer = LayerShellProtocol.LAYER_BACKGROUND,
            anchor = LayerShellProtocol.ANCHOR_TOP or
                    LayerShellProtocol.ANCHOR_BOTTOM or
                    LayerShellProtocol.ANCHOR_LEFT or
                    LayerShellProtocol.ANCHOR_RIGHT,
            exclusiveZone = 0,
            namespace = namespace,
            x = 0, y = 0,
            width = screenSize.width,
            height = screenSize.height,
            keyboardInteractivity = LayerShellProtocol.KEYBOARD_INTERACTIVITY_NONE
        )
    }

    fun configureAsLockScreen(
        window: ComposeWindow,
        coroutineScope: CoroutineScope,
        namespace: String
    ) {
        val screenSize = Toolkit.getDefaultToolkit().screenSize

        configureWindow(
            window = window,
            coroutineScope = coroutineScope,
            layer = LayerShellProtocol.LAYER_OVERLAY,
            anchor = LayerShellProtocol.ANCHOR_TOP or
                    LayerShellProtocol.ANCHOR_BOTTOM or
                    LayerShellProtocol.ANCHOR_LEFT or
                    LayerShellProtocol.ANCHOR_RIGHT,
            exclusiveZone = -1, // Takes all input
            namespace = namespace,
            x = 0, y = 0,
            width = screenSize.width,
            height = screenSize.height,
            keyboardInteractivity = LayerShellProtocol.KEYBOARD_INTERACTIVITY_EXCLUSIVE
        )
    }

    fun configureAsOSD(
        window: ComposeWindow,
        coroutineScope: CoroutineScope,
        width: Int = 300,
        height: Int = 100,
        namespace: String
    ) {
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val x = (screenSize.width - width) / 2
        val y = screenSize.height / 4 // Top quarter of screen

        configureWindow(
            window = window,
            coroutineScope = coroutineScope,
            layer = LayerShellProtocol.LAYER_OVERLAY,
            anchor = 0, // No anchoring - centered
            exclusiveZone = 0,
            namespace = namespace,
            x = x, y = y,
            width = width,
            height = height,
            keyboardInteractivity = LayerShellProtocol.KEYBOARD_INTERACTIVITY_NONE
        )
    }

    private fun configureWindow(
        window: ComposeWindow,
        coroutineScope: CoroutineScope,
        layer: Int,
        anchor: Int,
        exclusiveZone: Int,
        namespace: String,
        x: Int, y: Int, width: Int, height: Int,
        keyboardInteractivity: Int
    ) {
        this.composeWindow = window

        if (!waylandManager.isInitialized) {
            println("✗ Wayland manager not initialized")
            return
        }

        val surface = waylandManager.createSurface()
        if (surface == null || surface == Pointer.NULL) {
            println("✗ Failed to create Wayland surface")
            return
        }
        currentSurface = surface

        val layerSurface = waylandManager.createLayerSurface(
            surface = surface,
            layer = layer,
            anchor = anchor,
            exclusiveZone = exclusiveZone,
            namespace = namespace
        )

        if (layerSurface != null && layerSurface != Pointer.NULL) {
            currentLayerSurface = layerSurface

            // Set keyboard interactivity
            waylandManager.setLayerSurfaceKeyboardInteractivity(layerSurface, keyboardInteractivity)

            println("✓ Configured Compose window as Wayland $namespace")

            SwingUtilities.invokeLater {
                window.size = Dimension(width, height)
                window.setLocation(x, y)
            }

            isConfigured = true
            startEventLoop(coroutineScope)
        }
    }

    private fun startEventLoop(scope: CoroutineScope) {
        eventLoopJob = scope.launch(Dispatchers.IO) {
            println("✓ Started Wayland event loop")
            while (isActive && isConfigured) {
                try {
                    waylandManager.dispatch()
                    waylandManager.flush()
                    delay(16)
                } catch (e: Exception) {
                    println("Event loop error: ${e.message}")
                }
            }
        }
    }

    fun setDockSize(size: Int) {
        composeWindow?.let { window ->
            SwingUtilities.invokeLater {
                val screenSize = Toolkit.getDefaultToolkit().screenSize
                window.size = Dimension(screenSize.width, size)
                window.setLocation(0, screenSize.height - size)
            }
        }
    }

    fun cleanup() {
        isConfigured = false
        eventLoopJob?.cancel()
    }
}

// Helper data class
private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)