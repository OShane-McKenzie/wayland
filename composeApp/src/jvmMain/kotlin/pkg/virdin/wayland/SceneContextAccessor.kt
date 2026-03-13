package pkg.virdin.wayland

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformContext
import java.lang.reflect.Field

object SceneContextAccessor {

    @OptIn(InternalComposeUiApi::class)
    fun getContext(scene: ImageComposeScene): Any {
        val ctxField = scene.javaClass.getDeclaredField("_platformContext")
        ctxField.isAccessible = true
        return ctxField.get(scene)
    }

    @OptIn(InternalComposeUiApi::class)
    fun getPlatformContext(ctx: Any): PlatformContext {
        val delegateField = ctx.javaClass.getDeclaredField(DELEGATE_FIELD)
        delegateField.isAccessible = true
        return delegateField.get(ctx) as PlatformContext
    }

    /**
     * Returns the [DELEGATE_FIELD] reflective handle for [ctx], already marked
     * accessible. Pass the result directly to [putDelegate] — no further
     * [Field.isAccessible] call needed.
     */
    @OptIn(InternalComposeUiApi::class)
    fun getDelegateField(ctx: Any): Field {
        return ctx.javaClass.getDeclaredField(DELEGATE_FIELD).also { it.isAccessible = true }
    }

    /**
     * Writes [replacement] into the [DELEGATE_FIELD] of [ctx] using
     * [sun.misc.Unsafe] via reflection, avoiding any direct reference to the
     * deprecated ["sun.misc.Unsafe.putObject"] API.
     *
     * Requires --add-opens=java.base/sun.misc=ALL-UNNAMED in JVM args.
     */
    @OptIn(InternalComposeUiApi::class)
    fun putDelegate(ctx: Any, replacement: PlatformContext) {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)

        val field        = getDelegateField(ctx)
        val offsetMethod = unsafe.javaClass.getMethod("objectFieldOffset", Field::class.java)
        val offset       = offsetMethod.invoke(unsafe, field) as Long

        val putMethod = unsafe.javaClass.getMethod(
            "putObject",
            Any::class.java,
            Long::class.java,
            Any::class.java
        )
        putMethod.invoke(unsafe, ctx, offset, replacement)
    }

    /**
     * Name of the generated delegate field inside ImageComposeScene's
     * _platformContext anonymous class. Exposed so consumer code can
     * reference the same constant rather than duplicating the string literal.
     */
    const val DELEGATE_FIELD = "\$\$delegate_0"
}