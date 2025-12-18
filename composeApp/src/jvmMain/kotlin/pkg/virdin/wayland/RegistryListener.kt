package pkg.virdin.wayland

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

class RegistryListener(private val callback: (Int, String, Int) -> Unit) {
    interface GlobalCallback : Callback {
        fun invoke(data: Pointer?, registry: Pointer?, name: Int, interfaceName: String?, version: Int)
    }
    
    interface GlobalRemoveCallback : Callback {
        fun invoke(data: Pointer?, registry: Pointer?, name: Int)
    }
    
    val global: GlobalCallback = object : GlobalCallback {
        override fun invoke(data: Pointer?, registry: Pointer?, name: Int, interfaceName: String?, version: Int) {
            interfaceName?.let { callback(name, it, version) }
        }
    }
    
    val globalRemove: GlobalRemoveCallback = object : GlobalRemoveCallback {
        override fun invoke(data: Pointer?, registry: Pointer?, name: Int) {}
    }
    
    fun toNativeListener(): Pointer {
        val pointerSize = Native.POINTER_SIZE.toLong()
        val listener = Memory(pointerSize * 2)
        listener.setPointer(0, CallbackReference.getFunctionPointer(global))
        listener.setPointer(pointerSize, CallbackReference.getFunctionPointer(globalRemove))
        return listener
    }
}