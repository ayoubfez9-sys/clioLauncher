package fr.ayoub.cliolauncher.render

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Matiere simplifiee d'une piece de la voiture. */
class Material(
    val paint: Boolean,          // true = couleur de carrosserie choisie dans les Reglages
    val base: FloatArray,        // couleur lineaire RGB
    val rough: Float,
    val metal: Float,
    val coat: Float,
    val alpha: Float,
    val light: Int = 0,          // 1 = phare avant, 2 = feu arriere (s'allument avec les feux)
)

/** Une piece a dessiner : sommets (position + normale), triangles, position dans la voiture. */
class Primitive(
    val node: String,
    val vertices: FloatArray,    // x, y, z, nx, ny, nz
    val indices: ShortArray,
    val world: FloatArray,       // matrice 4x4 (colonnes)
    val material: Material,
)

class CarModel(
    val prims: List<Primitive>,
    val normalize: FloatArray,   // centre la voiture, la pose sur le plateau, longueur = CAR_LENGTH
    val halfWidth: Float,
    val halfLength: Float,
)

/** Petites operations de matrices 4x4 (ordre colonnes, comme OpenGL). */
object Mat4 {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(16)
        for (i in 0 until 4) for (j in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + j] * b[i * 4 + k]
            o[i * 4 + j] = s
        }
        return o
    }

    fun transformZ(m: FloatArray, x: Float, y: Float, z: Float) = m[2] * x + m[6] * y + m[10] * z + m[14]
}

/** Lecture d'un fichier .glb (glTF binaire). */
object GlbLoader {
    const val CAR_LENGTH = 4f

    @Volatile private var cached: CarModel? = null

    /** Le modele est lu une seule fois puis garde en memoire. */
    @Synchronized
    fun get(ctx: Context): CarModel {
        cached?.let { return it }
        val bytes = ctx.assets.open("models/clio4.glb").use { it.readBytes() }
        return load(bytes).also { cached = it }
    }

    fun load(bytes: ByteArray): CarModel {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.getInt(0) == 0x46546C67) { "Ce fichier n'est pas un .glb" }
        val jsonLen = bb.getInt(12)
        val json = JSONObject(String(bytes, 20, jsonLen, Charsets.UTF_8))
        val bin = 20 + jsonLen + 8

        val nodes = json.getJSONArray("nodes")
        val meshes = json.getJSONArray("meshes")
        val accessors = json.getJSONArray("accessors")
        val views = json.getJSONArray("bufferViews")
        val materials = json.optJSONArray("materials") ?: JSONArray()

        fun accInfo(i: Int): Triple<JSONObject, Int, Int> {
            val a = accessors.getJSONObject(i)
            val bv = views.getJSONObject(a.getInt("bufferView"))
            val off = bin + bv.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
            return Triple(a, off, bv.optInt("byteStride", 0))
        }

        val prims = ArrayList<Primitive>()

        fun walk(index: Int, parent: FloatArray) {
            val n = nodes.getJSONObject(index)
            val world = Mat4.mul(parent, localMatrix(n))
            val name = n.optString("name", "")
            if (n.has("mesh")) {
                val primArr = meshes.getJSONObject(n.getInt("mesh")).getJSONArray("primitives")
                for (p in 0 until primArr.length()) {
                    val pr = primArr.getJSONObject(p)
                    val attrs = pr.getJSONObject("attributes")
                    if (!attrs.has("POSITION") || !attrs.has("NORMAL") || !pr.has("indices")) continue
                    val (pa, po, ps) = accInfo(attrs.getInt("POSITION"))
                    val (_, no, ns) = accInfo(attrs.getInt("NORMAL"))
                    val count = pa.getInt("count")
                    val pStride = if (ps == 0) 12 else ps
                    val nStride = if (ns == 0) 12 else ns
                    val v = FloatArray(count * 6)
                    for (i in 0 until count) for (k in 0 until 3) {
                        v[i * 6 + k] = bb.getFloat(po + i * pStride + k * 4)
                        v[i * 6 + 3 + k] = bb.getFloat(no + i * nStride + k * 4)
                    }
                    val (ia, io, _) = accInfo(pr.getInt("indices"))
                    val ic = ia.getInt("count")
                    val type = ia.getInt("componentType")
                    val idx = ShortArray(ic) { i ->
                        when (type) {
                            5125 -> bb.getInt(io + i * 4).toShort()
                            5121 -> (bb.get(io + i).toInt() and 0xFF).toShort()
                            else -> bb.getShort(io + i * 2)
                        }
                    }
                    val matJson = if (pr.has("material")) materials.optJSONObject(pr.getInt("material")) else null
                    val matName = matJson?.optString("name", "") ?: ""
                    val baseArr = matJson?.optJSONObject("pbrMetallicRoughness")?.optJSONArray("baseColorFactor")
                    val base = floatArrayOf(
                        baseArr?.optDouble(0, 0.8)?.toFloat() ?: 0.8f,
                        baseArr?.optDouble(1, 0.8)?.toFloat() ?: 0.8f,
                        baseArr?.optDouble(2, 0.8)?.toFloat() ?: 0.8f,
                    )
                    prims += Primitive(name, v, idx, world, materialFor(matName, name, base))
                }
            }
            val ch = n.optJSONArray("children") ?: return
            for (c in 0 until ch.length()) walk(ch.getInt(c), world)
        }

        val scenes = json.getJSONArray("scenes")
        val roots = scenes.getJSONObject(json.optInt("scene", 0)).getJSONArray("nodes")
        for (r in 0 until roots.length()) walk(roots.getInt(r), Mat4.identity())

        splitLensesFrontRear(prims)

        // Boite englobante dans l'espace du modele
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (p in prims) {
            val m = p.world
            var i = 0
            while (i < p.vertices.size) {
                val x = p.vertices[i]; val y = p.vertices[i + 1]; val z = p.vertices[i + 2]
                val wx = m[0] * x + m[4] * y + m[8] * z + m[12]
                val wy = m[1] * x + m[5] * y + m[9] * z + m[13]
                val wz = m[2] * x + m[6] * y + m[10] * z + m[14]
                minX = min(minX, wx); maxX = max(maxX, wx)
                minY = min(minY, wy); maxY = max(maxY, wy)
                minZ = min(minZ, wz); maxZ = max(maxZ, wz)
                i += 6
            }
        }
        val s = CAR_LENGTH / (maxZ - minZ)
        val norm = Mat4.identity().also {
            it[0] = s; it[5] = s; it[10] = s
            it[12] = -s * (minX + maxX) / 2f
            it[13] = -s * minY
            it[14] = -s * (minZ + maxZ) / 2f
        }
        return CarModel(prims, norm, s * (maxX - minX) / 2f, s * (maxZ - minZ) / 2f)
    }

    private fun localMatrix(n: JSONObject): FloatArray {
        n.optJSONArray("matrix")?.let { a -> return FloatArray(16) { a.getDouble(it).toFloat() } }
        val t = n.optJSONArray("translation")
        val q = n.optJSONArray("rotation")
        val sc = n.optJSONArray("scale")
        val tx = t?.getDouble(0)?.toFloat() ?: 0f; val ty = t?.getDouble(1)?.toFloat() ?: 0f; val tz = t?.getDouble(2)?.toFloat() ?: 0f
        val x = q?.getDouble(0)?.toFloat() ?: 0f; val y = q?.getDouble(1)?.toFloat() ?: 0f
        val z = q?.getDouble(2)?.toFloat() ?: 0f; val w = q?.getDouble(3)?.toFloat() ?: 1f
        val sx = sc?.getDouble(0)?.toFloat() ?: 1f; val sy = sc?.getDouble(1)?.toFloat() ?: 1f; val sz = sc?.getDouble(2)?.toFloat() ?: 1f
        val r = Mat4.identity()
        r[0] = 1 - 2 * (y * y + z * z); r[1] = 2 * (x * y + z * w); r[2] = 2 * (x * z - y * w)
        r[4] = 2 * (x * y - z * w); r[5] = 1 - 2 * (x * x + z * z); r[6] = 2 * (y * z + x * w)
        r[8] = 2 * (x * z + y * w); r[9] = 2 * (y * z - x * w); r[10] = 1 - 2 * (x * x + y * y)
        val sm = Mat4.identity().also { it[0] = sx; it[5] = sy; it[10] = sz }
        val tm = Mat4.identity().also { it[12] = tx; it[13] = ty; it[14] = tz }
        return Mat4.mul(tm, Mat4.mul(r, sm))
    }

    /**
     * Dans ce modele, les vitres des phares avant ET des feux arriere sont une seule piece.
     * On la coupe en deux : avant = verre transparent, arriere = verre rouge.
     */
    private fun splitLensesFrontRear(prims: ArrayList<Primitive>) {
        for (p in ArrayList(prims)) {
            if (!p.node.contains("TAILLIGHT_LENS")) continue
            val front = ArrayList<Short>(); val rear = ArrayList<Short>()
            var t = 0
            while (t + 2 < p.indices.size) {
                var z = 0f
                for (k in 0 until 3) {
                    val i = p.indices[t + k].toInt() and 0xFFFF
                    z += Mat4.transformZ(p.world, p.vertices[i * 6], p.vertices[i * 6 + 1], p.vertices[i * 6 + 2])
                }
                val target = if (z > 0f) front else rear
                for (k in 0 until 3) target += p.indices[t + k]
                t += 3
            }
            prims.remove(p)
            prims += Primitive(p.node, p.vertices, rear.toShortArray(), p.world, p.material)
            prims += Primitive(
                p.node + "_FRONT", p.vertices, front.toShortArray(), p.world,
                Material(false, floatArrayOf(0.09f, 0.095f, 0.10f), 0.08f, 0.6f, 0.7f, 0.92f, light = 1),
            )
        }
    }

    /** Table des matieres (memes valeurs que l'apercu). */
    private fun materialFor(name: String, node: String, base: FloatArray): Material {
        val n = name.lowercase()
        if (node.uppercase().contains("MIRROR_GLASS")) return Material(false, floatArrayOf(0.6f, 0.62f, 0.65f), 0.05f, 1f, 0f, 1f)
        return when (n) {
            "body" -> Material(true, floatArrayOf(0.1f, 0.2f, 0.6f), 0.32f, 0f, 1f, 1f)
            "black" -> Material(false, floatArrayOf(0.012f, 0.012f, 0.014f), 0.55f, 0f, 0.15f, 1f)
            "brake" -> Material(false, floatArrayOf(0.45f, 0.012f, 0.012f), 0.2f, 0f, 0.8f, 1f)
            "light_glass" -> Material(false, floatArrayOf(0.30f, 0.02f, 0.02f), 0.1f, 0f, 1f, 1f, light = 2)
            "glass" -> Material(false, floatArrayOf(0.002f, 0.0025f, 0.003f), 0.05f, 0f, 0.22f, 0.95f)
            "inter" -> Material(false, floatArrayOf(0.018f, 0.018f, 0.02f), 0.85f, 0f, 0f, 1f)
            "tyre" -> Material(false, floatArrayOf(0.016f, 0.016f, 0.017f), 0.85f, 0f, 0f, 1f)
            "disk" -> Material(false, floatArrayOf(0.22f, 0.22f, 0.23f), 0.45f, 0.8f, 0f, 1f)
            "material" -> Material(false, floatArrayOf(0.62f, 0.63f, 0.66f), 0.30f, 1f, 0f, 1f)
            "lights" -> Material(false, floatArrayOf(0.50f, 0.03f, 0.03f), 0.2f, 0f, 0.8f, 1f, light = 2)
            "head" -> Material(false, floatArrayOf(0.72f, 0.74f, 0.78f), 0.12f, 1f, 0f, 1f, light = 1)
            else -> Material(false, base, 0.6f, 0f, 0.2f, 1f)
        }
    }
}
