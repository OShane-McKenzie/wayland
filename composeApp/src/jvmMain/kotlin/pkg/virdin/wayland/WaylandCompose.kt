package pkg.virdin.wayland

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.awt.Dimension
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window
import javax.swing.SwingUtilities


/**
 * High-level Compose Desktop helpers for integrating with native Wayland
 * layer-shell protocols.
 *
 * This object provides composable window builders that automatically:
 * - Detect whether the application is running on native Wayland
 * - Configure windows using `zwlr_layer_shell_v1` when available
 * - Gracefully fall back to standard X11/XWayland window positioning
 *
 * These APIs are designed for building desktop-environment components such as:
 * docks, panels, lock screens, backgrounds, and on-screen displays (OSDs)
 * using Compose Desktop.
 */
object WaylandCompose {

    /**
     * Creates and remembers a [ComposeWaylandBridge] for the current Compose
     * lifecycle.
     *
     * This function:
     * - Detects whether the application is running on native Wayland
     * - Initializes the Wayland connection
     * - Verifies support for the layer-shell protocol
     *
     * If any step fails (not Wayland, initialization failure, or missing protocol),
     * this function returns `null` and the application should fall back to
     * traditional window positioning.
     *
     * The bridge is automatically cleaned up when the composable leaves the
     * composition.
     *
     * @return a [ComposeWaylandBridge] if Wayland layer-shell is available,
     *         or `null` otherwise.
     */
    @Composable
    fun rememberComposeWaylandBridge(): ComposeWaylandBridge? {
        val nativeWayland = remember { NativeWaylandCalls() }
        val isWayland = nativeWayland.isWaylandAvailable()

        println("Environment: ${if (isWayland) "Wayland" else "X11/XWayland"}")

        val waylandBridge = remember {
            if (isWayland) {
                if (nativeWayland.initialize()) {
                    val manager = nativeWayland.getManager()
                    if (manager?.isLayerShellSupported() == true) {
                        println("✓ Layer shell protocol supported")
                        ComposeWaylandBridge(manager)
                    } else {
                        println("✗ Layer shell protocol not supported by compositor")
                        null
                    }
                } else {
                    println("✗ Failed to initialize Wayland connection")
                    null
                }
            } else {
                println("! Not running on native Wayland, using fallback mode")
                null
            }
        }

        // Cleanup on dispose
        DisposableEffect(waylandBridge) {
            onDispose {
                waylandBridge?.cleanup()
                nativeWayland.cleanup()
            }
        }

        return waylandBridge
    }

    /**
     * Creates a dock-style window anchored to a screen edge using Wayland
     * layer-shell.
     *
     * On native Wayland:
     * - The window is configured as a layer-shell surface
     * - Anchored to the specified edge
     * - Given a fixed thickness (`size`)
     *
     * On X11/XWayland:
     * - The window falls back to manual size and position calculation
     *
     * Typical use cases:
     * - Application docks
     * - Launchers
     * - Persistent toolbars
     *
     * @param bridge Wayland bridge obtained from [rememberComposeWaylandBridge],
     *               or `null` to enable fallback behavior.
     * @param windowState Compose window state.
     * @param namespace Unique layer-shell namespace used by the compositor.
     * @param title Window title (mostly ignored by compositors).
     * @param position Screen edge to anchor the dock to.
     * @param size Thickness of the dock in pixels.
     * @param undecorated Whether the window should have no system decorations.
     * @param transparent Whether the window background is transparent.
     * @param alwaysOnTop Whether the window stays above normal application windows.
     * @param resizable Whether the user can resize the window.
     * @param onClose Callback invoked when the window is closed.
     * @param content Composable content rendered inside the dock.
     */
    @Composable
    fun WaylandDockWindow(
        bridge: ComposeWaylandBridge?,
        windowState: WindowState,
        namespace: String = "virdin-dock",
        title: String = "Dock",
        position: ContentPosition = ContentPosition.BOTTOM,
        size: Int = 64,
        undecorated: Boolean = true,
        transparent: Boolean = true,
        alwaysOnTop: Boolean = true,
        resizable: Boolean = false,
        onClose: () -> Unit = {},
        content: @Composable () -> Unit
    ) {
        val scope = rememberCoroutineScope()

        Window(
            onCloseRequest = {
                bridge?.cleanup()
                onClose()
            },
            state = windowState,
            title = title,
            undecorated = undecorated,
            transparent = transparent,
            alwaysOnTop = alwaysOnTop,
            resizable = resizable
        ) {
            LaunchedEffect(Unit) {
                val window = this@Window.window
                if (bridge != null) {
                    delay(100)
                    bridge.configureAsDock(
                        window = window,
                        coroutineScope = scope,
                        position = position,
                        size = size,
                        namespace = namespace
                    )
                } else {
                    // Fallback for X11/XWayland
                    SwingUtilities.invokeLater {
                        val screenSize = Toolkit.getDefaultToolkit().screenSize
                        val (width, height, x, y) = when (position) {
                            ContentPosition.BOTTOM -> {
                                Dimension4(screenSize.width, size, 0, screenSize.height - size)
                            }
                            ContentPosition.TOP -> {
                                Dimension4(screenSize.width, size, 0, 0)
                            }
                            ContentPosition.LEFT -> {
                                Dimension4(size, screenSize.height, 0, 0)
                            }
                            ContentPosition.RIGHT -> {
                                Dimension4(size, screenSize.height, screenSize.width - size, 0)
                            }
                        }
                        window.size = Dimension(width, height)
                        window.setLocation(x, y)
                    }
                }
            }

            content()
        }
    }

    /**
     * Creates a panel or taskbar window using Wayland layer-shell.
     *
     * Panels are typically thinner than docks and used for:
     * - Top bars
     * - Taskbars
     * - Status bars
     *
     * Behavior mirrors [WaylandDockWindow], but with defaults more suitable
     * for panel-style UI elements.
     *
     * @param bridge Wayland bridge or `null` for fallback behavior.
     * @param windowState Compose window state.
     * @param namespace Layer-shell namespace.
     * @param title Window title.
     * @param position Screen edge to anchor the panel to.
     * @param size Panel thickness in pixels.
     * @param undecorated Remove window decorations.
     * @param transparent Whether the panel background is transparent.
     * @param alwaysOnTop Keep the panel above regular windows.
     * @param resizable Whether the panel can be resized.
     * @param onClose Callback invoked when the window is closed.
     * @param content Composable content rendered inside the panel.
     */
    @Composable
    fun WaylandPanelWindow(
        bridge: ComposeWaylandBridge?,
        windowState: WindowState,
        namespace: String = "virdin-panel",
        title: String = "Panel",
        position: ContentPosition = ContentPosition.TOP,
        size: Int = 32,
        undecorated: Boolean = true,
        transparent: Boolean = false,
        alwaysOnTop: Boolean = true,
        resizable: Boolean = false,
        onClose: () -> Unit = {},
        content: @Composable () -> Unit
    ) {
        val scope = rememberCoroutineScope()

        Window(
            onCloseRequest = {
                bridge?.cleanup()
                onClose()
            },
            state = windowState,
            title = title,
            undecorated = undecorated,
            transparent = transparent,
            alwaysOnTop = alwaysOnTop,
            resizable = resizable
        ) {
            LaunchedEffect(Unit) {
                val window = this@Window.window
                if (bridge != null) {
                    delay(100)
                    bridge.configureAsPanel(
                        window = window,
                        coroutineScope = scope,
                        position = position,
                        size = size,
                        namespace = namespace
                    )
                } else {
                    // Fallback for X11/XWayland
                    SwingUtilities.invokeLater {
                        val screenSize = Toolkit.getDefaultToolkit().screenSize
                        val (width, height, x, y) = when (position) {
                            ContentPosition.TOP -> {
                                Dimension4(screenSize.width, size, 0, 0)
                            }
                            ContentPosition.BOTTOM -> {
                                Dimension4(screenSize.width, size, 0, screenSize.height - size)
                            }
                            ContentPosition.LEFT -> {
                                Dimension4(size, screenSize.height, 0, 0)
                            }
                            ContentPosition.RIGHT -> {
                                Dimension4(size, screenSize.height, screenSize.width - size, 0)
                            }
                        }
                        window.size = Dimension(width, height)
                        window.setLocation(x, y)
                    }
                }
            }

            content()
        }
    }

    /**
     * Creates a desktop background window.
     *
     * On native Wayland:
     * - The window is configured as a background layer-shell surface
     * - It occupies the full screen
     * - Other layer-shell surfaces (panels, docks, OSDs) can appear above it
     *
     * On X11/XWayland:
     * - The window is maximized to full screen
     *
     * Typical use cases:
     * - Desktop backgrounds
     * - Wallpaper renderers
     * - Desktop widgets
     *
     * @param bridge Wayland bridge or `null` for fallback behavior.
     * @param windowState Compose window state.
     * @param namespace Layer-shell namespace.
     * @param title Window title.
     * @param undecorated Remove window decorations.
     * @param transparent Whether the background is transparent.
     * @param alwaysOnTop Whether the background should stay above other windows
     *                    (normally false).
     * @param resizable Whether the window can be resized.
     * @param onClose Callback invoked when the window is closed.
     * @param content Composable content rendered as the desktop background.
     */
    @Composable
    fun WaylandBackgroundWindow(
        bridge: ComposeWaylandBridge?,
        windowState: WindowState,
        namespace: String = "virdin-background",
        title: String = "Desktop Background",
        undecorated: Boolean = true,
        transparent: Boolean = false,
        alwaysOnTop: Boolean = false,
        resizable: Boolean = false,
        onClose: () -> Unit = {},
        content: @Composable () -> Unit
    ) {
        val scope = rememberCoroutineScope()

        Window(
            onCloseRequest = {
                bridge?.cleanup()
                onClose()
            },
            state = windowState,
            title = title,
            undecorated = undecorated,
            transparent = transparent,
            alwaysOnTop = alwaysOnTop,
            resizable = resizable
        ) {
            LaunchedEffect(Unit) {
                val window = this@Window.window
                if (bridge != null) {
                    delay(100)
                    bridge.configureAsDesktopBackground(
                        window = window,
                        coroutineScope = scope,
                        namespace = namespace
                    )
                } else {
                    // Fallback for X11/XWayland
                    SwingUtilities.invokeLater {
                        val screenSize = Toolkit.getDefaultToolkit().screenSize
                        window.size = Dimension(screenSize.width, screenSize.height)
                        window.setLocation(0, 0)
                        window.extendedState = Frame.MAXIMIZED_BOTH
                    }
                }
            }

            content()
        }
    }

    /**
     * Creates a lock-screen style window.
     *
     * On native Wayland:
     * - Configured as a layer-shell surface intended to block user interaction
     * - Occupies the entire screen
     *
     * On X11/XWayland:
     * - Falls back to a maximized undecorated window
     *
     * Note:
     * This API does not enforce security by itself. Actual input blocking and
     * authentication must be handled by the application.
     *
     * @param bridge Wayland bridge or `null` for fallback behavior.
     * @param windowState Compose window state.
     * @param namespace Layer-shell namespace.
     * @param title Window title.
     * @param undecorated Remove window decorations.
     * @param transparent Whether the lock screen background is transparent.
     * @param alwaysOnTop Keep the lock screen above all other windows.
     * @param resizable Whether the window can be resized.
     * @param onClose Callback invoked when the window is closed.
     * @param content Composable content rendered inside the lock screen.
     */
    @Composable
    fun WaylandLockScreenWindow(
        bridge: ComposeWaylandBridge?,
        windowState: WindowState,
        namespace: String = "virdin-lockscreen",
        title: String = "Lock Screen",
        undecorated: Boolean = true,
        transparent: Boolean = false,
        alwaysOnTop: Boolean = true,
        resizable: Boolean = false,
        onClose: () -> Unit = {},
        content: @Composable () -> Unit
    ) {
        val scope = rememberCoroutineScope()

        Window(
            onCloseRequest = {
                bridge?.cleanup()
                onClose()
            },
            state = windowState,
            title = title,
            undecorated = undecorated,
            transparent = transparent,
            alwaysOnTop = alwaysOnTop,
            resizable = resizable
        ) {
            LaunchedEffect(Unit) {
                val window = this@Window.window
                if (bridge != null) {
                    delay(100)
                    bridge.configureAsLockScreen(
                        window = window,
                        coroutineScope = scope,
                        namespace = namespace
                    )
                } else {
                    // Fallback for X11/XWayland
                    SwingUtilities.invokeLater {
                        val screenSize = Toolkit.getDefaultToolkit().screenSize
                        window.size = Dimension(screenSize.width, screenSize.height)
                        window.setLocation(0, 0)
                        window.extendedState = Frame.MAXIMIZED_BOTH
                    }
                }
            }

            content()
        }
    }

    /**
     * Creates an On-Screen Display (OSD) window.
     *
     * OSD windows are typically:
     * - Small
     * - Temporarily visible
     * - Positioned near the top or center of the screen
     *
     * Common use cases:
     * - Volume indicators
     * - Brightness indicators
     * - Notifications
     *
     * On Wayland, the compositor controls final placement.
     * On X11/XWayland, manual positioning is applied.
     *
     * @param bridge Wayland bridge or `null` for fallback behavior.
     * @param windowState Compose window state.
     * @param namespace Layer-shell namespace.
     * @param title Window title.
     * @param width Desired window width in pixels.
     * @param height Desired window height in pixels.
     * @param centerHorizontal Whether to horizontally center the window.
     * @param centerVertical Whether to vertically center the window.
     * @param offsetY Vertical offset when not vertically centered.
     * @param undecorated Remove window decorations.
     * @param transparent Whether the window background is transparent.
     * @param alwaysOnTop Keep the OSD above other windows.
     * @param resizable Whether the window can be resized.
     * @param onClose Callback invoked when the window is closed.
     * @param content Composable content rendered inside the OSD.
     */
    @Composable
    fun WaylandOSDWindow(
        bridge: ComposeWaylandBridge?,
        windowState: WindowState,
        namespace: String = "virdin-osd",
        title: String = "OSD",
        width: Int = 300,
        height: Int = 100,
        centerHorizontal: Boolean = true,
        centerVertical: Boolean = false,
        offsetY: Int = 100, // Offset from top when centerVertical is false
        undecorated: Boolean = true,
        transparent: Boolean = true,
        alwaysOnTop: Boolean = true,
        resizable: Boolean = false,
        onClose: () -> Unit = {},
        content: @Composable () -> Unit
    ) {
        val scope = rememberCoroutineScope()

        Window(
            onCloseRequest = {
                bridge?.cleanup()
                onClose()
            },
            state = windowState,
            title = title,
            undecorated = undecorated,
            transparent = transparent,
            alwaysOnTop = alwaysOnTop,
            resizable = resizable
        ) {
            LaunchedEffect(Unit) {
                val window = this@Window.window
                if (bridge != null) {
                    delay(100)
                    bridge.configureAsOSD(
                        window = window,
                        coroutineScope = scope,
                        width = width,
                        height = height,
                        namespace = namespace
                    )
                } else {
                    // Fallback for X11/XWayland
                    SwingUtilities.invokeLater {
                        val screenSize = Toolkit.getDefaultToolkit().screenSize
                        val x = if (centerHorizontal) {
                            (screenSize.width - width) / 2
                        } else {
                            (screenSize.width - width) / 2 // Default to center anyway
                        }
                        val y = if (centerVertical) {
                            (screenSize.height - height) / 2
                        } else {
                            offsetY
                        }
                        window.size = Dimension(width, height)
                        window.setLocation(x, y)
                    }
                }
            }

            content()
        }
    }

    /**
     * Generic Wayland-aware window builder with full control over configuration.
     *
     * This API is intended for advanced use cases where the predefined helpers
     * (dock, panel, OSD, etc.) are insufficient.
     *
     * On native Wayland:
     * - The provided [configure] lambda is invoked with direct access to the
     *   [ComposeWaylandBridge]
     *
     * On X11/XWayland:
     * - The [fallback] lambda is invoked instead
     *
     * @param bridge Wayland bridge or `null` for fallback behavior.
     * @param windowState Compose window state.
     * @param namespace Layer-shell namespace.
     * @param title Window title.
     * @param undecorated Remove window decorations.
     * @param transparent Whether the window background is transparent.
     * @param alwaysOnTop Keep the window above other windows.
     * @param resizable Whether the window can be resized.
     * @param onClose Callback invoked when the window is closed.
     * @param configure Suspend function used to configure the window on Wayland.
     * @param fallback Function used to configure the window on X11/XWayland.
     * @param content Composable content rendered inside the window.
     */
    @Composable
    fun WaylandWindow(
        bridge: ComposeWaylandBridge?,
        windowState: WindowState,
        namespace: String,
        title: String = "",
        undecorated: Boolean = true,
        transparent: Boolean = false,
        alwaysOnTop: Boolean = true,
        resizable: Boolean = false,
        onClose: () -> Unit = {},
        configure: suspend (window: Window, bridge: ComposeWaylandBridge, scope: CoroutineScope) -> Unit,
        fallback: (window: Window) -> Unit = {},
        content: @Composable () -> Unit
    ) {
        val scope = rememberCoroutineScope()

        Window(
            onCloseRequest = {
                bridge?.cleanup()
                onClose()
            },
            state = windowState,
            title = title,
            undecorated = undecorated,
            transparent = transparent,
            alwaysOnTop = alwaysOnTop,
            resizable = resizable
        ) {
            LaunchedEffect(Unit) {
                val window = this@Window.window
                if (bridge != null) {
                    delay(100)
                    configure(window, bridge, scope)
                } else {
                    SwingUtilities.invokeLater {
                        fallback(window)
                    }
                }
            }

            content()
        }
    }

    /**
     * Creates a WindowState for dock windows (bottom, top, left, right).
     */
    @Composable
    fun rememberDockWindowState() = rememberWindowState(
        placement = WindowPlacement.Floating,
        position = WindowPosition.Absolute(0.dp, 0.dp),
        size = DpSize.Unspecified
    )

    /**
     * Creates a WindowState for panel windows (taskbar, top bar).
     */
    @Composable
    fun rememberPanelWindowState() = rememberWindowState(
        placement = WindowPlacement.Floating,
        position = WindowPosition.Aligned(Alignment.TopCenter),
        size = DpSize.Unspecified
    )

    /**
     * Creates a WindowState for desktop background windows.
     */
    @Composable
    fun rememberBackgroundWindowState() = rememberWindowState(
        placement = WindowPlacement.Maximized,
        position = WindowPosition.Aligned(Alignment.Center),
        size = DpSize.Unspecified
    )

    /**
     * Creates a WindowState for lock screen windows.
     */
    @Composable
    fun rememberLockScreenWindowState() = rememberWindowState(
        placement = WindowPlacement.Fullscreen,
        position = WindowPosition.Aligned(Alignment.Center),
        size = DpSize.Unspecified
    )

    /**
     * Creates a WindowState for OSD windows (notifications, indicators).
     * @param width Width of the OSD in dp
     * @param height Height of the OSD in dp
     * @param alignment Position alignment (default: TopCenter)
     */
    @Composable
    fun rememberOSDWindowState(
        width: Int = 300,
        height: Int = 100,
        alignment: Alignment = Alignment.TopCenter
    ) = rememberWindowState(
        placement = WindowPlacement.Floating,
        position = WindowPosition.Aligned(alignment),
        size = DpSize(width.dp, height.dp)
    )

    /**
     * Creates a WindowState for left side panel windows.
     */
    @Composable
    fun rememberLeftPanelWindowState() = rememberWindowState(
        placement = WindowPlacement.Floating,
        position = WindowPosition.Absolute(0.dp, 0.dp),
        size = DpSize.Unspecified
    )

    /**
     * Creates a WindowState for right side panel windows.
     */
    @Composable
    fun rememberRightPanelWindowState() = rememberWindowState(
        placement = WindowPlacement.Floating,
        position = WindowPosition.Aligned(Alignment.TopEnd),
        size = DpSize.Unspecified
    )
    private data class Dimension4(val width: Int, val height: Int, val x: Int, val y: Int)
}