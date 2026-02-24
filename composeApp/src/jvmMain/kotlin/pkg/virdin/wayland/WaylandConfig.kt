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

// ── Margins ───────────────────────────────────────────────────────────────────

/**
 * Gap in logical pixels between the surface edge and the screen edge,
 * enforced by the compositor via zwlr_layer_surface_v1_set_margin.
 *
 * Useful for floating dock styles, avoiding screen notches, or adding
 * breathing room around panels and menus.
 */
data class Margins(
    val top:    Int = 0,
    val bottom: Int = 0,
    val left:   Int = 0,
    val right:  Int = 0
) {
    companion object {
        /** No margins on any side (default). */
        val NONE = Margins()

        /** Equal margin on all four sides. */
        fun all(px: Int) = Margins(px, px, px, px)

        /** Left and right margins only. */
        fun horizontal(px: Int) = Margins(left = px, right = px)

        /** Top and bottom margins only. */
        fun vertical(px: Int) = Margins(top = px, bottom = px)
    }
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
 * @param margins          Gap between the surface and the screen edge on each side.
 * @param namespace        Unique name shown in compositor debug tools.
 * @param density          Screen density for Compose rendering.
 */
data class WindowConfig(
    val layer:         WindowLayer  = WindowLayer.TOP,
    val anchor:        Int          = 0,
    val exclusiveZone: Int          = 0,
    val keyboardMode:  KeyboardMode = KeyboardMode.NONE,
    val width:         Int          = 0,
    val height:        Int          = 0,
    val margins:       Margins      = Margins.NONE,
    val namespace:     String       = "virdin-surface",
    val density:       Density      = Density(1f)
)

// ── Pre-built configs for common surface types ────────────────────────────────

object SurfacePresets {

    /**
     * Application dock at any screen edge.
     *
     * @param margins  Gap between dock and screen edges.
     *                 e.g. [Margins.all(8)] gives a floating dock look.
     *                 Margin thickness is added to [exclusiveZone] automatically
     *                 so other windows still respect the full reserved area.
     */
    fun dock(
        position:  ContentPosition = ContentPosition.BOTTOM,
        size:      Int             = 64,
        margins:   Margins         = Margins.NONE,
        namespace: String          = "virdin-dock"
    ): WindowConfig {
        val anchor = when (position) {
            ContentPosition.BOTTOM -> Anchor.BOTTOM or Anchor.LEFT or Anchor.RIGHT
            ContentPosition.TOP    -> Anchor.TOP    or Anchor.LEFT or Anchor.RIGHT
            ContentPosition.LEFT   -> Anchor.LEFT   or Anchor.TOP  or Anchor.BOTTOM
            ContentPosition.RIGHT  -> Anchor.RIGHT  or Anchor.TOP  or Anchor.BOTTOM
        }
        val isHorizontal = position == ContentPosition.BOTTOM || position == ContentPosition.TOP
        // Include margin thickness in exclusive zone so the WM reserves the right space
        val marginsThickness = if (isHorizontal) margins.top + margins.bottom
        else              margins.left + margins.right
        return WindowConfig(
            layer         = WindowLayer.TOP,
            anchor        = anchor,
            exclusiveZone = size + marginsThickness,
            keyboardMode  = KeyboardMode.ON_DEMAND,
            width         = if (isHorizontal) 0 else size,
            height        = if (isHorizontal) size else 0,
            margins       = margins,
            namespace     = namespace
        )
    }

    /**
     * Status / taskbar panel.
     *
     * @param margins  Gap between panel and screen edges.
     */
    fun panel(
        position:  ContentPosition = ContentPosition.TOP,
        size:      Int             = 32,
        margins:   Margins         = Margins.NONE,
        namespace: String          = "virdin-panel"
    ): WindowConfig {
        val anchor = when (position) {
            ContentPosition.TOP    -> Anchor.TOP    or Anchor.LEFT or Anchor.RIGHT
            ContentPosition.BOTTOM -> Anchor.BOTTOM or Anchor.LEFT or Anchor.RIGHT
            ContentPosition.LEFT   -> Anchor.LEFT   or Anchor.TOP  or Anchor.BOTTOM
            ContentPosition.RIGHT  -> Anchor.RIGHT  or Anchor.TOP  or Anchor.BOTTOM
        }
        val isHorizontal = position == ContentPosition.TOP || position == ContentPosition.BOTTOM
        val marginsThickness = if (isHorizontal) margins.top + margins.bottom
        else              margins.left + margins.right
        return WindowConfig(
            layer         = WindowLayer.TOP,
            anchor        = anchor,
            exclusiveZone = size + marginsThickness,
            keyboardMode  = KeyboardMode.NONE,
            width         = if (isHorizontal) 0 else size,
            height        = if (isHorizontal) size else 0,
            margins       = margins,
            namespace     = namespace
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

    /** Full-screen lock screen — grabs keyboard exclusively. */
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
     * Floats centred with no exclusive zone.
     */
    fun osd(
        width:     Int    = 300,
        height:    Int    = 100,
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
     *
     * @param margins  Gap between the menu and the screen edge it's anchored to.
     */
    fun appMenu(
        position:  ContentPosition = ContentPosition.BOTTOM,
        width:     Int             = 600,
        height:    Int             = 400,
        margins:   Margins         = Margins.NONE,
        namespace: String          = "virdin-appmenu"
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
            margins       = margins,
            namespace     = namespace
        )
    }
}