package pkg.virdin.wayland

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * A memory-mapped file that acts as the shared pixel buffer between the JVM
 * and the C helper process.
 *
 * Layout: raw ARGB8888 pixels, row-major, [width × height × 4] bytes.
 *
 * The JVM writes here; the C binary mmaps the same file and attaches it
 * directly to a [wl_shm_pool] / [wl_buffer].  No copy is needed.
 */
internal class SharedFrame(
    var width: Int,
    var height: Int
) {
    private val tmpFile: Path = Files.createTempFile("virdin-frame-", ".raw")
    private var raf: RandomAccessFile = RandomAccessFile(tmpFile.toFile(), "rw")
    private var mappedBuffer: MappedByteBuffer = allocate(width, height)

    /** Absolute path of the backing file — passed to the C binary. */
    val path: String get() = tmpFile.toString()

    /** Direct [ByteBuffer] view of the pixel memory. Position is NOT reset on access. */
    val buffer: ByteBuffer get() = mappedBuffer

    /**
     * Write a complete frame from [pixels].
     * [pixels] must be exactly [width] × [height] × 4 bytes, ARGB8888.
     */
    fun writePixels(pixels: ByteArray) {
        val expected = width * height * 4
        require(pixels.size == expected) {
            "Expected $expected bytes, got ${pixels.size}"
        }
        mappedBuffer.position(0)
        mappedBuffer.put(pixels)
        mappedBuffer.force()  // ensure OS writes are visible to C process
    }

    /**
     * Write pixels from an IntArray (each Int is an ARGB pixel, Compose native format).
     * Skia uses BGRA8888 (little-endian) which maps to ARGB8888 in Wayland.
     */
    fun writePixels(pixels: IntArray) {
        mappedBuffer.position(0)
        // Wayland WL_SHM_FORMAT_ARGB8888 on little-endian = bytes in memory: B G R A.
        // JVM putInt() writes big-endian (A R G B in memory), which is wrong.
        // We must write each channel byte explicitly in LE order.
        for (px in pixels) {
            mappedBuffer.put((px and 0xFF).toByte())               // B
            mappedBuffer.put((px shr 8  and 0xFF).toByte())        // G
            mappedBuffer.put((px shr 16 and 0xFF).toByte())        // R
            mappedBuffer.put((px shr 24 and 0xFF).toByte())        // A
        }
        mappedBuffer.force()
    }

    /**
     * Resize the shared buffer.  The C binary must also be notified and will
     * re-create its [wl_shm_pool]; call this only between frames.
     */
    fun resize(newWidth: Int, newHeight: Int) {
        mappedBuffer.force()
        raf.close()
        width  = newWidth
        height = newHeight
        raf = RandomAccessFile(tmpFile.toFile(), "rw")
        mappedBuffer = allocate(newWidth, newHeight)
    }

    fun close() {
        runCatching { raf.close() }
        runCatching { Files.deleteIfExists(tmpFile) }
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun allocate(w: Int, h: Int): MappedByteBuffer {
        val size = w.toLong() * h.toLong() * 4L
        raf.setLength(size)
        return raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, size)
    }
}