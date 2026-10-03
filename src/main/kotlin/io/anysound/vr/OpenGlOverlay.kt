package io.anysound.vr

import org.jetbrains.skia.*
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C.*
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext

/** Windows-only hidden GL context. No desktop swapchain access or CPU pixel readback. */
internal class OpenGlOverlay(coroutineContext: CoroutineContext) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var glfwInitialized = false
    private var window = 0L
    private var glReady = false
    private var framebuffer = 0
    private var stencil = 0
    var textureId = 0
        private set
    private var context: DirectContext? = null
    private var target: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var scene: OverlayScene? = null

    init {
        try {
            check(glfwInit()) { "无法初始化覆盖层 OpenGL，请检查显卡驱动" }
            glfwInitialized = true
            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
            window = glfwCreateWindow(1, 1, "AnySound overlay renderer", 0L, 0L)
            check(window != 0L) { "无法创建 OpenGL 3.3 覆盖层，请让 AnySound 与 SteamVR 使用同一独立显卡" }
            glfwMakeContextCurrent(window)
            GL.createCapabilities()
            glReady = true

            textureId = glGenTextures()
            glBindTexture(GL_TEXTURE_2D, textureId)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, OverlayScene.width, OverlayScene.height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, null as ByteBuffer?)
            framebuffer = glGenFramebuffers()
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textureId, 0)
            stencil = glGenRenderbuffers()
            glBindRenderbuffer(GL_RENDERBUFFER, stencil)
            glRenderbufferStorage(GL_RENDERBUFFER, GL_STENCIL_INDEX8, OverlayScene.width, OverlayScene.height)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, stencil)
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "覆盖层 OpenGL 帧缓冲创建失败" }

            val directContext = DirectContext.makeGL().also { context = it }
            val renderTarget = BackendRenderTarget.makeGL(OverlayScene.width, OverlayScene.height, 0, 8, framebuffer, GL_RGBA8).also { target = it }
            surface = checkNotNull(Surface.makeFromBackendRenderTarget(directContext, renderTarget,
                SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB)) { "无法创建 Compose GPU 绘制表面" }
            scene = OverlayScene(coroutineContext)
        } catch (error: Throwable) { close(); throw error }
    }

    fun hasInvalidations(): Boolean = scene?.hasInvalidations() == true
    val isVisible: Boolean get() = scene?.isVisible == true

    fun pollEvents() = glfwPollEvents()

    fun render(content: HudContent, nanoTime: Long) {
        check(Thread.currentThread() === owner && window != 0L) { "OpenGL 覆盖层线程或生命周期无效" }
        // OpenVR may change GL state during submission; invalidate Skia's cached state.
        checkNotNull(context).resetGLAll()
        val canvasSurface = checkNotNull(surface)
        checkNotNull(scene).render(canvasSurface, content, nanoTime)
        // Complete writes before OpenVR accesses the texture in its own context.
        // This small HUD redraws only for content changes or Compose animation frames.
        canvasSurface.flushAndSubmit(syncCpu = true)
    }

    override fun close() {
        check(Thread.currentThread() === owner) { "OpenGL 覆盖层必须在创建它的线程中关闭" }
        // SteamVr clears the submitted texture before this destroys any native resources.
        try { scene?.close() } finally {
            scene = null
            try { surface?.close() } finally {
                surface = null
                try { target?.close() } finally {
                    target = null
                    try { context?.close() } finally {
                        context = null
                        if (glReady) {
                            glDeleteFramebuffers(framebuffer)
                            glDeleteRenderbuffers(stencil)
                            glDeleteTextures(textureId)
                            GL.setCapabilities(null)
                            glReady = false
                        }
                        if (window != 0L) { glfwMakeContextCurrent(0L); glfwDestroyWindow(window); window = 0L }
                        if (glfwInitialized) { glfwTerminate(); glfwInitialized = false }
                    }
                }
            }
        }
    }
}
