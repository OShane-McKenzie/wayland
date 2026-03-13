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
     * accessible. Pass the result directly to [Field.set] — no further
     * [Field.isAccessible] call needed.
     */
    @OptIn(InternalComposeUiApi::class)
    fun getDelegateField(ctx: Any): Field {
        return ctx.javaClass.getDeclaredField(DELEGATE_FIELD).also { it.isAccessible = true }
    }

    /**
     * Name of the generated delegate field inside ImageComposeScene's
     * _platformContext anonymous class. Exposed so consumer code performing
     * the field.set() can reference the same constant rather than
     * duplicating the string literal.
     */
    const val DELEGATE_FIELD = "\$\$delegate_0"
}