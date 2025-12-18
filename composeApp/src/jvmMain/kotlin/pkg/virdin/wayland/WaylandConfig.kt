package pkg.virdin.wayland

import androidx.compose.ui.unit.Density

// ── Enums ─────────────────────────────────────────────────────────────────────

enum class ContentPosition { TOP, BOTTOM, LEFT, RIGHT }

enum class WindowLayer(val value: Int) {
    BACKGROUND(0),
    BOTTOM(1),
    TOP(2),
    OVERLAY(3)
}

enum class KeyboardMode(val value: Int) {
    NONE(0),
    EXCLUSIVE(1),
    ON_DEMAND(2)
}

// ── Anchor bitfield (mirrors wlr-layer-shell anchor enum) ────────────────────

object Anchor {
    const val TOP    = 1
    const val BOTTOM = 2
    const val LEFT   = 4
    const val RIGHT  = 8
    const val ALL    = TOP or BOTTOM or LEFT or RIGHT
    const val NONE   = 0
}

// ── Window configuration ──────────────────────────────────────────────────────

/**
 * Generic configuration for any Wayland layer-shell surface.
 *
 * @param layer            Which compositor layer to place the surface on.
 * @param anchor           Bitfield of [Anchor] constants.
 *                         Use 0 for floating/centred (OSD style).
 * @param exclusiveZone    Pixels to reserve along the anchored edge.
 *                         0  = no reservation.
 *                        -1  = extend into other surfaces' reserved zones.
 * @param keyboardMode     How keyboard focus is handled.
 * @param width            Surface width in logical pixels.
 *                         If the surface is anchored to both LEFT and RIGHT,
 *                         pass 0 and the compositor fills the axis.
 * @param height           Surface height in logical pixels.
 *                         Same rule: 0 when anchored TOP+BOTTOM.
 * @param namespace        Unique name shown in compositor debug tools.
 * @param density          Screen density for Compose rendering.
 */
data class WindowConfig(
    val layer: WindowLayer = WindowLayer.TOP,
    val anchor: Int = 0,
    val exclusiveZone: Int = 0,
    val keyboardMode: KeyboardMode = KeyboardMode.NONE,
    val width: Int = 0,
    val height: Int = 0,
    val namespace: String = "virdin-surface",
    val density: Density = Density(1f)
)

// ── Pre-built configs for common surface types ────────────────────────────────

object SurfacePresets {

    /** Application dock at any screen edge. */
    fun dock(
        position: ContentPosition = ContentPosition.BOTTOM,
        size: Int = 64,
        namespace: String = "virdin-dock"
    ): WindowConfig {
        val anchor = when (position) {
            ContentPosition.BOTTOM -> Anchor.BOTTOM or Anchor.LEFT or Anchor.RIGHT
            ContentPosition.TOP    -> Anchor.TOP    or Anchor.LEFT or Anchor.RIGHT
            ContentPosition.LEFT   -> Anchor.LEFT   or Anchor.TOP  or Anchor.BOTTOM
            ContentPosition.RIGHT  -> Anchor.RIGHT  or Anchor.TOP  or Anchor.BOTTOM
        }
        val isHorizontal = position == ContentPosition.BOTTOM || position == ContentPosition.TOP
        return WindowConfig(
            layer          = WindowLayer.TOP,
            anchor         = anchor,
            exclusiveZone  = size,
            keyboardMode   = KeyboardMode.ON_DEMAND,
            width          = if (isHorizontal) 0 else size,
            height         = if (isHorizontal) size else 0,
            namespace      = namespace
        )
    }

    /** Status / taskbar panel. */
    fun panel(
        position: ContentPosition = ContentPosition.TOP,
        size: Int = 32,
        namespace: String = "virdin-panel"
    ): WindowConfig {
        val anchor = when (position) {
            ContentPosition.TOP    -> Anchor.TOP    or Anchor.LEFT or Anchor.RIGHT
            ContentPosition.BOTTOM -> Anchor.BOTTOM or Anchor.LEFT or Anchor.RIGHT
            ContentPosition.LEFT   -> Anchor.LEFT   or Anchor.TOP  or Anchor.BOTTOM
            ContentPosition.RIGHT  -> Anchor.RIGHT  or Anchor.TOP  or Anchor.BOTTOM
        }
        val isHorizontal = position == ContentPosition.TOP || position == ContentPosition.BOTTOM
        return WindowConfig(
            layer          = WindowLayer.TOP,
            anchor         = anchor,
            exclusiveZone  = size,
            keyboardMode   = KeyboardMode.NONE,
            width          = if (isHorizontal) 0 else size,
            height         = if (isHorizontal) size else 0,
            namespace      = namespace
        )
    }

    /** Full-screen desktop background. */
    fun desktopBackground(namespace: String = "virdin-background") = WindowConfig(
        layer         = WindowLayer.BACKGROUND,
        anchor        = Anchor.ALL,
        exclusiveZone = 0,
        keyboardMode  = KeyboardMode.NONE,
        width         = 0,
        height        = 0,
        namespace     = namespace
    )

    /** Full-screen lock screen. */
    fun lockScreen(namespace: String = "virdin-lockscreen") = WindowConfig(
        layer         = WindowLayer.OVERLAY,
        anchor        = Anchor.ALL,
        exclusiveZone = -1,
        keyboardMode  = KeyboardMode.EXCLUSIVE,
        width         = 0,
        height        = 0,
        namespace     = namespace
    )

    /**
     * On-screen display (volume indicator, notifications, …).
     * Centred near the top quarter of the screen by default.
     */
    fun osd(
        width: Int = 300,
        height: Int = 100,
        namespace: String = "virdin-osd"
    ) = WindowConfig(
        layer         = WindowLayer.OVERLAY,
        anchor        = Anchor.NONE,
        exclusiveZone = 0,
        keyboardMode  = KeyboardMode.NONE,
        width         = width,
        height        = height,
        namespace     = namespace
    )

    /**
     * App-launcher / application menu.
     * Anchored to the specified position and sized explicitly.
     */
    fun appMenu(
        position: ContentPosition = ContentPosition.BOTTOM,
        width: Int = 600,
        height: Int = 400,
        namespace: String = "virdin-appmenu"
    ): WindowConfig {
        val anchor = when (position) {
            ContentPosition.BOTTOM -> Anchor.BOTTOM or Anchor.LEFT
            ContentPosition.TOP    -> Anchor.TOP    or Anchor.LEFT
            ContentPosition.LEFT   -> Anchor.LEFT   or Anchor.BOTTOM
            ContentPosition.RIGHT  -> Anchor.RIGHT  or Anchor.BOTTOM
        }
        return WindowConfig(
            layer         = WindowLayer.OVERLAY,
            anchor        = anchor,
            exclusiveZone = 0,
            keyboardMode  = KeyboardMode.ON_DEMAND,
            width         = width,
            height        = height,
            namespace     = namespace
        )
    }
}
