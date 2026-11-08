package fr.ayoub.cliolauncher.render

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Dessine le plateau lumineux et la Clio. S'execute sur le fil OpenGL. */
class CarRenderer(private val model: CarModel) {

    companion object {
        // Memes valeurs que l'apercu WebGL
        const val PLATE_R = 2.55f
        const val FLOOR_EXT = 1.75f
        const val FOV = 30f
        const val DIST = 12.5f
        const val TARGET_Y = 0.6f
        private val ACCENT = floatArrayOf(46f / 255f, 139f / 255f, 1f)
    }

    private var carProg = 0
    private var floorProg = 0
    private lateinit var vaos: IntArray
    private var floorVao = 0
    private lateinit var baseModels: Array<FloatArray>

    // emplacements des "uniforms"
    private var uModel = 0; private var uVP = 0; private var uCam = 0; private var uBase = 0
    private var uRough = 0; private var uMetal = 0; private var uCoat = 0; private var uEmissive = 0
    private var uAlpha = 0; private var uLite = 0; private var uAccentC = 0
    private var fVP = 0; private var fExt = 0; private var fR = 0; private var fAccent = 0
    private var fYaw = 0; private var fHalf = 0; private var fLite = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val yawM = FloatArray(16)
    private val modelM = FloatArray(16)
    private val accentLin = FloatArray(3) { ACCENT[it].pow(2.2f) }

    fun init() {
        carProg = program(Shaders.CAR_VS, Shaders.CAR_FS, arrayOf("aPos", "aNormal"))
        floorProg = program(Shaders.FLOOR_VS, Shaders.FLOOR_FS, arrayOf("aXZ"))

        uModel = loc(carProg, "uModel"); uVP = loc(carProg, "uViewProj"); uCam = loc(carProg, "uCam")
        uBase = loc(carProg, "uBase"); uRough = loc(carProg, "uRough"); uMetal = loc(carProg, "uMetal")
        uCoat = loc(carProg, "uCoat"); uEmissive = loc(carProg, "uEmissive"); uAlpha = loc(carProg, "uAlpha")
        uLite = loc(carProg, "uLite"); uAccentC = loc(carProg, "uAccent")
        fVP = loc(floorProg, "uViewProj"); fExt = loc(floorProg, "uExt"); fR = loc(floorProg, "uR")
        fAccent = loc(floorProg, "uAccent"); fYaw = loc(floorProg, "uYaw"); fHalf = loc(floorProg, "uHalf")
        fLite = loc(floorProg, "uLite")

        val prims = model.prims
        vaos = IntArray(prims.size)
        GLES30.glGenVertexArrays(prims.size, vaos, 0)
        val bufs = IntArray(prims.size * 2)
        GLES30.glGenBuffers(bufs.size, bufs, 0)
        prims.forEachIndexed { i, p ->
            GLES30.glBindVertexArray(vaos[i])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufs[i * 2])
            val vb = ByteBuffer.allocateDirect(p.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vb.put(p.vertices).position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, p.vertices.size * 4, vb, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, bufs[i * 2 + 1])
            val ib = ByteBuffer.allocateDirect(p.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            ib.put(p.indices).position(0)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, p.indices.size * 2, ib, GLES30.GL_STATIC_DRAW)
        }
        baseModels = Array(prims.size) { Mat4.mul(model.normalize, prims[it].world) }

        val fv = IntArray(1); val fb = IntArray(1)
        GLES30.glGenVertexArrays(1, fv, 0); GLES30.glGenBuffers(1, fb, 0)
        floorVao = fv[0]
        GLES30.glBindVertexArray(floorVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, fb[0])
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val qb = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        qb.put(quad).position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, qb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindVertexArray(0)
    }

    fun draw(width: Int, height: Int, yawDeg: Float, pitchDeg: Float, bodyColor: Int, lite: Boolean) {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // Camera fixe, c'est la voiture (et son ombre) qui tourne
        val p = Math.toRadians(pitchDeg.toDouble())
        val ex = 0f
        val ey = TARGET_Y + DIST * sin(p).toFloat()
        val ez = DIST * cos(p).toFloat()
        Matrix.perspectiveM(proj, 0, FOV, width.toFloat() / height.coerceAtLeast(1), 0.5f, 60f)
        Matrix.setLookAtM(view, 0, ex, ey, ez, 0f, TARGET_Y, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.setRotateM(yawM, 0, yawDeg, 0f, 1f, 0f)
        val liteI = if (lite) 1 else 0

        // 1. Plateau + anneau + ombre
        GLES30.glUseProgram(floorProg)
        GLES30.glDepthMask(false)
        GLES30.glUniformMatrix4fv(fVP, 1, false, vp, 0)
        GLES30.glUniform1f(fExt, PLATE_R * FLOOR_EXT)
        GLES30.glUniform1f(fR, PLATE_R)
        GLES30.glUniform3fv(fAccent, 1, ACCENT, 0)
        val yr = Math.toRadians(yawDeg.toDouble())
        GLES30.glUniform2f(fYaw, cos(yr).toFloat(), sin(yr).toFloat())
        GLES30.glUniform2f(fHalf, model.halfWidth, model.halfLength)
        GLES30.glUniform1i(fLite, liteI)
        GLES30.glBindVertexArray(floorVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDepthMask(true)

        // 2. Voiture : pieces opaques puis vitres
        GLES30.glUseProgram(carProg)
        GLES30.glUniformMatrix4fv(uVP, 1, false, vp, 0)
        GLES30.glUniform3f(uCam, ex, ey, ez)
        GLES30.glUniform1i(uLite, liteI)
        GLES30.glUniform3fv(uAccentC, 1, accentLin, 0)
        GLES30.glUniform3f(uEmissive, 0f, 0f, 0f)
        val r = ((bodyColor shr 16) and 0xFF) / 255f
        val g = ((bodyColor shr 8) and 0xFF) / 255f
        val b = (bodyColor and 0xFF) / 255f
        val paint = floatArrayOf(r.pow(2.2f), g.pow(2.2f), b.pow(2.2f))

        for (pass in 0..1) {
            model.prims.forEachIndexed { i, prim ->
                val m = prim.material
                val glass = m.alpha < 1f
                if (glass != (pass == 1)) return@forEachIndexed
                GLES30.glDepthMask(!glass)
                Matrix.multiplyMM(modelM, 0, yawM, 0, baseModels[i], 0)
                GLES30.glUniformMatrix4fv(uModel, 1, false, modelM, 0)
                if (m.paint) GLES30.glUniform3fv(uBase, 1, paint, 0) else GLES30.glUniform3fv(uBase, 1, m.base, 0)
                GLES30.glUniform1f(uRough, m.rough)
                GLES30.glUniform1f(uMetal, m.metal)
                GLES30.glUniform1f(uCoat, m.coat)
                GLES30.glUniform1f(uAlpha, m.alpha)
                GLES30.glBindVertexArray(vaos[i])
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, prim.indices.size, GLES30.GL_UNSIGNED_SHORT, 0)
            }
        }
        GLES30.glDepthMask(true)
        GLES30.glBindVertexArray(0)
    }

    private fun loc(prog: Int, name: String) = GLES30.glGetUniformLocation(prog, name)

    private fun program(vs: String, fs: String, attribs: Array<String>): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        attribs.forEachIndexed { i, a -> GLES30.glBindAttribLocation(p, i, a) }
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("Link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("Shader: " + GLES30.glGetShaderInfoLog(s))
        return s
    }
}
