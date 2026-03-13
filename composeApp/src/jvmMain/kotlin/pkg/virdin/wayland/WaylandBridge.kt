package pkg.virdin.wayland

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ArrayBlockingQueue

class WaylandBridge(
    private val scope: CoroutineScope,
    private val binary: BinarySource = BinarySource.Bundled
) {

    enum class BridgeState { IDLE, STARTING, CONFIGURED, RUNNING, ERROR, CLOSED }

    private val _state = MutableStateFlow(BridgeState.IDLE)
    val state: StateFlow<BridgeState> = _state

    // Physical pixel dimensions — set after CONFIGURE_ACK, read-only to callers.
    var actualWidth:  Int = 0; private set
    var actualHeight: Int = 0; private set

    // Density derived from wl_output scale — read-only to callers.
    // Exposed so a VirdinSceneFactory implementation can pass it to
    // ImageComposeScene without the library having to expose the raw scale value.
    var surfaceDensity: Density = Density(1f); private set

    private var socket:   BridgeSocket?      = null
    private var shm:      SharedFrame?       = null
    private var renderer: OffScreenRenderer? = null
    private var process:  Process?           = null
    private var recvJob:  Job?               = null

    @Volatile private var frameSeq: Long    = 0L
    @Volatile private var running:  Boolean = false

    private val renderTrigger = ArrayBlockingQueue<Unit>(1)

    /**
     * Called by a [VirdinSceneFactory] implementation from inside its injected
     * `PlatformContext.startInputMethod` override to hand the active input
     * session back to the library.
     *
     * Pass `null` to close the session (on cancellation or completion).
     */
    fun notifyInputSession(session: VirdinInputSession?) {
        renderer?.inputSession = session
    }

    suspend fun configure(
        config:       WindowConfig,
        sceneFactory: VirdinSceneFactory? = null,
        content:      @Composable () -> Unit
    ) {
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

            val screenSize = java.awt.Toolkit.getDefaultToolkit().screenSize
            val overW = if (config.width  > 0) config.width  * 4 else screenSize.width
            val overH = if (config.height > 0) config.height * 4 else screenSize.height
            val frame = SharedFrame(overW, overH).also { shm = it }

            scope.launch(Dispatchers.IO) { bridgeSock.receiveLoop() }
            bridgeSock.send(buildConfigureMsg(config, frame.path))

            val ack = withTimeoutOrNull(15_000L) { bridgeSock.waitForAck() }
                ?: error("No CONFIGURE_ACK received within 15 seconds")

            val scale    = ack.scale
            val density  = Density(scale)
            val physW    = (ack.width  * scale).toInt()
            val physH    = (ack.height * scale).toInt()
            actualWidth  = physW
            actualHeight = physH
            surfaceDensity = density
            println("[JVM] surface: ${ack.width}x${ack.height} logical, ${physW}x${physH} physical, scale=$scale")

            if (physW != overW || physH != overH) {
                frame.resize(physW, physH)
            }

            val rend = OffScreenRenderer(
                width                = actualWidth,
                height               = actualHeight,
                density              = density,
                bridge               = this,
                onPointerIconChanged = { cursorName -> bridgeSock.sendCursorChange(cursorName) },
                sceneFactory         = sceneFactory
            ).also { renderer = it }
            rend.setContent(content)

            recvJob = scope.launch(Dispatchers.IO) {
                for (event in bridgeSock.incomingEvents) {
                    when (event) {
                        is FrameDone    -> renderTrigger.offer(Unit)
                        is PointerEvent -> rend.injectPointerEvent(event.copy(x = event.x * scale, y = event.y * scale))
                        is KeyEvent     -> rend.injectKeyEvent(event)
                        is ResizeEvent  -> {
                            val newPhysW = (event.width  * scale).toInt()
                            val newPhysH = (event.height * scale).toInt()
                            actualWidth  = newPhysW
                            actualHeight = newPhysH
                            shm?.resize(newPhysW, newPhysH)
                            rend.resize(newPhysW, newPhysH)
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

            Thread({
                while (running) {
                    try {
                        renderTrigger.take()
                    } catch (e: InterruptedException) {
                        break
                    }

                    if (!running) break

                    val pixels = rend.render() ?: continue
                    frame.writePixels(pixels)
                    bridgeSock.send(buildFrameReadyMsg(++frameSeq))
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
        renderTrigger.offer(Unit)
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