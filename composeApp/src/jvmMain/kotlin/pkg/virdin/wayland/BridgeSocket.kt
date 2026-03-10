package pkg.virdin.wayland

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * Manages the Unix-domain socket server that the C binary connects back to.
 *
 * Usage:
 *  1. Call [start] → get the socket path to pass to the C process.
 *  2. Call [accept] (suspending) after the binary has launched.
 *  3. Call [send] / [receive] to exchange messages.
 *  4. Call [close] on cleanup.
 */
internal class BridgeSocket {

    private val socketPath: Path = Files.createTempFile("virdin-wayland-", ".sock")
    private var serverChannel: ServerSocketChannel? = null
    private var clientChannel: SocketChannel? = null

    /** Incoming events parsed from the wire, buffered for consumers. */
    val incomingEvents = Channel<WaylandEvent>(capacity = Channel.UNLIMITED)

    /** Separate channel just for ConfigureAck — never races with incomingEvents consumers. */
    private val ackChannel = Channel<ConfigureAck>(capacity = 1)

    /** File-system path that the C binary should connect to. */
    val path: String get() = socketPath.toString()

    fun start() {
        Files.deleteIfExists(socketPath)  // ensure clean path
        val addr = UnixDomainSocketAddress.of(socketPath)
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(addr)
        }
        println("[JVM] Socket listening at $socketPath")
    }

    /** Blocks until the C binary connects. Call from a background coroutine. */
    suspend fun accept() = withContext(Dispatchers.IO) {
        clientChannel = serverChannel!!.accept()
        clientChannel!!.configureBlocking(true)
        println("[JVM] C binary connected")
    }

    /** Send a pre-serialised wire message. */
    fun send(bytes: ByteArray) {
        val ch = clientChannel ?: error("Socket not connected")
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) ch.write(buf)
    }

    /**
     * Read exactly [n] bytes into a freshly allocated array.
     * Throws on EOF or error.
     */
    private fun readExact(n: Int): ByteArray {
        val ch  = clientChannel ?: error("Socket not connected")
        val buf = ByteBuffer.allocate(n)
        while (buf.hasRemaining()) {
            val r = ch.read(buf)
            if (r < 0) error("Unexpected EOF from C binary")
        }
        return buf.array()
    }

    /**
     * Blocking read loop — reads one message at a time and puts it into
     * [incomingEvents].  Run this inside a dedicated IO coroutine.
     */
    suspend fun receiveLoop() = withContext(Dispatchers.IO) {
        try {
            while (isActive) {
                val headerBytes = readExact(HEADER_SIZE)
                val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
                val magic   = header.int
                val type    = header.int
                val payLen  = header.int

                if (magic != PROTOCOL_MAGIC) {
                    System.err.println("[JVM] Bad magic: 0x${magic.toString(16)}")
                    continue
                }

                val payload = if (payLen > 0) readExact(payLen) else ByteArray(0)
                val event   = parseEvent(type, payload)
                if (event != null) {
                    if (event is ConfigureAck) ackChannel.send(event)
                    else incomingEvents.send(event)
                } else {
                    System.err.println("[JVM] Unknown message type: 0x${type.toString(16)}")
                }
            }
        } catch (e: Exception) {
            if (isActive) System.err.println("[JVM] receiveLoop error: ${e.message}")
        } finally {
            incomingEvents.close()
        }
    }

    /** Suspends until ConfigureAck arrives — does not consume from incomingEvents. */
    suspend fun waitForAck(): ConfigureAck = ackChannel.receive()

    fun close() {
        runCatching { clientChannel?.close() }
        runCatching { serverChannel?.close() }
        runCatching { Files.deleteIfExists(socketPath) }
    }
}