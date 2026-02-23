package pkg.virdin.wayland

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope

// ── Screen geometry ───────────────────────────────────────────────────────────

/**
 * Physical screen dimensions in logical pixels.
 * Obtain via your preferred method (read /sys, run `wlr-randr`, parse
 * `xrandr --current`, etc.) and pass to [ContextMenuConfig].
 */
data class ScreenSize(val width: Float, val height: Float)

// ── Context menu anchor ───────────────────────────────────────────────────────

/**
 * Which corner of the menu is pinned to the cursor position.
 * Auto-selected by [ContextMenuConfig.resolveAnchor] based on available space.
 */
enum class MenuAnchor {
    TOP_LEFT,      // menu opens right-and-down from cursor (most common)
    TOP_RIGHT,     // menu opens left-and-down  (near right edge)
    BOTTOM_LEFT,   // menu opens right-and-up   (near bottom edge)
    BOTTOM_RIGHT,  // menu opens left-and-up    (near bottom-right corner)
}

data class Margins(
    val top:    Int = 0,
    val bottom: Int = 0,
    val left:   Int = 0,
    val right:  Int = 0
) {
    companion object {
        val NONE = Margins()
        fun all(px: Int) = Margins(px, px, px, px)
        fun horizontal(px: Int) = Margins(left = px, right = px)
        fun vertical(px: Int) = Margins(top = px, bottom = px)
    }
}

// ── Config ────────────────────────────────────────────────────────────────────

/**
 * Everything needed to position and size a context menu on screen.
 *
 * @param cursorX     Cursor X in logical pixels.
 * @param cursorY     Cursor Y in logical pixels.
 * @param menuWidth   Desired menu width in logical pixels.
 * @param menuHeight  Desired menu height in logical pixels.
 * @param screen      Physical screen dimensions.
 * @param padding     Minimum gap between menu edge and screen edge (default 8px).
 */
data class ContextMenuConfig(
    val cursorX:    Float,
    val cursorY:    Float,
    val menuWidth:  Float,
    val menuHeight: Float,
    val screen:     ScreenSize,
    val padding:    Float = 8f
) {
    /**
     * Automatically selects the best [MenuAnchor] based on available space
     * around the cursor so the menu never goes off-screen.
     */
    val resolvedAnchor: MenuAnchor get() {
        val spaceRight  = screen.width  - cursorX
        val spaceBottom = screen.height - cursorY
        val fitsRight   = spaceRight  >= menuWidth  + padding
        val fitsDown    = spaceBottom >= menuHeight + padding
        return when {
            fitsRight  && fitsDown  -> MenuAnchor.TOP_LEFT
            !fitsRight && fitsDown  -> MenuAnchor.TOP_RIGHT
            fitsRight  && !fitsDown -> MenuAnchor.BOTTOM_LEFT
            else                    -> MenuAnchor.BOTTOM_RIGHT
        }
    }

    /**
     * Converts this config into a [WindowConfig] with the correct anchor
     * bitfield and margins so the compositor places the surface exactly at
     * the cursor position.
     *
     * Layer-shell margins are distances from screen edges, so we compute
     * them from cursor position and menu size based on the resolved anchor.
     */
    fun toWindowConfig(namespace: String = "virdin-contextmenu"): WindowConfig {
        val w = menuWidth.toInt()
        val h = menuHeight.toInt()

        val (waylandAnchor, margins) = when (resolvedAnchor) {
            MenuAnchor.TOP_LEFT -> Pair(
                Anchor.TOP or Anchor.LEFT,
                Margins(
                    top   = cursorY.toInt(),
                    left  = cursorX.toInt()
                )
            )
            MenuAnchor.TOP_RIGHT -> Pair(
                Anchor.TOP or Anchor.RIGHT,
                Margins(
                    top   = cursorY.toInt(),
                    right = (screen.width - cursorX).toInt()
                )
            )
            MenuAnchor.BOTTOM_LEFT -> Pair(
                Anchor.BOTTOM or Anchor.LEFT,
                Margins(
                    bottom = (screen.height - cursorY).toInt(),
                    left   = cursorX.toInt()
                )
            )
            MenuAnchor.BOTTOM_RIGHT -> Pair(
                Anchor.BOTTOM or Anchor.RIGHT,
                Margins(
                    bottom = (screen.height - cursorY).toInt(),
                    right  = (screen.width - cursorX).toInt()
                )
            )
        }

        return WindowConfig(
            layer         = WindowLayer.OVERLAY,
            anchor        = waylandAnchor,
            exclusiveZone = 0,
            keyboardMode  = KeyboardMode.ON_DEMAND,
            width         = w,
            height        = h,
            margins       = margins,
            namespace     = namespace
        )
    }
}

// ── Public API ────────────────────────────────────────────────────────────────

/**
 * Show a context menu at the given cursor position.
 *
 * Automatically:
 * - Picks the best corner anchor so the menu stays on screen
 * - Converts cursor coords to compositor margins
 * - Sizes the surface to [menuWidth] × [menuHeight]
 *
 * The [content] lambda receives a `dismiss` callback — call it to close the menu.
 *
 * ```kotlin
 * val screen = ScreenSize(1920f, 1080f)
 *
 * contextMenu(
 *     cursorX    = 850f,
 *     cursorY    = 600f,
 *     menuWidth  = 200f,
 *     menuHeight = 240f,
 *     screen     = screen,
 *     scope      = scope
 * ) { dismiss ->
 *     ContextMenuContent(dismiss)
 * }
 * ```
 *
 * @param cursorX    Cursor X in logical pixels.
 * @param cursorY    Cursor Y in logical pixels.
 * @param menuWidth  Menu surface width in logical pixels.
 * @param menuHeight Menu surface height in logical pixels.
 * @param screen     Screen dimensions — used for edge detection.
 * @param padding    Minimum gap from screen edge before flipping anchor (default 8px).
 * @param namespace  Compositor debug identifier.
 * @param binary     Binary source (defaults to bundled).
 * @param scope      Coroutine scope owning the bridge lifetime.
 * @param content    Your composable content. Receives a `dismiss` lambda.
 */
suspend fun contextMenu(
    cursorX:    Float,
    cursorY:    Float,
    menuWidth:  Float,
    menuHeight: Float,
    screen:     ScreenSize,
    padding:    Float          = 8f,
    namespace:  String         = "virdin-contextmenu",
    binary:     BinarySource   = BinarySource.Bundled,
    scope:      CoroutineScope,
    content:    @Composable (() -> Unit) -> Unit
): WaylandBridge {
    val config = ContextMenuConfig(
        cursorX    = cursorX,
        cursorY    = cursorY,
        menuWidth  = menuWidth,
        menuHeight = menuHeight,
        screen     = screen,
        padding    = padding
    )

    lateinit var bridge: WaylandBridge
    bridge = waylandSurface(
        config = config.toWindowConfig(namespace),
        binary = binary,
        scope  = scope
    ) {
        content { bridge.close() }
    }
    return bridge
}

/**
 * Convenience overload that accepts raw [screenWidth] and [screenHeight] floats
 * instead of a [ScreenSize] instance.
 *
 * ```kotlin
 * contextMenu(
 *     cursorX      = 850f,
 *     cursorY      = 600f,
 *     menuWidth    = 200f,
 *     menuHeight   = 240f,
 *     screenWidth  = 1920f,
 *     screenHeight = 1080f,
 *     scope        = scope
 * ) { dismiss ->
 *     ContextMenuContent(dismiss)
 * }
 * ```
 */
suspend fun contextMenu(
    cursorX:      Float,
    cursorY:      Float,
    menuWidth:    Float,
    menuHeight:   Float,
    screenWidth:  Float,
    screenHeight: Float,
    padding:      Float          = 8f,
    namespace:    String         = "virdin-contextmenu",
    binary:       BinarySource   = BinarySource.Bundled,
    scope:        CoroutineScope,
    content:      @Composable (() -> Unit) -> Unit
): WaylandBridge = contextMenu(
    cursorX    = cursorX,
    cursorY    = cursorY,
    menuWidth  = menuWidth,
    menuHeight = menuHeight,
    screen     = ScreenSize(screenWidth, screenHeight),
    padding    = padding,
    namespace  = namespace,
    binary     = binary,
    scope      = scope,
    content    = content
)