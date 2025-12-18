package pkg.virdin.wayland

class NativeWaylandCalls {
    private var waylandManager: WaylandWindowManager? = null

    fun initialize(): Boolean {
        try {
            waylandManager = WaylandWindowManager()
            return waylandManager?.initialize() ?: false
        } catch (e: Exception) {
            println("Failed to initialize Wayland: ${e.message}")
            return false
        }
    }

    fun getManager(): WaylandWindowManager? = waylandManager

    fun isWaylandAvailable(): Boolean {
        val waylandDisplay = LibC.INSTANCE.getenv("WAYLAND_DISPLAY")
        return !waylandDisplay.isNullOrEmpty()
    }

    fun cleanup() {
        try {
            waylandManager?.cleanup()
            waylandManager = null
        } catch (e: Exception) {
            println("Error during Wayland cleanup: ${e.message}")
        }
    }
}