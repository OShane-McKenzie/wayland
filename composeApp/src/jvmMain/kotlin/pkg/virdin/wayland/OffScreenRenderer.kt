package pkg.virdin.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.*
import java.util.concurrent.LinkedBlockingQueue

@OptIn(ExperimentalComposeUiApi::class)
internal class OffScreenRenderer(
    var width: Int,
    var height: Int,
    private val density: Density
) {
    private var scene: ImageComposeScene? = null
    private var currentContent: (@Composable () -> Unit)? = null
    private val pendingEvents = LinkedBlockingQueue<() -> Unit>()
    private val keyEventSource = java.awt.Canvas()

    /*
     * Reusable surface + canvas for pixel readback.
     * We render the scene into this surface directly, avoiding the
     * Image → Bitmap → ByteArray chain that goes through Skia's color
     * management and introduces softness.
     */
    private var surface: Surface? = null
    private var pixelBuf: IntArray = IntArray(width * height)

    private val taskQueue = LinkedBlockingQueue<() -> Unit>()
    private val thread = Thread({
        while (!Thread.currentThread().isInterrupted) {
            try { taskQueue.take().invoke() }
            catch (e: InterruptedException) { break }
            catch (e: Exception) { System.err.println("[OffScreenRenderer] ${e.message}") }
        }
    }, "virdin-scene").apply { isDaemon = true; start() }

    private fun <T> runOnScene(block: () -> T): T {
        val f = java.util.concurrent.CompletableFuture<T>()
        taskQueue.put { try { f.complete(block()) } catch (e: Exception) { f.completeExceptionally(e) } }
        return f.get()
    }

    fun setContent(content: @Composable () -> Unit) = runOnScene {
        currentContent = content
        recreate(content)
    }

    fun render(): IntArray? = runOnScene {
        val sc = scene ?: return@runOnScene null

        // Drain all pending input events before rendering
        while (pendingEvents.isNotEmpty()) pendingEvents.poll()?.invoke()

        val image = try {
            sc.render(System.nanoTime())
        } catch (e: Exception) {
            return@runOnScene null
        }

        /*
         * Read pixels using a Bitmap configured with BGRA_8888 and no
         * color space. colorSpace=null tells Skia to do a raw copy with
         * no gamma or color management transform — avoiding the softness
         * that comes from sRGB↔linear conversion on readback.
         *
         * BGRA_8888 with no colorSpace matches WL_SHM_FORMAT_ARGB8888
         * on little-endian exactly: bytes in memory are B G R A.
         */
        val info = ImageInfo(
            width      = width,
            height     = height,
            colorType  = ColorType.BGRA_8888,
            alphaType  = ColorAlphaType.PREMUL,
            colorSpace = null   // raw pixel values, no color management
        )
        val bitmap = Bitmap()
        bitmap.allocPixels(info)
        val ok = image.readPixels(null, bitmap, 0, 0, false)
        image.close()

        if (!ok) { bitmap.close(); return@runOnScene null }

        // Read as IntArray directly — each Int is already BGRA packed,
        // which is what SharedFrame.writePixels(IntArray) expects.
        val bytes = bitmap.readPixels() ?: run { bitmap.close(); return@runOnScene null }
        bitmap.close()

        val dst = pixelBuf
        for (i in dst.indices) {
            val o = i * 4
            dst[i] = ((bytes[o + 3].toInt() and 0xFF) shl 24) or  // A
                    ((bytes[o + 2].toInt() and 0xFF) shl 16) or  // R
                    ((bytes[o + 1].toInt() and 0xFF) shl 8)  or  // G
                    (bytes[o    ].toInt() and 0xFF)              // B
        }
        dst
    }

    fun resize(newWidth: Int, newHeight: Int) = runOnScene {
        width    = newWidth
        height   = newHeight
        pixelBuf = IntArray(newWidth * newHeight)
        surface?.close()
        surface = null
        currentContent?.let { recreate(it) }
    }

    fun injectPointerEvent(event: PointerEvent) = pendingEvents.put {
        val sc = scene ?: return@put
        val type = when (event.type) {
            PtrEventType.ENTER  -> PointerEventType.Enter
            PtrEventType.LEAVE  -> PointerEventType.Exit
            PtrEventType.MOTION -> PointerEventType.Move
            PtrEventType.BUTTON -> if (event.state == 1) PointerEventType.Press
            else                  PointerEventType.Release
            else                -> PointerEventType.Unknown
        }
        val buttons = if (event.type == PtrEventType.BUTTON && event.state == 1)
            when (event.button) {
                272  -> PointerButtons(isPrimaryPressed   = true)
                273  -> PointerButtons(isSecondaryPressed = true)
                274  -> PointerButtons(isTertiaryPressed  = true)
                else -> PointerButtons()
            }
        else PointerButtons()

        sc.sendPointerEvent(type, Offset(event.x, event.y),
            timeMillis = System.currentTimeMillis(),
            type       = PointerType.Mouse,
            buttons    = buttons)
    }

    fun injectKeyEvent(event: KeyEvent) = pendingEvents.put {
        val sc = scene ?: return@put
        sc.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(
            nativeKeyEvent = java.awt.event.KeyEvent(
                keyEventSource,
                if (event.state == 0) java.awt.event.KeyEvent.KEY_RELEASED
                else                  java.awt.event.KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), event.modifiers, event.keycode,
                java.awt.event.KeyEvent.CHAR_UNDEFINED
            )
        ))
    }

    fun close() = runOnScene {
        runCatching { scene?.close() }
        runCatching { surface?.close() }
        thread.interrupt()
    }

    private fun recreate(content: @Composable () -> Unit) {
        scene?.close()
        scene = ImageComposeScene(
            width            = width,
            height           = height,
            density          = density,
            coroutineContext = Dispatchers.Default
        ) {
            androidx.compose.material3.MaterialTheme { content() }
        }
    }
}