# Suppress unresolvable MethodHandle reference in OffScreenRenderer
-dontwarn java.lang.invoke.MethodHandle

# JNA
-dontwarn com.sun.jna.**
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }

# Kotlin reflection
-dontwarn kotlin.reflect.**
-keep class kotlin.reflect.** { *; }