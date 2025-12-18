package pkg.virdin.wayland

import com.sun.jna.Callback
import com.sun.jna.Pointer
import com.sun.jna.Structure

class WaylandWindowManager {
    private val wl = WaylandClientLib.INSTANCE

    private var display: Pointer? = null
    private var registry: Pointer? = null
    private var compositor: Pointer? = null
    private var layerShell: Pointer? = null
    private var surface: Pointer? = null
    private var layerSurface: Pointer? = null
    private var output: Pointer? = null

    private val globals = mutableMapOf<String, Pair<Int, Int>>()

    var isInitialized = false
        private set

    fun initialize(): Boolean {
        if (wl == null) {
            println("✗ Wayland client library not available")
            return false
        }

        try {
            display = wl.wl_display_connect(null)
            if (display == null || display == Pointer.NULL) {
                println("✗ Failed to connect to Wayland display")
                return false
            }

            println("✓ Connected to Wayland display")

            registry = getRegistry()
            if (registry == null || registry == Pointer.NULL) {
                println("✗ Failed to get Wayland registry")
                return false
            }

            val listener = RegistryListener { name, interfaceName, version ->
                globals[interfaceName] = Pair(name, version)
            }

            wl.wl_proxy_add_listener(registry!!, listener.toNativeListener(), null)
            wl.wl_display_roundtrip(display!!)

            println("✓ Discovered ${globals.size} global objects")

            if (!bindGlobals()) {
                return false
            }

            isInitialized = true
            return true

        } catch (e: Exception) {
            println("✗ Error initializing Wayland: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    private fun bindGlobals(): Boolean {
        if (wl == null) return false

        val compositorInfo = globals["wl_compositor"]
        if (compositorInfo == null) {
            println("✗ wl_compositor not available")
            return false
        }
        compositor = bindGlobal(compositorInfo.first, "wl_compositor", compositorInfo.second)
        if (compositor == null || compositor == Pointer.NULL) {
            println("✗ Failed to bind wl_compositor")
            return false
        }
        println("✓ Bound to wl_compositor")

        val layerShellInfo = globals[LayerShellProtocol.INTERFACE_NAME]
        if (layerShellInfo == null) {
            println("✗ ${LayerShellProtocol.INTERFACE_NAME} not available")
            println("  Your compositor doesn't support wlr-layer-shell protocol")
            return false
        }
        layerShell = bindGlobal(layerShellInfo.first, LayerShellProtocol.INTERFACE_NAME, layerShellInfo.second)
        if (layerShell == null || layerShell == Pointer.NULL) {
            println("✗ Failed to bind ${LayerShellProtocol.INTERFACE_NAME}")
            return false
        }
        println("✓ Bound to ${LayerShellProtocol.INTERFACE_NAME}")

        val outputInfo = globals["wl_output"]
        if (outputInfo != null) {
            output = bindGlobal(outputInfo.first, "wl_output", outputInfo.second)
            if (output != null && output != Pointer.NULL) {
                println("✓ Bound to wl_output")
            }
        }

        return true
    }

    fun createSurface(): Pointer? {
        if (wl == null || compositor == null || compositor == Pointer.NULL) {
            println("✗ Compositor not initialized")
            return null
        }

        surface = wl.wl_proxy_marshal_flags(
            compositor!!,
            0,
            null,
            wl.wl_proxy_get_version(compositor!!),
            0
        )

        if (surface != null && surface != Pointer.NULL) {
            println("✓ Created wl_surface")
            return surface
        }

        return null
    }

    fun createLayerSurface(
        surface: Pointer,
        layer: Int = LayerShellProtocol.LAYER_TOP,
        anchor: Int = LayerShellProtocol.ANCHOR_BOTTOM or LayerShellProtocol.ANCHOR_LEFT or LayerShellProtocol.ANCHOR_RIGHT,
        exclusiveZone: Int = 64,
        namespace: String
    ): Pointer? {
        if (wl == null || layerShell == null || layerShell == Pointer.NULL) {
            println("✗ Layer shell not initialized")
            return null
        }

        layerSurface = wl.wl_proxy_marshal_flags(
            layerShell!!,
            0,
            null,
            wl.wl_proxy_get_version(layerShell!!),
            0,
            null,
            surface,
            output,
            layer,
            namespace
        )

        if (layerSurface == null || layerSurface == Pointer.NULL) {
            println("✗ Failed to create layer surface")
            return null
        }

        println("✓ Created layer surface")

        setLayerSurfaceSize(layerSurface!!, 0, exclusiveZone)
        setLayerSurfaceAnchor(layerSurface!!, anchor)
        setLayerSurfaceExclusiveZone(layerSurface!!, exclusiveZone)
        setLayerSurfaceKeyboardInteractivity(layerSurface!!, LayerShellProtocol.KEYBOARD_INTERACTIVITY_ON_DEMAND)

        commitSurface(surface)

        return layerSurface
    }

    fun setLayerSurfaceSize(layerSurface: Pointer, width: Int, height: Int) {
        wl?.wl_proxy_marshal_flags(layerSurface, 1, null, wl.wl_proxy_get_version(layerSurface), 0, width, height)
    }

    fun setLayerSurfaceAnchor(layerSurface: Pointer, anchor: Int) {
        wl?.wl_proxy_marshal_flags(layerSurface, 2, null, wl.wl_proxy_get_version(layerSurface), 0, anchor)
    }

    fun setLayerSurfaceExclusiveZone(layerSurface: Pointer, zone: Int) {
        wl?.wl_proxy_marshal_flags(layerSurface, 3, null, wl.wl_proxy_get_version(layerSurface), 0, zone)
    }

    fun setLayerSurfaceKeyboardInteractivity(layerSurface: Pointer, interactivity: Int) {
        wl?.wl_proxy_marshal_flags(layerSurface, 5, null, wl.wl_proxy_get_version(layerSurface), 0, interactivity)
    }

    fun setLayer(layerSurface: Pointer, layer: Int) {
        wl?.wl_proxy_marshal_flags(layerSurface, 6, null, wl.wl_proxy_get_version(layerSurface), 0, layer)
        surface?.let { commitSurface(it) }
    }

    fun commitSurface(surface: Pointer) {
        wl?.wl_proxy_marshal_flags(surface, 6, null, wl.wl_proxy_get_version(surface), 0)
    }

    fun roundtrip(): Int {
        return wl?.let { display?.let { d -> it.wl_display_roundtrip(d) } } ?: -1
    }

    fun dispatch(): Int {
        return wl?.let { display?.let { d -> it.wl_display_dispatch_pending(d) } } ?: -1
    }

    fun flush(): Int {
        return wl?.let { display?.let { d -> it.wl_display_flush(d) } } ?: -1
    }

    fun cleanup() {
        if (wl == null) return

        layerSurface?.let { if (it != Pointer.NULL) wl.wl_proxy_destroy(it) }
        surface?.let { if (it != Pointer.NULL) wl.wl_proxy_destroy(it) }
        compositor?.let { if (it != Pointer.NULL) wl.wl_proxy_destroy(it) }
        layerShell?.let { if (it != Pointer.NULL) wl.wl_proxy_destroy(it) }
        output?.let { if (it != Pointer.NULL) wl.wl_proxy_destroy(it) }
        registry?.let { if (it != Pointer.NULL) wl.wl_proxy_destroy(it) }
        display?.let { if (it != Pointer.NULL) wl.wl_display_disconnect(it) }

        println("✓ Cleaned up Wayland connection")
    }

    private fun getRegistry(): Pointer? {
        return wl?.let { display?.let { d ->
            // The null interface is okay here - the error is cosmetic
            it.wl_proxy_marshal_flags(d, 1, null, it.wl_proxy_get_version(d), 0)
        } }
    }

    private fun bindGlobal(name: Int, interfaceName: String, version: Int): Pointer? {
        val interface_ = when (interfaceName) {
            "wl_compositor" -> WaylandInterface.createCompositorInterface()
            "wl_output" -> WaylandInterface.createOutputInterface()
            LayerShellProtocol.INTERFACE_NAME -> WaylandInterface.createLayerShellInterface()
            else -> null
        }

        return wl?.let { registry?.let { r ->
            it.wl_proxy_marshal_flags(r, 0, interface_, it.wl_proxy_get_version(r), 0, name, interfaceName, version, null)
        } }
    }

    fun isLayerShellSupported(): Boolean {
        return globals.containsKey(LayerShellProtocol.INTERFACE_NAME)
    }

    fun getAvailableGlobals(): Map<String, Int> {
        return globals.mapValues { it.value.second }
    }
}