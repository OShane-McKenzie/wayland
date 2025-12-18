package pkg.virdin.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo
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

        // Drain all pending input
        while (pendingEvents.isNotEmpty()) pendingEvents.poll()?.invoke()

        val image = try {
            sc.render(System.nanoTime())
        } catch (e: Exception) {
            // Ripple/animation state can NPE during rapid interaction — skip frame
            return@runOnScene null
        }

        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo(width, height, image.colorType, image.alphaType))
        image.readPixels(bitmap, 0, 0)
        image.close()
        val bytes = bitmap.readPixels()
        bitmap.close()
        if (bytes == null) return@runOnScene null

        IntArray(width * height) { i ->
            val o = i * 4
            ((bytes[o + 3].toInt() and 0xFF) shl 24) or
                    ((bytes[o + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[o + 1].toInt() and 0xFF) shl 8)  or
                    ( bytes[o    ].toInt() and 0xFF)
        }
    }

    fun resize(newWidth: Int, newHeight: Int) = runOnScene {
        width = newWidth; height = newHeight
        currentContent?.let { recreate(it) }
    }

    fun injectPointerEvent(event: PointerEvent) = pendingEvents.put {
        val sc = scene ?: return@put
        val type = when (event.type) {
            PtrEventType.ENTER  -> PointerEventType.Enter
            PtrEventType.LEAVE  -> PointerEventType.Exit
            PtrEventType.MOTION -> PointerEventType.Move
            PtrEventType.BUTTON -> if (event.state == 1) PointerEventType.Press else PointerEventType.Release
            else                -> PointerEventType.Unknown
        }
        val buttons = if (event.type == PtrEventType.BUTTON && event.state == 1) when (event.button) {
            272  -> PointerButtons(isPrimaryPressed   = true)
            273  -> PointerButtons(isSecondaryPressed = true)
            274  -> PointerButtons(isTertiaryPressed  = true)
            else -> PointerButtons()
        } else PointerButtons()
        sc.sendPointerEvent(type, Offset(event.x, event.y),
            timeMillis = System.currentTimeMillis(), type = PointerType.Mouse, buttons = buttons)
    }

    fun injectKeyEvent(event: KeyEvent) = pendingEvents.put {
        val sc = scene ?: return@put
        sc.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(
            nativeKeyEvent = java.awt.event.KeyEvent(
                keyEventSource,
                if (event.state == 0) java.awt.event.KeyEvent.KEY_RELEASED
                else java.awt.event.KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), event.modifiers, event.keycode,
                java.awt.event.KeyEvent.CHAR_UNDEFINED
            )
        ))
    }

    fun close() = runOnScene {
        runCatching { scene?.close() }
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
            // Wrap in MaterialTheme to avoid ripple NPE in ImageComposeScene
            // during rapid clicks (known CMP issue with off-screen rendering)
            androidx.compose.material3.MaterialTheme {
                content()
            }
        }
    }
}