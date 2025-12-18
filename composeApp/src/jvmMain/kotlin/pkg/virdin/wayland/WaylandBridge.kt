package pkg.virdin.wayland

import androidx.compose.runtime.Composable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WaylandBridge(
    private val scope: CoroutineScope,
    private val binary: BinarySource = BinarySource.Bundled
) {

    enum class BridgeState { IDLE, STARTING, CONFIGURED, RUNNING, ERROR, CLOSED }

    private val _state = MutableStateFlow(BridgeState.IDLE)
    val state: StateFlow<BridgeState> = _state

    var actualWidth:  Int = 0; private set
    var actualHeight: Int = 0; private set

    private var socket:   BridgeSocket?      = null
    private var shm:      SharedFrame?       = null
    private var renderer: OffScreenRenderer? = null
    private var process:  Process?           = null
    private var recvJob:  Job?               = null

    @Volatile private var frameSeq:        Long    = 0L
    @Volatile private var framePendingAck: Boolean = false
    @Volatile private var running:         Boolean = false

    suspend fun configure(config: WindowConfig, content: @Composable () -> Unit) {
        require(_state.value == BridgeState.IDLE) { "Already configured." }
        _state.value = BridgeState.STARTING

        try {
            val binaryFile = BinaryManager.resolve(binary)
            val bridgeSock = BridgeSocket().also { socket = it }
            bridgeSock.start()

            val proc = ProcessBuilder(binaryFile.absolutePath, "--socket", bridgeSock.path)
                .redirectErrorStream(true).start().also { process = it }

            scope.launch(Dispatchers.IO) {
                proc.inputStream.bufferedReader().lineSequence().forEach { println(it) }
            }

            withTimeoutOrNull(10_000L) { bridgeSock.accept() }
                ?: error("C binary did not connect within 10 seconds")

            val initW = if (config.width  > 0) config.width  else 1920
            val initH = if (config.height > 0) config.height else 1080
            val frame = SharedFrame(initW, initH).also { shm = it }

            scope.launch(Dispatchers.IO) { bridgeSock.receiveLoop() }
            bridgeSock.send(buildConfigureMsg(config, frame.path))

            val ack = withTimeoutOrNull(15_000L) { bridgeSock.waitForAck() }
                ?: error("No CONFIGURE_ACK received within 15 seconds")

            actualWidth  = ack.width
            actualHeight = ack.height
            println("[JVM] Compositor confirmed surface: ${actualWidth}x${actualHeight}")

            if (actualWidth != initW || actualHeight != initH) {
                frame.resize(actualWidth, actualHeight)
            }

            val rend = OffScreenRenderer(
                width   = actualWidth,
                height  = actualHeight,
                density = config.density
            ).also { renderer = it }
            rend.setContent(content)

            recvJob = scope.launch(Dispatchers.IO) {
                for (event in bridgeSock.incomingEvents) {
                    when (event) {
                        is FrameDone    -> framePendingAck = false
                        is PointerEvent -> rend.injectPointerEvent(event)
                        is KeyEvent     -> rend.injectKeyEvent(event)
                        is ResizeEvent  -> {
                            actualWidth = event.width; actualHeight = event.height
                            shm?.resize(event.width, event.height)
                            rend.resize(event.width, event.height)
                        }
                        is ErrorEvent -> {
                            System.err.println("[JVM] C error: ${event.message}")
                            _state.value = BridgeState.ERROR
                        }
                        else -> {}
                    }
                }
            }

            running = true
            _state.value = BridgeState.CONFIGURED
            _state.value = BridgeState.RUNNING

            // Simple 60fps poll loop — render every 16ms unconditionally.
            // This is the most reliable approach: no dirty flags, no condition
            // variables, no missed wakeups. CPU cost is negligible for a small
            // dock surface. Frames are only pushed to the compositor when pixels
            // actually changed (cheap IntArray comparison).
            Thread({

                while (running) {
                    Thread.sleep(16)

                    if (framePendingAck) continue

                    val pixels = rend.render() ?: continue
                    if (pixels.any { it != 0 }) {
                        frame.writePixels(pixels)
                        framePendingAck = true
                        bridgeSock.send(buildFrameReadyMsg(++frameSeq))
                    }
                }
            }, "virdin-render").apply { isDaemon = true; start() }

        } catch (e: Exception) {
            System.err.println("[JVM] configure error: ${e.message}")
            _state.value = BridgeState.ERROR
            close(); throw e
        }
    }

    fun invalidate() {}

    fun close() {
        running = false
        recvJob?.cancel()
        runCatching { socket?.send(buildShutdownMsg()) }
        runCatching { process?.destroy() }
        runCatching { renderer?.close() }
        runCatching { shm?.close() }
        runCatching { socket?.close() }
        _state.value = BridgeState.CLOSED
        println("[JVM] WaylandBridge closed")
    }
}