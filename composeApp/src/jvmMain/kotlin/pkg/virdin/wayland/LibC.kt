package pkg.virdin.wayland

import com.sun.jna.Library
import com.sun.jna.Native

interface LibC : Library {
    companion object {
        val INSTANCE: LibC = Native.load(LibNames.LIB_C, LibC::class.java) as LibC
    }

    fun getenv(name: String): String?
    fun setenv(name: String, value: String, overwrite: Int): Int
}