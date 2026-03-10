package pkg.virdin.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.skia.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalComposeUiApi::class)
internal class OffScreenRenderer(
    @Volatile var width: Int,
    @Volatile var height: Int,
    private val density: Density
) {
    private var scene: ImageComposeScene? = null
    private var currentContent: (@Composable () -> Unit)? = null
    private val keyEventSource = java.awt.Canvas()
    private var pixelBuf: IntArray = IntArray(width * height)

    private val sceneExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "virdin-scene").apply { isDaemon = true }
    }
    private val sceneDispatcher = sceneExecutor.asCoroutineDispatcher()

    fun needsRender(): Boolean = scene != null

    private fun postOnScene(block: () -> Unit) {
        sceneExecutor.submit {
            try { block() }
            catch (e: Exception) { System.err.println("[OffScreenRenderer] ${e.message}") }
        }
    }

    private fun <T> runOnScene(block: () -> T): T {
        val f = CompletableFuture<T>()
        sceneExecutor.submit {
            try { f.complete(block()) }
            catch (e: Exception) { f.completeExceptionally(e) }
        }
        return f.get()
    }

    fun setContent(content: @Composable () -> Unit) = runOnScene {
        currentContent = content
        recreate(content)
    }

    fun render(): IntArray? {
        val f = CompletableFuture<IntArray?>()
        sceneExecutor.submit {
            try { f.complete(renderInternal()) }
            catch (e: Exception) {
                //println("[OffScreenRenderer] render dispatch: ${e::class.simpleName}: ${e.message}")
                f.complete(null)
            }
        }
        return try {
            f.get(500, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        }
    }

    fun resize(newWidth: Int, newHeight: Int) = runOnScene {
        width    = newWidth
        height   = newHeight
        pixelBuf = IntArray(newWidth * newHeight)
        currentContent?.let { recreate(it) }
    }

    fun injectPointerEvent(event: PointerEvent) = postOnScene {
        val sc = scene ?: return@postOnScene
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
        sc.sendPointerEvent(
            type, Offset(event.x, event.y),
            timeMillis = System.currentTimeMillis(),
            type       = PointerType.Mouse,
            buttons    = buttons
        )
    }

    fun injectKeyEvent(event: KeyEvent) = postOnScene {
        val sc = scene ?: return@postOnScene
        sc.sendKeyEvent(
            androidx.compose.ui.input.key.KeyEvent(
                nativeKeyEvent = java.awt.event.KeyEvent(
                    keyEventSource,
                    if (event.state == 0) java.awt.event.KeyEvent.KEY_RELEASED
                    else                  java.awt.event.KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(), event.modifiers, event.keycode,
                    java.awt.event.KeyEvent.CHAR_UNDEFINED
                )
            )
        )
    }

    fun close() = runOnScene {
        runCatching { scene?.close() }
        sceneDispatcher.close()
        sceneExecutor.shutdownNow()
    }

    private fun renderInternal(): IntArray? {
        val sc = scene ?: return null

        val image = try {
            sc.render(System.nanoTime())
        } catch (e: Exception) {
            // Print full stack trace so we can see exactly which Skia call fails
            //println("[OffScreenRenderer] sc.render() EXCEPTION:")
            e.printStackTrace()
            return null
        }

        val info = ImageInfo(
            width      = width,
            height     = height,
            colorType  = ColorType.BGRA_8888,
            alphaType  = ColorAlphaType.PREMUL,
            colorSpace = null
        )
        val bitmap = Bitmap()
        bitmap.allocPixels(info)
        val ok = image.readPixels(null, bitmap, 0, 0, false)
        image.close()

        if (!ok) {
            //println("[OffScreenRenderer] readPixels(image→bitmap) failed")
            bitmap.close()
            return null
        }

        val bytes = bitmap.readPixels() ?: run {
            //println("[OffScreenRenderer] bitmap.readPixels() returned null")
            bitmap.close()
            return null
        }
        bitmap.close()

        val dst = pixelBuf
        for (i in dst.indices) {
            val o = i * 4
            dst[i] = ((bytes[o + 3].toInt() and 0xFF) shl 24) or
                    ((bytes[o + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[o + 1].toInt() and 0xFF) shl 8)  or
                    (bytes[o    ].toInt() and 0xFF)
        }
        return dst
    }

    private fun recreate(content: @Composable () -> Unit) {
        scene?.close()
        scene = ImageComposeScene(
            width            = width,
            height           = height,
            density          = density,
            coroutineContext = sceneDispatcher
        ) {
            androidx.compose.material3.MaterialTheme { content() }
        }
        //println("[OffScreenRenderer] scene created ${width}x${height}")
    }
}