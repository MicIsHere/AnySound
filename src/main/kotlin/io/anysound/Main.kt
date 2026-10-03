package io.anysound

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.anysound.ui.AnySoundTheme
import io.anysound.ui.MainScreen
import kotlinx.coroutines.launch

fun main() = application {
    val controller = remember { AppController() }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    Window(
        title = "AnySound",
        state = rememberWindowState(width = 1100.dp, height = 800.dp),
        onCloseRequest = {
            if (!closing) {
                closing = true
                scope.launch { try { controller.close() } finally { exitApplication() } }
            }
        },
    ) {
        AnySoundTheme { MainScreen(controller, closing) }
    }
}
