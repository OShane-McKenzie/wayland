package pkg.virdin.wayland

import java.nio.ByteBuffer
import java.nio.ByteOrder

// ── Constants ─────────────────────────────────────────────────────────────────

internal const val PROTOCOL_MAGIC = 0x56495244.toInt()  // "VIRD"
internal const val HEADER_SIZE    = 12                   // magic(4) + type(4) + len(4)

internal object MsgType {
    const val CONFIGURE   = 0x01
    const val CFG_ACK     = 0x02
    const val FRAME_READY = 0x03
    const val FRAME_DONE  = 0x04
    const val PTR_EVENT   = 0x05
    const val KEY_EVENT   = 0x06
    const val RESIZE      = 0x07
    const val SHUTDOWN    = 0x08
    const val ERROR       = 0x09
}

internal object PtrEventType {
    const val ENTER  = 0
    const val LEAVE  = 1
    const val MOTION = 2
    const val BUTTON = 3
    const val AXIS   = 4
}

// ── Inbound messages (C → JVM) ────────────────────────────────────────────────

sealed class WaylandEvent

data class ConfigureAck(val width: Int, val height: Int) : WaylandEvent()

data class FrameDone(val seqNum: Long) : WaylandEvent()

data class PointerEvent(
    val type: Int,
    val x: Float,
    val y: Float,
    val button: Int = 0,
    val state: Int = 0
) : WaylandEvent()

data class KeyEvent(
    val keycode: Int,
    val state: Int,       // 0=released, 1=pressed, 2=repeated
    val modifiers: Int
) : WaylandEvent()

data class ResizeEvent(val width: Int, val height: Int) : WaylandEvent()

data class ErrorEvent(val code: Int, val message: String) : WaylandEvent()

// ── Serialization helpers ─────────────────────────────────────────────────────

internal fun ByteBuffer.writeLe(): ByteBuffer = order(ByteOrder.LITTLE_ENDIAN)

/** Build a complete wire message: header + payload. */
internal fun buildMessage(type: Int, buildPayload: ByteBuffer.() -> Unit): ByteArray {
    // pre-allocate a generous buffer, then trim
    val payload = ByteBuffer.allocate(2048).writeLe()
    payload.buildPayload()
    payload.flip()

    val payloadBytes = ByteArray(payload.remaining())
    payload.get(payloadBytes)

    val out = ByteBuffer.allocate(HEADER_SIZE + payloadBytes.size).writeLe()
    out.putInt(PROTOCOL_MAGIC)
    out.putInt(type)
    out.putInt(payloadBytes.size)
    out.put(payloadBytes)
    return out.array()
}

/** Write a Pascal-style length-prefixed UTF-8 string. */
internal fun ByteBuffer.putLenString(s: String) {
    val bytes = s.toByteArray(Charsets.UTF_8)
    putInt(bytes.size)
    put(bytes)
}

// ── Outbound message builders (JVM → C) ──────────────────────────────────────

internal fun buildConfigureMsg(config: WindowConfig, shmPath: String): ByteArray =
    buildMessage(MsgType.CONFIGURE) {
        putInt(config.layer.value)
        putInt(config.anchor)
        putInt(config.exclusiveZone)
        putInt(config.keyboardMode.value)
        putInt(config.width)
        putInt(config.height)
        putLenString(config.namespace)
        putLenString(shmPath)
    }

internal fun buildFrameReadyMsg(seqNum: Long): ByteArray =
    buildMessage(MsgType.FRAME_READY) { putLong(seqNum) }

internal fun buildShutdownMsg(): ByteArray =
    buildMessage(MsgType.SHUTDOWN) {}

// ── Inbound message parser (C → JVM) ─────────────────────────────────────────

internal fun parseEvent(type: Int, payload: ByteArray): WaylandEvent? {
    val buf = ByteBuffer.wrap(payload).writeLe()
    return when (type) {
        MsgType.CFG_ACK -> {
            val w = buf.int; val h = buf.int
            ConfigureAck(w, h)
        }
        MsgType.FRAME_DONE -> {
            val seq = buf.long
            FrameDone(seq)
        }
        MsgType.PTR_EVENT -> {
            val evType = buf.int
            val x      = buf.float
            val y      = buf.float
            val btn    = if (buf.remaining() >= 4) buf.int else 0
            val st     = if (buf.remaining() >= 4) buf.int else 0
            PointerEvent(evType, x, y, btn, st)
        }
        MsgType.KEY_EVENT -> {
            val kc   = buf.int
            val st   = buf.int
            val mods = buf.int
            KeyEvent(kc, st, mods)
        }
        MsgType.RESIZE -> {
            val w = buf.int; val h = buf.int
            ResizeEvent(w, h)
        }
        MsgType.ERROR -> {
            val code   = buf.int
            val msgLen = buf.int
            val bytes  = ByteArray(msgLen).also { buf.get(it) }
            ErrorEvent(code, String(bytes, Charsets.UTF_8))
        }
        else -> null
    }
}
