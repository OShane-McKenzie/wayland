package pkg.virdin.wayland

import com.sun.jna.Memory
import com.sun.jna.Pointer

/**
 * StrutShim creates a thin (~3px) native zwlr_layer_surface_v1 surface anchored
 * at the same edge as a Compose dock/panel surface.
 *
 * The Compose layer-shell surfaces are positioned correctly by the compositor,
 * but do not cause other windows to be moved away. This shim's sole purpose is
 * to claim the exclusive_zone for that edge so the compositor pushes windows.
 *
 * Nothing about the existing Compose surfaces changes. This is purely additive.
 *
 * Usage:
 *   val shim = StrutShim(waylandManager)
 *   shim.createForDock(position = ContentPosition.BOTTOM, dockHeight = 64)
 *   // in your event loop: shim.flush()
 *   // on exit: shim.destroy()
 */
class StrutShim(private val waylandManager: WaylandWindowManager) {

    companion object {
        /** Thickness of the shim surface itself in pixels. */
        const val SHIM_THICKNESS_PX = 3
    }

    private val wl = WaylandClientLib.INSTANCE

    // Each active shim owns a wl_surface + zwlr_layer_surface_v1
    private data class ShimSurface(
        val surface: Pointer,
        val layerSurface: Pointer,
        val position: ContentPosition
    )

    private val shims = mutableListOf<ShimSurface>()

    /**
     * Create a strut shim for a dock (bottom/top/left/right).
     *
     * @param position   Which edge the Compose dock occupies.
     * @param dockHeight The full pixel height/width of the Compose dock surface.
     *                   This becomes the exclusive_zone so the compositor reserves
     *                   exactly that much space and pushes windows away.
     * @param namespace  Wayland namespace string — should be distinct from the
     *                   Compose surface's namespace, e.g. "dock-strut".
     */
    fun createForDock(
        position: ContentPosition,
        dockHeight: Int,
        namespace: String = "compose-strut-${position.name.lowercase()}"
    ) = createShim(position, dockHeight, namespace)

    /**
     * Create a strut shim for a panel (same as dock, different defaults).
     */
    fun createForPanel(
        position: ContentPosition,
        panelHeight: Int,
        namespace: String = "compose-strut-${position.name.lowercase()}"
    ) = createShim(position, panelHeight, namespace)

    private fun createShim(
        position: ContentPosition,
        reservationSize: Int,
        namespace: String
    ) {
        if (wl == null) {
            println("StrutShim ✗ wayland-client not available")
            return
        }

        // --- 1. Create a bare wl_surface via the compositor ---
        val surface = waylandManager.createSurface()
        if (surface == null || surface == Pointer.NULL) {
            println("StrutShim ✗ could not create wl_surface for shim ($position)")
            return
        }

        // --- 2. Anchor flags for this edge ---
        val anchor = anchorForPosition(position)

        // --- 3. Create the zwlr_layer_surface_v1 on LAYER_TOP ---
        //    exclusive_zone = full reservation size (e.g. 64px for a 64px dock)
        //    surface size   = SHIM_THICKNESS_PX × full-width/height
        //    This is what the compositor actually reads to decide how far to push windows.
        val layerSurface = waylandManager.createLayerSurface(
            surface = surface,
            layer = LayerShellProtocol.LAYER_TOP,
            anchor = anchor,
            exclusiveZone = reservationSize,
            namespace = namespace
        )

        if (layerSurface == null || layerSurface == Pointer.NULL) {
            println("StrutShim ✗ could not create layer surface for shim ($position)")
            wl.wl_proxy_destroy(surface)
            return
        }

        // --- 4. Set the physical size of the shim surface ---
        //    Width/height along the axis perpendicular to the edge = SHIM_THICKNESS_PX.
        //    Width/height along the edge itself = 0 (means "stretch to fill", per protocol).
        val (shimWidth, shimHeight) = shimDimensions(position)
        waylandManager.setLayerSurfaceSize(layerSurface, shimWidth, shimHeight)

        // --- 5. No keyboard interactivity — the shim is invisible and input-transparent ---
        waylandManager.setLayerSurfaceKeyboardInteractivity(
            layerSurface,
            LayerShellProtocol.KEYBOARD_INTERACTIVITY_NONE
        )

        // --- 6. Commit so the compositor processes the configuration ---
        waylandManager.commitSurface(surface)
        waylandManager.roundtrip()

        shims += ShimSurface(surface, layerSurface, position)
        println("StrutShim ✓ created ${SHIM_THICKNESS_PX}px shim at $position " +
                "with exclusive_zone=$reservationSize (namespace=$namespace)")
    }

    /**
     * Destroy all shim surfaces. Call this when the corresponding
     * Compose dock/panel is closing.
     */
    fun destroy() {
        if (wl == null) return
        for (shim in shims) {
            if (shim.layerSurface != Pointer.NULL) wl.wl_proxy_destroy(shim.layerSurface)
            if (shim.surface != Pointer.NULL)      wl.wl_proxy_destroy(shim.surface)
        }
        shims.clear()
        println("StrutShim ✓ all shims destroyed")
    }

    /**
     * Destroy only the shim(s) for a specific edge.
     */
    fun destroyForPosition(position: ContentPosition) {
        if (wl == null) return
        val targets = shims.filter { it.position == position }
        for (shim in targets) {
            if (shim.layerSurface != Pointer.NULL) wl.wl_proxy_destroy(shim.layerSurface)
            if (shim.surface != Pointer.NULL)      wl.wl_proxy_destroy(shim.surface)
        }
        shims.removeAll(targets.toSet())
        println("StrutShim ✓ shim for $position destroyed")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Anchor flags: shim is anchored to its edge AND spans the full width/height
     * of that edge (LEFT+RIGHT for horizontal edges, TOP+BOTTOM for vertical).
     */
    private fun anchorForPosition(position: ContentPosition): Int = when (position) {
        ContentPosition.BOTTOM ->
            LayerShellProtocol.ANCHOR_BOTTOM or
                    LayerShellProtocol.ANCHOR_LEFT   or
                    LayerShellProtocol.ANCHOR_RIGHT

        ContentPosition.TOP ->
            LayerShellProtocol.ANCHOR_TOP  or
                    LayerShellProtocol.ANCHOR_LEFT or
                    LayerShellProtocol.ANCHOR_RIGHT

        ContentPosition.LEFT ->
            LayerShellProtocol.ANCHOR_LEFT or
                    LayerShellProtocol.ANCHOR_TOP  or
                    LayerShellProtocol.ANCHOR_BOTTOM

        ContentPosition.RIGHT ->
            LayerShellProtocol.ANCHOR_RIGHT or
                    LayerShellProtocol.ANCHOR_TOP   or
                    LayerShellProtocol.ANCHOR_BOTTOM
    }

    /**
     * Physical dimensions of the shim surface itself.
     * - 0 on the axis parallel to the edge = "stretch to fill the output width/height"
     * - SHIM_THICKNESS_PX on the axis perpendicular to the edge
     */
    private fun shimDimensions(position: ContentPosition): Pair<Int, Int> = when (position) {
        ContentPosition.BOTTOM,
        ContentPosition.TOP    -> Pair(0, SHIM_THICKNESS_PX)   // full width, thin height
        ContentPosition.LEFT,
        ContentPosition.RIGHT  -> Pair(SHIM_THICKNESS_PX, 0)   // thin width, full height
    }
}