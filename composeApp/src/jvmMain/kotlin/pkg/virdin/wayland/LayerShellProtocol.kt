package pkg.virdin.wayland

object LayerShellProtocol {
    const val INTERFACE_NAME = "zwlr_layer_shell_v1"
    const val VERSION = 4

    const val LAYER_BACKGROUND = 0
    const val LAYER_BOTTOM = 1
    const val LAYER_TOP = 2
    const val LAYER_OVERLAY = 3

    const val ANCHOR_TOP = 1 shl 0
    const val ANCHOR_BOTTOM = 1 shl 1
    const val ANCHOR_LEFT = 1 shl 2
    const val ANCHOR_RIGHT = 1 shl 3

    const val KEYBOARD_INTERACTIVITY_NONE = 0
    const val KEYBOARD_INTERACTIVITY_EXCLUSIVE = 1
    const val KEYBOARD_INTERACTIVITY_ON_DEMAND = 2
}