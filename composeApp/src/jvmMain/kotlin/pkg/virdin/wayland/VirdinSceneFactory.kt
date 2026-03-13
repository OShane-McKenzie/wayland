package pkg.virdin.wayland

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import kotlin.coroutines.CoroutineContext

/**
 * Factory that constructs the [ImageComposeScene] used by [OffScreenRenderer].
 *
 * ## Why this exists
 *
 * [OffScreenRenderer] needs to inject a custom `PlatformContext` into
 * [ImageComposeScene] via reflection in order to intercept `startInputMethod`
 * (IME / keyboard input).  When the library is consumed as a JAR the JVM
 * module system may block that `final`-field write, silently leaving keyboard
 * input broken.
 *
 * By providing your own factory you perform the construction — and the
 * reflection injection — inside *your* module, where access is permitted,
 * and hand the ready-to-use scene back to the library.
 *
 * ## What you must do inside [create]
 *
 * 1. Construct an [ImageComposeScene] using the values exposed by the
 *    [WaylandBridge] receiver: [WaylandBridge.actualWidth],
 *    [WaylandBridge.actualHeight], and [WaylandBridge.surfaceDensity].
 * 2. Inject a custom `PlatformContext` that overrides `startInputMethod`
 *    and calls [WaylandBridge.notifyInputSession] to hand the session back
 *    to the library.
 * 3. Return the scene.  Do **not** set content — the library does that.
 *
 * ## What the library still owns
 *
 * - `setPointerIcon` — cursor changes continue to work via the library's
 *   own internal wiring; you do not need to handle them.
 * - `width`, `height`, `density` — read them from the bridge; never
 *   hard-code or override them.
 *
 * ## Example
 *
 * ```kotlin
 * @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
 * val myFactory = VirdinSceneFactory { coroutineContext ->
 *     // 'this' is the WaylandBridge — read system-calculated values
 *     val scene = ImageComposeScene(
 *         width            = actualWidth,
 *         height           = actualHeight,
 *         density          = surfaceDensity,
 *         coroutineContext = coroutineContext
 *     )
 *
 *     // Inject PlatformContext in your module where reflection is allowed
 *     val ctxField = scene.javaClass.getDeclaredField("_platformContext")
 *     ctxField.isAccessible = true
 *     val ctx = ctxField.get(scene)
 *     val delegateField = ctx.javaClass.getDeclaredField("\$\$delegate_0")
 *     delegateField.isAccessible = true
 *     val existing = delegateField.get(ctx) as PlatformContext
 *     delegateField.set(ctx, object : PlatformContext by existing {
 *         override suspend fun startInputMethod(
 *             request: PlatformTextInputMethodRequest
 *         ): Nothing {
 *             val session = VirdinInputSession(
 *                 onEditCommand = request.onEditCommand,
 *                 onImeAction   = request.onImeAction ?: {}
 *             )
 *             notifyInputSession(session)          // hand session to bridge
 *             try {
 *                 suspendCancellableCoroutine<Nothing> { cont ->
 *                     cont.invokeOnCancellation { notifyInputSession(null) }
 *                 }
 *             } finally {
 *                 notifyInputSession(null)
 *             }
 *         }
 *     })
 *
 *     scene
 * }
 *
 * // Pass it to any surface function:
 * waylandDock(sceneFactory = myFactory, scope = scope) { MyContent() }
 * ```
 */
@OptIn(ExperimentalComposeUiApi::class)
fun interface VirdinSceneFactory {
    /**
     * Create and return a fully configured [ImageComposeScene].
     *
     * The receiver is the [WaylandBridge] that owns this renderer — use it
     * to read [WaylandBridge.actualWidth], [WaylandBridge.actualHeight],
     * [WaylandBridge.surfaceDensity], and to call
     * [WaylandBridge.notifyInputSession].
     *
     * @param coroutineContext Pass directly to [ImageComposeScene].
     */
    fun WaylandBridge.create(coroutineContext: CoroutineContext): ImageComposeScene
}