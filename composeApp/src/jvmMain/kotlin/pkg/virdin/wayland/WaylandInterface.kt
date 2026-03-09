package pkg.virdin.wayland

import com.sun.jna.Pointer
import com.sun.jna.Structure
class WaylandInterface : Structure() {
    @JvmField var name: String? = null
    @JvmField var version: Int = 0
    @JvmField var method_count: Int = 0
    @JvmField var methods: Pointer? = null
    @JvmField var event_count: Int = 0
    @JvmField var events: Pointer? = null

    override fun getFieldOrder() = listOf("name", "version", "method_count", "methods", "event_count", "events")

    companion object {
        // Keep strong references to prevent GC
        private val registryInterface = WaylandInterface().apply {
            name = "wl_registry"
            version = 1
            method_count = 1
            event_count = 2
            autoWrite()
        }

        private val compositorInterface = WaylandInterface().apply {
            name = "wl_compositor"
            version = 6
            method_count = 2
            event_count = 0
            autoWrite()
        }

        private val surfaceInterface = WaylandInterface().apply {
            name = "wl_surface"
            version = 6
            method_count = 10
            event_count = 2
            autoWrite()
        }

        private val layerShellInterface = WaylandInterface().apply {
            name = LayerShellProtocol.INTERFACE_NAME
            version = LayerShellProtocol.VERSION
            method_count = 3
            event_count = 0
            autoWrite()
        }

        private val layerSurfaceInterface = WaylandInterface().apply {
            name = "zwlr_layer_surface_v1"
            version = 4
            method_count = 8
            event_count = 2
            autoWrite()
        }

        private val outputInterface = WaylandInterface().apply {
            name = "wl_output"
            version = 4
            method_count = 1
            event_count = 4
            autoWrite()
        }

        fun createRegistryInterface(): Pointer = registryInterface.pointer
        fun createCompositorInterface(): Pointer = compositorInterface.pointer
        fun createSurfaceInterface(): Pointer = surfaceInterface.pointer
        fun createLayerShellInterface(): Pointer = layerShellInterface.pointer
        fun createLayerSurfaceInterface(): Pointer = layerSurfaceInterface.pointer
        fun createOutputInterface(): Pointer = outputInterface.pointer
    }
}