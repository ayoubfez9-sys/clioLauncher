package fr.ayoub.cliolauncher.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.MotionEvent
import android.view.TextureView
import android.view.VelocityTracker
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vue 3D de la Clio : fond transparent, rotation lente automatique,
 * rotation au doigt (avec elan), bouton "recentrer".
 * Le rendu tourne sur son propre fil, a 30 images/seconde maximum.
 */
class CarView(context: Context, private val liteAtStart: Boolean) : TextureView(context), TextureView.SurfaceTextureListener {

    companion object {
        const val DEFAULT_YAW = 35f        // vue 3/4 avant
        const val DEFAULT_PITCH = 20f
        const val AUTO_DEG_PER_S = 9f      // rotation automatique
        const val IDLE_MS = 4000L          // pause apres un toucher avant de reprendre la rotation
        const val DEG_PER_PX = 0.45f
        const val LITE_SCALE = 0.7f        // mode allege : 70 % de la resolution
        private const val TAG = "CarView"
    }

    @Volatile var bodyColor: Int = 0xFF1F5FD0.toInt()
    @Volatile var lite: Boolean = liteAtStart
    var onFailed: (() -> Unit)? = null

    private val lock = Any()
    private var pendDx = 0f
    private var pendDy = 0f
    private var dragging = false
    private var newFling: Float? = null
    private var lastTouch = 0L
    private var recenterReq = false

    private var tracker: VelocityTracker? = null
    private var lastX = 0f
    private var lastY = 0f
    private var thread: RenderThread? = null

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun recenter() {
        synchronized(lock) { recenterReq = true; lastTouch = System.currentTimeMillis() }
    }

    // ------------------------------------------------------------------ surface

    private fun bufferSize(w: Int, h: Int): Pair<Int, Int> {
        val s = if (liteAtStart) LITE_SCALE else 1f
        return (w * s).roundToInt().coerceAtLeast(1) to (h * s).roundToInt().coerceAtLeast(1)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val (bw, bh) = bufferSize(width, height)
        st.setDefaultBufferSize(bw, bh)
        thread = RenderThread(st, bw, bh).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        val (bw, bh) = bufferSize(width, height)
        st.setDefaultBufferSize(bw, bh)
        thread?.setSize(bw, bh)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        thread?.finish()
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // ------------------------------------------------------------------ toucher

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = System.currentTimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                lastX = e.x; lastY = e.y
                synchronized(lock) { dragging = true; newFling = 0f; lastTouch = now; recenterReq = false }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                synchronized(lock) { pendDx += dx; pendDy += dy; lastTouch = now }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val t = tracker
                var vx = 0f
                if (t != null) {
                    t.addMovement(e)
                    t.computeCurrentVelocity(1000)
                    vx = t.xVelocity
                    t.recycle()
                }
                tracker = null
                synchronized(lock) { dragging = false; newFling = vx * DEG_PER_PX; lastTouch = now }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ rendu

    private inner class RenderThread(
        private val surfaceTexture: SurfaceTexture,
        @Volatile private var w: Int,
        @Volatile private var h: Int,
    ) : Thread("ClioRender") {

        @Volatile private var running = true

        fun setSize(width: Int, height: Int) { w = width; h = height }

        fun finish() {
            running = false
            try { join(1500) } catch (e: InterruptedException) { }
        }

        override fun run() {
            val egl = Egl()
            try {
                if (!egl.init(surfaceTexture)) { fail("EGL indisponible"); return }
                val renderer = CarRenderer(GlbLoader.get(context))
                renderer.init()

                var yaw = DEFAULT_YAW
                var pitch = DEFAULT_PITCH
                var spin = 0f
                var recentering = false
                var last = System.nanoTime()
                val frameNs = 1_000_000_000L / 30

                while (running) {
                    val start = System.nanoTime()
                    val dt = ((start - last) / 1e9f).coerceIn(0f, 0.1f)
                    last = start

                    var dx: Float; var dy: Float; var drag: Boolean; var fling: Float?; var touched: Long; var rec: Boolean
                    synchronized(lock) {
                        dx = pendDx; dy = pendDy; pendDx = 0f; pendDy = 0f
                        drag = dragging; fling = newFling; newFling = null
                        touched = lastTouch; rec = recenterReq; recenterReq = false
                    }
                    fling?.let { spin = it }
                    if (rec) { recentering = true; spin = 0f }

                    if (drag) {
                        recentering = false
                        yaw += dx * DEG_PER_PX
                        pitch = (pitch + dy * 0.15f).coerceIn(8f, 40f)
                    } else if (recentering) {
                        val target = DEFAULT_YAW + Math.round((yaw - DEFAULT_YAW) / 360f) * 360f
                        val k = min(1f, dt * 5f)
                        yaw += (target - yaw) * k
                        pitch += (DEFAULT_PITCH - pitch) * k
                        if (abs(target - yaw) < 0.3f && abs(DEFAULT_PITCH - pitch) < 0.3f) recentering = false
                    } else {
                        yaw += spin * dt
                        spin *= exp(-dt * 2.5f)
                        val idle = System.currentTimeMillis() - touched - IDLE_MS
                        if (idle > 0) yaw += AUTO_DEG_PER_S * dt * min(1f, idle / 1500f)
                    }
                    yaw %= 360f

                    renderer.draw(w, h, yaw, pitch, bodyColor, lite)
                    if (!egl.swap()) break

                    val spent = System.nanoTime() - start
                    if (spent < frameNs) sleep((frameNs - spent) / 1_000_000L)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Erreur 3D", t)
                fail(t.message ?: "erreur")
            } finally {
                egl.release()
            }
        }

        private fun fail(msg: String) {
            Log.e(TAG, msg)
            post { onFailed?.invoke() }
        }
    }
}

/** Mise en place minimale d'OpenGL ES 3 sur une SurfaceTexture. */
private class Egl {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    fun init(window: Any): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false
        // Anti-crenelage 4x (presque gratuit sur les GPU Mali), sinon sans
        val config = choose(4) ?: choose(0) ?: return false
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) return false
        surface = EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) return false
        return EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    private fun choose(samples: Int): EGLConfig? {
        val attrs = mutableListOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
        )
        if (samples > 0) attrs += listOf(EGL14.EGL_SAMPLE_BUFFERS, 1, EGL14.EGL_SAMPLES, samples)
        attrs += EGL14.EGL_NONE
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs.toIntArray(), 0, configs, 0, 1, n, 0) || n[0] == 0) return null
        return configs[0]
    }

    fun swap(): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        // pas de eglTerminate : l'affichage EGL est partage avec le reste de l'appli
        display = EGL14.EGL_NO_DISPLAY
    }
}
