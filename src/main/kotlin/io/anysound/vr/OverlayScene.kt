package io.anysound.vr

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Surface
import kotlin.coroutines.CoroutineContext

/** Owns a persistent composition; all rendering and disposal stay on its creating thread. */
@OptIn(InternalComposeUiApi::class)
internal class OverlayScene(coroutineContext: CoroutineContext) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val content = mutableStateOf(HudContent("", ""))
    private val scene = CanvasLayersComposeScene(
        density = Density(1f), size = IntSize(width, height), coroutineContext = coroutineContext,
    )
    private var closed = false
    var isVisible = false
        private set

    init {
        try { scene.setContent { OverlayPanel(content.value) { isVisible = it } } }
        catch (error: Throwable) { scene.close(); throw error }
    }

    fun hasInvalidations(): Boolean = scene.hasInvalidations()

    fun render(surface: Surface, value: HudContent, nanoTime: Long) {
        check(Thread.currentThread() === owner && !closed) { "覆盖层必须在创建它的线程中绘制，且尚未关闭" }
        content.value = value
        // Clear every frame, including alpha, so shorter captions never retain old glyphs.
        surface.canvas.clear(0)
        scene.render(surface.canvas.asComposeCanvas(), nanoTime)
    }

    override fun close() {
        check(Thread.currentThread() === owner) { "覆盖层必须在创建它的线程中关闭" }
        if (!closed) { closed = true; scene.close() }
    }

    companion object { const val width = 1024; const val height = 384 }
}
