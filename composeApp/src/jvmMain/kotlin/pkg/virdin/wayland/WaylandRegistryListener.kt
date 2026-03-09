package pkg.virdin.wayland

import com.sun.jna.Callback
import com.sun.jna.Pointer
import com.sun.jna.Structure

// Registry listener structure
@Structure.FieldOrder("global", "global_remove")
class WaylandRegistryListener : Structure() {
    @JvmField var global: GlobalCallback? = null
    @JvmField var global_remove: GlobalRemoveCallback? = null

    interface GlobalCallback : Callback {
        fun invoke(
            data: Pointer?,
            registry: Pointer?,
            name: Int,
            interface_: String?,
            version: Int
        )
    }

    interface GlobalRemoveCallback : Callback {
        fun invoke(data: Pointer?, registry: Pointer?, name: Int)
    }
}