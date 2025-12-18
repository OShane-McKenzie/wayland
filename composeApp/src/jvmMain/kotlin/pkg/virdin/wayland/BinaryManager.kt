package pkg.virdin.wayland

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Describes where to find the [wayland-helper] binary.
 *
 * Pass one of these to [WaylandBridge] (or the top-level surface functions)
 * when you create a surface:
 *
 * ```kotlin
 * // Use the binary that was bundled inside the JAR (default)
 * waylandDock(binary = BinarySource.Bundled) { ... }
 *
 * // Point to a binary you installed yourself
 * waylandDock(binary = BinarySource.Path("/usr/local/bin/wayland-helper")) { ... }
 * ```
 */
sealed class BinarySource {

    /**
     * Extract the binary that was compiled and bundled into the JAR's
     * resources at build time (`src/jvmMain/resources/native/<arch>/wayland-helper`).
     * This is the default — it works out of the box with no installation.
     */
    object Bundled : BinarySource()

    /**
     * Use a binary at an explicit [path] on the filesystem.
     * Handy when you have `wayland-helper` installed system-wide
     * (e.g. via your distro's package manager) and don't want the
     * overhead of extracting a copy.
     */
    data class Path(val path: String) : BinarySource()
}

// ── Internal resolver ─────────────────────────────────────────────────────────

internal object BinaryManager {

    private const val BINARY_NAME = "wayland-helper"

    // Cache extractions so we only unpack once per JVM run
    private var extractedBundled: File? = null

    fun resolve(source: BinarySource): File = when (source) {
        is BinarySource.Bundled  -> bundled()
        is BinarySource.Path     -> external(source.path)
    }

    // ── Bundled ───────────────────────────────────────────────────────────────

    private fun bundled(): File {
        extractedBundled?.let { return it }

        val arch     = normaliseArch(System.getProperty("os.arch") ?: "x86_64")
        val resource = "/native/$arch/$BINARY_NAME"

        val stream = BinaryManager::class.java.getResourceAsStream(resource)
            ?: error(
                "Bundled binary not found at $resource.\n" +
                        "Run `./build_native.sh` from the repo root to compile and " +
                        "copy it into the resources directory, then rebuild the JAR."
            )

        val tmpDir  = Files.createTempDirectory("virdin-wayland").toFile()
        val binFile = File(tmpDir, BINARY_NAME)

        // Write then explicitly sync before exec.
        // Linux raises ETXTBSY (error=26) if any fd is still open for
        // writing on a file you try to execve(), even after copyTo returns.
        // Using FileOutputStream directly lets us call fd.sync() before close.
        java.io.FileOutputStream(binFile).use { out ->
            stream.use { input -> input.copyTo(out) }
            out.fd.sync()
        }

        Files.setPosixFilePermissions(
            binFile.toPath(),
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            )
        )

        tmpDir.deleteOnExit()
        binFile.deleteOnExit()

        println("[BinaryManager] Extracted bundled binary → ${binFile.absolutePath}")
        extractedBundled = binFile
        return binFile
    }

    // ── External path ─────────────────────────────────────────────────────────

    private fun external(path: String): File {
        val f = File(path)
        require(f.exists())     { "Binary not found at: $path" }
        require(f.canExecute()) { "Binary is not executable: $path" }
        println("[BinaryManager] Using external binary → $path")
        return f
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun normaliseArch(raw: String): String = when (raw.lowercase()) {
        "x86_64", "amd64"  -> "linux-x86_64"
        "aarch64", "arm64" -> "linux-aarch64"
        else               -> "linux-${raw.lowercase()}"
    }
}