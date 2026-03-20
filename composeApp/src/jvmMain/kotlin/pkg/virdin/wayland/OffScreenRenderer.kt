package pkg.virdin.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class VirdinInputSession(
    val onEditCommand: (List<EditCommand>) -> Unit,
    val onImeAction:   (ImeAction) -> Unit
) {
    fun commitText(text: String) = onEditCommand(listOf(CommitTextCommand(text, 1)))
    fun backspace()              = onEditCommand(listOf(BackspaceCommand()))
    fun sendImeAction(a: ImeAction) = onImeAction(a)
}

@OptIn(ExperimentalComposeUiApi::class)
internal class OffScreenRenderer(
    @Volatile var width: Int,
    @Volatile var height: Int,
    private val density: Density,
    private val onPointerIconChanged: ((cursorName: String) -> Unit)? = null
) {
    private var scene: ImageComposeScene? = null
    private var currentContent: (@Composable () -> Unit)? = null
    private var pixelBuf: IntArray = IntArray(width * height)
    private var focusInitialized = false

    @Volatile private var inputSession: VirdinInputSession? = null

    private val sceneExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "virdin-scene").apply { isDaemon = true }
    }
    private val sceneDispatcher = sceneExecutor.asCoroutineDispatcher()

    // Key repeat
    private var repeatJob: Job? = null
    private val repeatScope = CoroutineScope(sceneDispatcher)

    companion object {
        private const val REPEAT_DELAY_MS    = 400L
        private const val REPEAT_INTERVAL_MS = 35L
    }

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
            catch (e: Exception) { f.complete(null) }
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

    // Tracks which buttons are currently held for drag/highlight support.
    private var currentButtons = PointerButtons()

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
        if (event.type == PtrEventType.BUTTON) {
            val pressed = event.state == 1
            currentButtons = when (event.button) {
                272  -> PointerButtons(isPrimaryPressed   = pressed,
                    isSecondaryPressed = currentButtons.isSecondaryPressed,
                    isTertiaryPressed  = currentButtons.isTertiaryPressed)
                273  -> PointerButtons(isPrimaryPressed   = currentButtons.isPrimaryPressed,
                    isSecondaryPressed = pressed,
                    isTertiaryPressed  = currentButtons.isTertiaryPressed)
                274  -> PointerButtons(isPrimaryPressed   = currentButtons.isPrimaryPressed,
                    isSecondaryPressed = currentButtons.isSecondaryPressed,
                    isTertiaryPressed  = pressed)
                else -> currentButtons
            }
        }
        sc.sendPointerEvent(
            type, Offset(event.x, event.y),
            timeMillis = System.currentTimeMillis(),
            type       = PointerType.Mouse,
            buttons    = currentButtons
        )
    }

    @OptIn(InternalComposeUiApi::class)
    fun injectKeyEvent(event: KeyEvent) = postOnScene {
        val sc = scene ?: return@postOnScene

        // KeyUp — cancel repeat only.
        if (event.state == 0) {
            repeatJob?.cancel()
            repeatJob = null
            return@postOnScene
        }

        val isShift = event.modifiers and 1 != 0
        val isCtrl  = event.modifiers and 2 != 0
        val isAlt   = event.modifiers and 4 != 0
        val isMeta  = event.modifiers and 8 != 0

        val codePoint = if (event.keysym in 0x20..0xFEFF) event.keysym else 0

        val awtKeyCode = when (event.keysym) {
            0xFF08 -> java.awt.event.KeyEvent.VK_BACK_SPACE
            0xFF09 -> java.awt.event.KeyEvent.VK_TAB
            0xFF0D -> java.awt.event.KeyEvent.VK_ENTER
            0xFF1B -> java.awt.event.KeyEvent.VK_ESCAPE
            0xFF50 -> java.awt.event.KeyEvent.VK_HOME
            0xFF51 -> java.awt.event.KeyEvent.VK_LEFT
            0xFF52 -> java.awt.event.KeyEvent.VK_UP
            0xFF53 -> java.awt.event.KeyEvent.VK_RIGHT
            0xFF54 -> java.awt.event.KeyEvent.VK_DOWN
            0xFF57 -> java.awt.event.KeyEvent.VK_END
            0xFF63 -> java.awt.event.KeyEvent.VK_INSERT
            0xFFFF -> java.awt.event.KeyEvent.VK_DELETE
            else   -> if (codePoint > 0)
                java.awt.event.KeyEvent.getExtendedKeyCodeForChar(codePoint)
                    .takeIf { it != java.awt.event.KeyEvent.VK_UNDEFINED } ?: event.keycode
            else event.keycode
        }

        val session = inputSession
        val action: () -> Unit = when {
            codePoint > 0 && !isCtrl && !isAlt && !isMeta -> {
                val text = String(Character.toChars(codePoint))
                if (session != null) ({ session.commitText(text) })
                else ({ println("[KEY] char=$text dropped — no input session") })
            }
            awtKeyCode == java.awt.event.KeyEvent.VK_BACK_SPACE && session != null ->
                ({ session.backspace() })
            awtKeyCode == java.awt.event.KeyEvent.VK_ENTER && session != null ->
                ({ session.sendImeAction(ImeAction.Done) })
            else -> {
                val composeKey = androidx.compose.ui.input.key.Key(
                    awtKeyCode, java.awt.event.KeyEvent.KEY_LOCATION_STANDARD
                )
                val composeEvent = androidx.compose.ui.input.key.KeyEvent(
                    key            = composeKey,
                    type           = androidx.compose.ui.input.key.KeyEventType.KeyDown,
                    codePoint      = 0,
                    isShiftPressed = isShift,
                    isCtrlPressed  = isCtrl,
                    isAltPressed   = isAlt,
                    isMetaPressed  = isMeta,
                    nativeEvent    = null
                )
                ({ sc.sendKeyEvent(composeEvent) })
            }
        }

        val shouldRepeat = !isCtrl && !isMeta
        action()

        if (shouldRepeat) {
            repeatJob?.cancel()
            repeatJob = repeatScope.launch {
                delay(REPEAT_DELAY_MS)
                while (isActive) {
                    action()
                    delay(REPEAT_INTERVAL_MS)
                }
            }
        }
    }

    fun close() = runOnScene {
        repeatJob?.cancel()
        repeatScope.cancel()
        runCatching { scene?.close() }
        sceneDispatcher.close()
        sceneExecutor.shutdownNow()
    }

    @OptIn(InternalComposeUiApi::class)
    private fun renderInternal(): IntArray? {
        val sc = scene ?: return null

        val image = try {
            sc.render(System.nanoTime())
        } catch (e: Exception) {
            return null
        }

        if (!focusInitialized) {
            focusInitialized = true
            sceneExecutor.submit {
                Thread.sleep(100)
                try {
                    val sc2 = scene ?: return@submit
                    val innerSceneField = sc2.javaClass.getDeclaredField("scene")
                    innerSceneField.isAccessible = true
                    val innerScene = innerSceneField.get(sc2) as? androidx.compose.ui.scene.ComposeScene
                    innerScene?.focusManager?.takeFocus(androidx.compose.ui.focus.FocusDirection.Next)
                    println("[OffScreenRenderer] focusManager.takeFocus(Next) called")
                } catch (e: Exception) {
                    println("[OffScreenRenderer] focus init failed: ${e.message}")
                }
            }
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

        if (!ok) { bitmap.close(); return null }

        val bytes = bitmap.readPixels() ?: run { bitmap.close(); return null }
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

    private fun pointerIconToCursorName(icon: androidx.compose.ui.input.pointer.PointerIcon): String {
        return try {
            val cursorField = icon.javaClass.declaredFields
                .firstOrNull { it.type == java.awt.Cursor::class.java }
                ?.also { it.isAccessible = true }
            val awtCursor = cursorField?.get(icon) as? java.awt.Cursor
            when (awtCursor?.type) {
                java.awt.Cursor.TEXT_CURSOR          -> "text"
                java.awt.Cursor.HAND_CURSOR          -> "pointer"
                java.awt.Cursor.CROSSHAIR_CURSOR     -> "crosshair"
                java.awt.Cursor.MOVE_CURSOR          -> "move"
                java.awt.Cursor.WAIT_CURSOR          -> "wait"
                java.awt.Cursor.N_RESIZE_CURSOR,
                java.awt.Cursor.S_RESIZE_CURSOR      -> "ns-resize"
                java.awt.Cursor.E_RESIZE_CURSOR,
                java.awt.Cursor.W_RESIZE_CURSOR      -> "ew-resize"
                java.awt.Cursor.NE_RESIZE_CURSOR,
                java.awt.Cursor.SW_RESIZE_CURSOR     -> "nesw-resize"
                java.awt.Cursor.NW_RESIZE_CURSOR,
                java.awt.Cursor.SE_RESIZE_CURSOR     -> "nwse-resize"
                java.awt.Cursor.DEFAULT_CURSOR, null -> "default"
                else                                 -> "default"
            }
        } catch (e: Exception) { "default" }
    }

    @OptIn(InternalComposeUiApi::class)
    private fun makePlatformContext(
        base: androidx.compose.ui.platform.PlatformContext
    ): androidx.compose.ui.platform.PlatformContext =
        object : androidx.compose.ui.platform.PlatformContext by base {
            override fun setPointerIcon(pointerIcon: androidx.compose.ui.input.pointer.PointerIcon) {
                val cursorName = pointerIconToCursorName(pointerIcon)
                println("[OffScreenRenderer] pointer icon → $cursorName")
                onPointerIconChanged?.invoke(cursorName)
            }

            override suspend fun startInputMethod(
                request: androidx.compose.ui.platform.PlatformTextInputMethodRequest
            ): Nothing {
                val session = VirdinInputSession(
                    onEditCommand = request.onEditCommand,
                    onImeAction   = request.onImeAction ?: {}
                )
                inputSession = session
                println("[OffScreenRenderer] startInputMethod — input session open")
                try {
                    suspendCancellableCoroutine<Nothing> { cont ->
                        cont.invokeOnCancellation {
                            if (inputSession === session) {
                                inputSession = null
                                println("[OffScreenRenderer] input session closed")
                            }
                        }
                    }
                } finally {
                    if (inputSession === session) inputSession = null
                }
            }
        }

    @OptIn(InternalComposeUiApi::class)
    private fun injectPlatformContext(sc: ImageComposeScene) {
        try {
            val ctxField = sc.javaClass.getDeclaredField("_platformContext")
            ctxField.isAccessible = true
            val ctx = ctxField.get(sc)

            val delegateField = ctx.javaClass.getDeclaredField("\$\$delegate_0")
            delegateField.isAccessible = true
            val existing = delegateField.get(ctx) as androidx.compose.ui.platform.PlatformContext
            val replacement = makePlatformContext(existing)

            // Use MethodHandles to write the final field.
            // Requires --add-opens=java.base/java.lang.reflect=ALL-UNNAMED
            //           --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
            val lookup = java.lang.invoke.MethodHandles.privateLookupIn(
                ctx.javaClass,
                java.lang.invoke.MethodHandles.lookup()
            )
            lookup.unreflectSetter(delegateField).invoke(ctx, replacement)

            println("[OffScreenRenderer] PlatformContext injected")
        } catch (e: Exception) {
            println("[OffScreenRenderer] PlatformContext injection failed: ${e.message}")
        }
    }

    private fun recreate(content: @Composable () -> Unit) {
        scene?.close()
        inputSession = null
        repeatJob?.cancel()
        val sc = ImageComposeScene(
            width            = width,
            height           = height,
            density          = density,
            coroutineContext = sceneDispatcher
        ) {
            androidx.compose.material3.MaterialTheme { content() }
        }
        injectPlatformContext(sc)
        scene = sc
        focusInitialized = false
    }
}