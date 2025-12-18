package pkg.virdin.wayland

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

interface WaylandClientLib : Library {
    companion object {
        val INSTANCE: WaylandClientLib? = try {
            Native.load(LibNames.LIB_WAYLAND_CLIENT, WaylandClientLib::class.java) as WaylandClientLib
        } catch (e: UnsatisfiedLinkError) {
            println("Failed to load wayland-client library: ${e.message}")
            null
        }
    }

    fun wl_display_connect(name: String?): Pointer?
    fun wl_display_disconnect(display: Pointer)
    fun wl_display_roundtrip(display: Pointer): Int
    fun wl_display_dispatch(display: Pointer): Int
    fun wl_display_dispatch_pending(display: Pointer): Int
    fun wl_display_flush(display: Pointer): Int
    fun wl_display_get_fd(display: Pointer): Int

    fun wl_proxy_marshal_flags(proxy: Pointer, opcode: Int, interfacePtr: Pointer?, version: Int, flags: Int, vararg args: Any?): Pointer?
    fun wl_proxy_add_listener(proxy: Pointer, implementation: Pointer, data: Pointer?): Int
    fun wl_proxy_destroy(proxy: Pointer)
    fun wl_proxy_get_version(proxy: Pointer): Int

}