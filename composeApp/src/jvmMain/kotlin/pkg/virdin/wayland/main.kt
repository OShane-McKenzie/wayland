package pkg.virdin.wayland
//
//import androidx.compose.foundation.background
//import androidx.compose.foundation.layout.*
//import androidx.compose.material3.*
//import androidx.compose.runtime.*
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.unit.Density
//import androidx.compose.ui.unit.dp
//import kotlinx.coroutines.*
//import kotlinx.coroutines.swing.Swing
//import pkg.virdin.wayland.*
//import javax.swing.SwingUtilities
//
//// ── Entry point ───────────────────────────────────────────────────────────────
////
//// Must launch from the Swing EDT so that Dispatchers.Swing has a running
//// event pump to dispatch to.  runBlocking on the main thread blocks the
//// thread but does NOT start an AWT event loop — Dispatchers.Swing tasks
//// posted from that context queue up and never execute, producing blank frames.
////
//// Pattern:
////   SwingUtilities.invokeLater  →  starts AWT pump on EDT
////   CoroutineScope(Dispatchers.Swing)  →  coroutines run on that same EDT
////   done.await()  →  main thread sleeps until bridge.close() is called
//
//fun main() {
//    val done = CompletableDeferred<Unit>()
//
//    SwingUtilities.invokeLater {
//        val scope = CoroutineScope(Dispatchers.Swing + SupervisorJob())
//        scope.launch {
//            try {
//                // demoBottomDock(scope)
//                // demoTopPanel(scope)
//                // demoOsd(scope)
//                // demoAppMenu(scope)
//                // demoDesktopBackground(scope)
//                // demoGenericWindow(scope)
//            } finally {
//                done.complete(Unit)
//            }
//        }
//    }
//
//    // Block the main thread until the surface is closed
//    kotlinx.coroutines.runBlocking { done.await() }
//}
//
//// ── Bottom dock ───────────────────────────────────────────────────────────────
//suspend fun demoBottomDock(scope: CoroutineScope) {
//    val bridge = waylandDock(
//        position = ContentPosition.BOTTOM,
//        size     = 64,
//        scope    = scope
//    ) {
//        Box(
//            modifier = Modifier
//                .fillMaxSize()
//                .background(Color(0xCC1E1E2E)),
//            contentAlignment = Alignment.Center
//        ) {
//            Text("🚀  My Dock", color = Color.White)
//        }
//    }
//    bridge.awaitClose()
//}
//
//// ── Top panel ─────────────────────────────────────────────────────────────────
//suspend fun demoTopPanel(scope: CoroutineScope) {
//    val bridge = waylandPanel(
//        position = ContentPosition.TOP,
//        size     = 32,
//        scope    = scope
//    ) {
//        Row(
//            modifier = Modifier
//                .fillMaxSize()
//                .background(Color(0xFF2B2D42))
//                .padding(horizontal = 16.dp),
//            verticalAlignment = Alignment.CenterVertically
//        ) {
//            Text("Virdin Panel", color = Color.White)
//            Spacer(Modifier.weight(1f))
//            Text("12:34", color = Color.White)
//        }
//    }
//    bridge.awaitClose()
//}
//
//// ── OSD ───────────────────────────────────────────────────────────────────────
//suspend fun demoOsd(scope: CoroutineScope) {
//    val bridge = waylandOsd(width = 280, height = 80, scope = scope) {
//        Box(
//            modifier = Modifier
//                .fillMaxSize()
//                .background(Color(0xEE000000)),
//            contentAlignment = Alignment.Center
//        ) {
//            Text("🔊  Volume: 75%", color = Color.White)
//        }
//    }
//    delay(2000)
//    bridge.close()
//}
//
//// ── App menu ──────────────────────────────────────────────────────────────────
//suspend fun demoAppMenu(scope: CoroutineScope) {
//    // mutableStateOf so the composable can read the bridge ref reactively —
//    // lateinit crashes because the composable runs during ImageComposeScene
//    // construction, before waylandAppMenu() returns and assigns the variable.
//    val bridgeRef = androidx.compose.runtime.mutableStateOf<WaylandBridge?>(null)
//    val bridge = waylandAppMenu(
//        position = ContentPosition.BOTTOM,
//        width    = 500,
//        height   = 350,
//        scope    = scope
//    ) {
//        println("[JVM] density=${screenDensity().density}")
//        CompositionLocalProvider(LocalWaylandBridge provides bridgeRef.value) {
//            AppMenuContent()
//        }
//    }
//    bridgeRef.value = bridge  // now safe — composable will recompose with real ref
//    bridge.awaitClose()
//}
//
//@Composable
//private fun AppMenuContent() {
//    val bridge = LocalWaylandBridge.current
//    Column(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(Color(0xFF1E1E2E))
//            .padding(16.dp)
//    ) {
//        Text("Applications", color = Color.White, style = MaterialTheme.typography.titleMedium)
//        Spacer(Modifier.height(12.dp))
//        listOf("Files", "Terminal", "Browser", "Settings").forEach { app ->
//            TextButton(onClick = {  }) {
//                // bridge?.close()
//                Text(app, color = Color.White)
//            }
//        }
//    }
//}
//
//// ── Desktop background ────────────────────────────────────────────────────────
//suspend fun demoDesktopBackground(scope: CoroutineScope) {
//    val bridge = waylandDesktopBackground(scope = scope) {
//        Box(
//            modifier = Modifier
//                .fillMaxSize()
//                .background(Color(0xFF0D1117)),
//            contentAlignment = Alignment.Center
//        ) {
//            Text("✨  Virdin Desktop", color = Color(0xFF58A6FF))
//        }
//    }
//    bridge.awaitClose()
//}
//
//// ── Generic / fully custom ────────────────────────────────────────────────────
//suspend fun demoGenericWindow(scope: CoroutineScope) {
//    val config = WindowConfig(
//        layer         = WindowLayer.TOP,
//        anchor        = Anchor.RIGHT or Anchor.TOP,
//        exclusiveZone = 0,
//        keyboardMode  = KeyboardMode.ON_DEMAND,
//        width         = 320,
//        height        = 480,
//        namespace     = "virdin-custom"
//    )
//    val bridge = waylandSurface(config = config, scope = scope) {
//        Box(
//            modifier = Modifier
//                .fillMaxSize()
//                .background(Color(0xCC16213E)),
//            contentAlignment = Alignment.Center
//        ) {
//            Text("Custom Surface", color = Color.White)
//        }
//    }
//    bridge.awaitClose()
//}