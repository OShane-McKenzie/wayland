package pkg.virdin.wayland

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.invoke.MethodHandles
import kotlin.coroutines.CoroutineContext

/**
 * Factory that constructs the [ImageComposeScene] and injects a custom
 * [PlatformContext] to enable keyboard input (IME) on Wayland.
 *
 * ## Why this exists
 *
 * [PlatformContext.startInputMethod] is the only hook that modern Compose
 * text fields use for input. Injecting it requires writing a `final` field
 * inside [ImageComposeScene] via reflection. On Java 17+ this write is
 * blocked when performed from inside a library JAR, but succeeds when
 * performed from the consumer's own module.
 *
 * This factory moves both scene construction and the field write into the
 * consumer's module, where access is permitted.
 *
 * ## Required JVM flags
 *
 * The following must be present in the consumer's JVM args:
 * ```
 * --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
 * ```
 * Without this flag the [MethodHandles.privateLookupIn] call will fail.
 *
 * ## Example
 *
 * ```kotlin
 * @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
 * val mySceneFactory = VirdinSceneFactory { coroutineContext ->
 *     val scene = ImageComposeScene(actualWidth, actualHeight, surfaceDensity, coroutineContext)
 *     val ctx      = SceneContextAccessor.getContext(scene)
 *     val existing = SceneContextAccessor.getPlatformContext(ctx)
 *     val lookup   = MethodHandles.privateLookupIn(ctx.javaClass, MethodHandles.lookup())
 *     val varHandle = lookup.findVarHandle(
 *         ctx.javaClass,
 *         SceneContextAccessor.DELEGATE_FIELD,
 *         PlatformContext::class.java
 *     )
 *     varHandle.set(ctx, object : PlatformContext by existing {
 *         override suspend fun startInputMethod(
 *             request: PlatformTextInputMethodRequest
 *         ): Nothing {
 *             val session = VirdinInputSession(
 *                 onEditCommand = request.onEditCommand,
 *                 onImeAction   = request.onImeAction ?: {}
 *             )
 *             notifyInputSession(session)
 *             try {
 *                 suspendCancellableCoroutine<Nothing> { cont ->
 *                     cont.invokeOnCancellation { notifyInputSession(null) }
 *                 }
 *             } finally {
 *                 notifyInputSession(null)
 *             }
 *         }
 *     })
 *     scene
 * }
 *
 * // Pass to any surface function:
 * waylandDock(sceneFactory = mySceneFactory, scope = scope) { MyContent() }
 * ```
 */
@OptIn(ExperimentalComposeUiApi::class)
fun interface VirdinSceneFactory {
    /**
     * Create and return a fully configured [ImageComposeScene].
     *
     * The receiver is the [WaylandBridge] — use it to read:
     * - [WaylandBridge.actualWidth]
     * - [WaylandBridge.actualHeight]
     * - [WaylandBridge.surfaceDensity]
     * - [WaylandBridge.notifyInputSession]
     *
     * @param coroutineContext Pass directly to [ImageComposeScene].
     */
    fun WaylandBridge.create(coroutineContext: CoroutineContext): ImageComposeScene
}