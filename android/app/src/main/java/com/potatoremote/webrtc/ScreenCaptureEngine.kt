package com.potatoremote.webrtc

import android.content.Context
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.potatoremote.signaling.CaptureQuality
import org.webrtc.EglBase
import org.webrtc.TextureBufferImpl
import org.webrtc.VideoFrame
import org.webrtc.YuvConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Capture d'écran MediaProjection rendue par OpenGL.
 *
 * Une seule capture alimente un encodeur par spectateur : chaque spectateur
 * reçoit un cadre à SON format (portrait, 4:3, 16:9…), sans déformation.
 * Le mode « fit » ajoute des bandes floues, le mode « fill » recadre légèrement.
 */
class ScreenCaptureEngine(
    private val context: Context,
    private val projection: MediaProjection,
    initialQuality: CaptureQuality,
) {
    enum class FitMode {
        FIT,
        FILL;

        companion object {
            fun from(raw: String?): FitMode = if (raw == "fill") FILL else FIT
        }
    }

    data class OutputSpec(val width: Int, val height: Int, val mode: FitMode)

    fun interface FrameConsumer {
        fun onFrame(buffer: VideoFrame.I420Buffer, timestampNs: Long)
    }

    interface Attachment {
        fun update(spec: OutputSpec)
        fun close()
    }

    private val thread = HandlerThread("PotatoCapture").apply { start() }
    private val handler = Handler(thread.looper)
    private val renderers = CopyOnWriteArrayList<Renderer>()

    private lateinit var eglBase: EglBase
    private var oesTextureId = 0
    private var sharedTextureId = 0
    private var sharedFboId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var displaySurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var displayManager: DisplayManager? = null

    private var oesProgram: Program? = null
    private var plainProgram: Program? = null
    private var blurProgram: Program? = null

    private val ndcQuad = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uvQuad = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val unitQuad = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    private val textureMatrix = FloatArray(16)
    private var captureWidth = 0
    private var captureHeight = 0
    private var lastFrameAt = 0L
    private var running = false
    private var shortSide = initialQuality.shortSide

    @Volatile
    var screenWidth = 0
        private set

    @Volatile
    var screenHeight = 0
        private set

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                handler.postDelayed({ resizeCapture(force = false) }, 300)
            }
        }
    }

    fun start() {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                initialize()
            } catch (error: Throwable) {
                failure = error
                Log.e(TAG, "Initialisation de la capture impossible", error)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(8, TimeUnit.SECONDS)) {
            throw IllegalStateException("La capture d'écran n'a pas démarré à temps.")
        }
        failure?.let { throw IllegalStateException("Capture d'écran impossible : ${it.message}", it) }
    }

    private fun initialize() {
        val screen = screenSize()
        screenWidth = screen.x
        screenHeight = screen.y
        val density = context.resources.displayMetrics.densityDpi

        // EglBase.makeCurrent() exige une EGLSurface, même lorsque le rendu
        // se fait uniquement dans des framebuffers hors écran. Un pbuffer 1x1
        // fournit cette surface sans dépendre d'une fenêtre ou d'un SurfaceTexture.
        eglBase = EglBase.create(null, EglBase.CONFIG_PIXEL_BUFFER)
        eglBase.createDummyPbufferSurface()
        eglBase.makeCurrent()

        val size = computeCaptureSize(screen)
        captureWidth = size.first
        captureHeight = size.second

        oesTextureId = createExternalTexture()
        val texture = SurfaceTexture(oesTextureId)
        texture.setDefaultBufferSize(captureWidth, captureHeight)
        texture.setOnFrameAvailableListener({ handler.post { onFrameAvailable() } }, handler)
        surfaceTexture = texture
        displaySurface = Surface(texture)

        virtualDisplay = projection.createVirtualDisplay(
            "Potato Remote",
            captureWidth,
            captureHeight,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            displaySurface,
            null,
            handler
        ) ?: throw IllegalStateException("Android n'a pas créé l'affichage de capture.")

        createSharedTarget(captureWidth, captureHeight)
        oesProgram = createProgram(OES_VERTEX_SHADER, OES_FRAGMENT_SHADER, needsTexMatrix = true)
        plainProgram = createProgram(QUAD_VERTEX_SHADER, PLAIN_FRAGMENT_SHADER, needsTexMatrix = false)
        blurProgram = createProgram(QUAD_VERTEX_SHADER, BLUR_FRAGMENT_SHADER, needsTexMatrix = false)

        displayManager = context.getSystemService(DisplayManager::class.java)
        displayManager?.registerDisplayListener(displayListener, handler)
        running = true
    }

    fun setQuality(quality: CaptureQuality) {
        shortSide = quality.shortSide
        handler.post { resizeCapture(force = true) }
    }

    fun attach(spec: OutputSpec, consumer: FrameConsumer): Attachment {
        val renderer = Renderer(spec, consumer)
        handler.post {
            if (!running) return@post
            try {
                renderer.setup()
                renderers.add(renderer)
            } catch (error: Throwable) {
                Log.e(TAG, "Impossible de créer le rendu spectateur", error)
            }
        }
        return object : Attachment {
            override fun update(spec: OutputSpec) {
                handler.post { renderer.update(spec) }
            }

            override fun close() {
                handler.post {
                    renderers.remove(renderer)
                    renderer.release()
                }
            }
        }
    }

    private fun screenSize(): Point {
        val manager = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = manager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealSize(point)
            point
        }
    }

    private fun computeCaptureSize(screen: Point): Pair<Int, Int> {
        val shortEdge = min(screen.x, screen.y).coerceAtLeast(2)
        val target = min(shortSide, shortEdge)
        val scale = target.toDouble() / shortEdge
        var width = (screen.x * scale).roundToInt()
        var height = (screen.y * scale).roundToInt()
        val longCap = 2400
        if (max(width, height) > longCap) {
            val shrink = longCap.toDouble() / max(width, height)
            width = (width * shrink).roundToInt()
            height = (height * shrink).roundToInt()
        }
        return even(width) to even(height)
    }

    private fun resizeCapture(force: Boolean) {
        if (!running) return
        val screen = screenSize()
        screenWidth = screen.x
        screenHeight = screen.y
        val size = computeCaptureSize(screen)
        if (!force && size.first == captureWidth && size.second == captureHeight) return
        if (size.first == captureWidth && size.second == captureHeight) {
            surfaceTexture?.setDefaultBufferSize(captureWidth, captureHeight)
            virtualDisplay?.resize(captureWidth, captureHeight, context.resources.displayMetrics.densityDpi)
            return
        }
        captureWidth = size.first
        captureHeight = size.second
        surfaceTexture?.setDefaultBufferSize(captureWidth, captureHeight)
        virtualDisplay?.resize(captureWidth, captureHeight, context.resources.displayMetrics.densityDpi)
        deleteSharedTarget()
        createSharedTarget(captureWidth, captureHeight)
        Log.i(TAG, "Capture redimensionnée : ${captureWidth}x$captureHeight")
    }

    private fun onFrameAvailable() {
        if (!running) return
        val texture = surfaceTexture ?: return
        try {
            synchronized(EglBase.lock) { texture.updateTexImage() }
        } catch (error: Throwable) {
            Log.w(TAG, "updateTexImage a échoué", error)
            return
        }
        val timestampNs = texture.timestamp.takeIf { it > 0 } ?: SystemClock.elapsedRealtimeNanos()
        val now = SystemClock.elapsedRealtimeNanos()
        if (lastFrameAt != 0L && now - lastFrameAt < FRAME_INTERVAL_NS) return
        lastFrameAt = now

        eglBase.makeCurrent()
        texture.getTransformMatrix(textureMatrix)
        drawScreenToShared()

        for (renderer in renderers) {
            try {
                renderer.render(sharedTextureId, captureWidth, captureHeight, timestampNs)
            } catch (error: Throwable) {
                Log.w(TAG, "Rendu spectateur en échec", error)
            }
        }
    }

    private fun drawScreenToShared() {
        val program = oesProgram ?: return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sharedFboId)
        GLES20.glViewport(0, 0, captureWidth, captureHeight)
        GLES20.glClearColor(0.055f, 0.035f, 0.02f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(program.texLoc, 0)
        GLES20.glUniformMatrix4fv(program.texMatrixLoc, 1, false, textureMatrix, 0)

        GLES20.glEnableVertexAttribArray(program.posLoc)
        GLES20.glVertexAttribPointer(program.posLoc, 2, GLES20.GL_FLOAT, false, 0, ndcQuad)
        GLES20.glEnableVertexAttribArray(program.uvLoc)
        GLES20.glVertexAttribPointer(program.uvLoc, 2, GLES20.GL_FLOAT, false, 0, uvQuad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun createSharedTarget(width: Int, height: Int) {
        sharedTextureId = createTexture2D(width, height)
        sharedFboId = createFramebuffer(sharedTextureId)
    }

    private fun deleteSharedTarget() {
        if (sharedFboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(sharedFboId), 0)
            sharedFboId = 0
        }
        if (sharedTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(sharedTextureId), 0)
            sharedTextureId = 0
        }
    }

    fun stop() {
        running = false
        val latch = CountDownLatch(1)
        handler.post {
            try {
                displayManager?.unregisterDisplayListener(displayListener)
                displayManager = null
                renderers.forEach { it.release() }
                renderers.clear()
                virtualDisplay?.release()
                virtualDisplay = null
                displaySurface?.release()
                displaySurface = null
                surfaceTexture?.release()
                surfaceTexture = null
                deleteSharedTarget()
                if (oesTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                    oesTextureId = 0
                }
                oesProgram?.release()
                plainProgram?.release()
                blurProgram?.release()
                oesProgram = null
                plainProgram = null
                blurProgram = null
                if (::eglBase.isInitialized) eglBase.release()
            } catch (error: Throwable) {
                Log.w(TAG, "Nettoyage de la capture", error)
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private inner class Renderer(
        private var spec: OutputSpec,
        private val consumer: FrameConsumer,
    ) {
        private var textureId = 0
        private var fboId = 0
        private var width = 0
        private var height = 0
        private var converter: YuvConverter? = null
        private var released = false

        fun setup() {
            createTarget()
            converter = YuvConverter()
        }

        fun update(newSpec: OutputSpec) {
            if (released || newSpec == spec) return
            spec = newSpec
            deleteTarget()
            createTarget()
        }

        fun render(sharedTexture: Int, sharedWidth: Int, sharedHeight: Int, timestampNs: Long) {
            if (released || width <= 0 || height <= 0) return
            val yuv = converter ?: return
            val plain = plainProgram ?: return
            val blur = blurProgram ?: return

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0.055f, 0.035f, 0.02f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val content = if (spec.mode == FitMode.FILL) {
                coverRect(width, height, sharedWidth, sharedHeight)
            } else {
                containRect(width, height, sharedWidth, sharedHeight)
            }
            val fillsFrame = content.left <= 0.5f && content.top <= 0.5f &&
                content.right >= width - 0.5f && content.bottom >= height - 0.5f

            if (!fillsFrame) {
                draw2D(blur, sharedTexture, coverRect(width, height, sharedWidth, sharedHeight), sharedWidth, sharedHeight, darken = 0.5f)
            }
            draw2D(plain, sharedTexture, content, sharedWidth, sharedHeight, darken = 0f)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            val buffer = TextureBufferImpl(
                width,
                height,
                VideoFrame.TextureBuffer.Type.RGB,
                textureId,
                Matrix(),
                handler,
                yuv,
                Runnable { }
            )
            val i420 = yuv.convert(buffer) ?: return
            consumer.onFrame(i420, timestampNs)
        }

        private fun draw2D(program: Program, texture: Int, rect: RectF, sharedWidth: Int, sharedHeight: Int, darken: Float) {
            GLES20.glUseProgram(program.id)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(program.texLoc, 0)
            val x0 = rect.left / width * 2f - 1f
            val x1 = rect.right / width * 2f - 1f
            val y0 = 1f - rect.bottom / height * 2f
            val y1 = 1f - rect.top / height * 2f
            GLES20.glUniform4f(program.rectLoc, x0, y0, x1, y1)
            if (program.texelLoc >= 0) {
                GLES20.glUniform2f(program.texelLoc, 2.2f / sharedWidth, 2.2f / sharedHeight)
            }
            if (program.darkenLoc >= 0) {
                GLES20.glUniform1f(program.darkenLoc, darken)
            }
            GLES20.glEnableVertexAttribArray(program.posLoc)
            GLES20.glVertexAttribPointer(program.posLoc, 2, GLES20.GL_FLOAT, false, 0, unitQuad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun createTarget() {
            width = spec.width.coerceAtLeast(2)
            height = spec.height.coerceAtLeast(2)
            textureId = createTexture2D(width, height)
            fboId = createFramebuffer(textureId)
        }

        private fun deleteTarget() {
            if (fboId != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                fboId = 0
            }
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
        }

        fun release() {
            if (released) return
            released = true
            deleteTarget()
            converter?.release()
            converter = null
        }
    }

    private class Program(
        val id: Int,
        val posLoc: Int,
        val uvLoc: Int,
        val texLoc: Int,
        val rectLoc: Int,
        val texMatrixLoc: Int,
        val texelLoc: Int,
        val darkenLoc: Int,
    ) {
        fun release() {
            GLES20.glDeleteProgram(id)
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String, needsTexMatrix: Boolean): Program {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            throw IllegalStateException("Lien de programme OpenGL impossible : $log")
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return Program(
            id = id,
            posLoc = GLES20.glGetAttribLocation(id, "aPosition"),
            uvLoc = GLES20.glGetAttribLocation(id, "aTextureCoord"),
            texLoc = GLES20.glGetUniformLocation(id, "uTex"),
            rectLoc = GLES20.glGetUniformLocation(id, "uRect"),
            texMatrixLoc = if (needsTexMatrix) GLES20.glGetUniformLocation(id, "uTexMatrix") else -1,
            texelLoc = GLES20.glGetUniformLocation(id, "uTexel"),
            darkenLoc = GLES20.glGetUniformLocation(id, "uDarken"),
        )
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Compilation de shader impossible : $log")
        }
        return shader
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun createTexture2D(width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        return ids[0]
    }

    private fun createFramebuffer(textureId: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, textureId, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw IllegalStateException("Framebuffer OpenGL incomplet ($status)")
        }
        return ids[0]
    }

    companion object {
        private const val TAG = "PotatoCapture"
        private const val FRAME_INTERVAL_NS = 33_333_333L

        private fun even(value: Int): Int = (value.coerceAtLeast(2) / 2) * 2

        private fun floatBuffer(vararg values: Float): FloatBuffer {
            val buffer = ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            buffer.put(values)
            buffer.position(0)
            return buffer
        }

        fun containRect(outWidth: Int, outHeight: Int, inWidth: Int, inHeight: Int): RectF {
            val scale = min(outWidth.toFloat() / inWidth, outHeight.toFloat() / inHeight)
            val width = inWidth * scale
            val height = inHeight * scale
            val left = (outWidth - width) / 2f
            val top = (outHeight - height) / 2f
            return RectF(left, top, left + width, top + height)
        }

        fun coverRect(outWidth: Int, outHeight: Int, inWidth: Int, inHeight: Int): RectF {
            val scale = max(outWidth.toFloat() / inWidth, outHeight.toFloat() / inHeight)
            val width = inWidth * scale
            val height = inHeight * scale
            val left = (outWidth - width) / 2f
            val top = (outHeight - height) / 2f
            return RectF(left, top, left + width, top + height)
        }

        private const val OES_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTextureCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTextureCoord = (uTexMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy;
            }
        """

        private const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTextureCoord);
            }
        """

        private const val QUAD_VERTEX_SHADER = """
            attribute vec2 aPosition;
            uniform vec4 uRect;
            varying vec2 vUv;
            void main() {
                vUv = aPosition;
                vec2 point = mix(uRect.xy, uRect.zw, aPosition);
                gl_Position = vec4(point, 0.0, 1.0);
            }
        """

        private const val PLAIN_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            void main() {
                gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0);
            }
        """

        private const val BLUR_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            uniform float uDarken;
            void main() {
                vec3 color = texture2D(uTex, vUv).rgb * 0.36;
                color += texture2D(uTex, vUv + vec2(uTexel.x, 0.0)).rgb * 0.16;
                color += texture2D(uTex, vUv - vec2(uTexel.x, 0.0)).rgb * 0.16;
                color += texture2D(uTex, vUv + vec2(0.0, uTexel.y)).rgb * 0.16;
                color += texture2D(uTex, vUv - vec2(0.0, uTexel.y)).rgb * 0.16;
                color *= (1.0 - uDarken);
                gl_FragColor = vec4(color, 1.0);
            }
        """
    }
}
