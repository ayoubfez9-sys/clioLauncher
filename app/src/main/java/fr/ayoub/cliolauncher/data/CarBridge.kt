package fr.ayoub.cliolauncher.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pont vers les donnees voiture du systeme Junsun (plateforme HCN, RK3326).
 *
 * Trouve en analysant les applis du systeme (com.hcn.autocan et le theme com.hcn.auto.theme.xt231) :
 *  - portes : proprietes systeme ecrites par l'appli CAN quand une porte change
 *      persist.vendor.cem.cardoor1..5 = AVG, AVD, ARG, ARD, coffre ; "2" = ouverte, "1" = fermee
 *  - phares : classe ajoutee par le fabricant, android.carstatus.CarStatus#getHeadLightStatus()
 *  - batterie : android.carsource.McuManager.getsInstance().getBattery()
 *  - vitesse : persist.vendor.cem.speed (le decodeur Renault SimpleSoft ne semble pas l'ecrire)
 * Rien n'est ecrit : on ne fait que lire.
 */
object CarBridge {

    val DOOR_KEYS = listOf(
        "persist.vendor.cem.cardoor1", // avant gauche
        "persist.vendor.cem.cardoor2", // avant droite
        "persist.vendor.cem.cardoor3", // arriere gauche
        "persist.vendor.cem.cardoor4", // arriere droite
        "persist.vendor.cem.cardoor5", // coffre
    )
    private const val SPEED_KEY = "persist.vendor.cem.speed"
    private const val SPEED_KEY2 = "persist.vendor.cem.current.speed"

    data class Snapshot(
        val doorsRaw: List<String>,   // valeurs brutes ("" = illisible ou jamais ecrite)
        val headlight: Boolean?,      // null = indisponible
        val battery: Float?,
        val speedRaw: String,
        val headlightError: String?,
    )

    // ---- acces aux API du fabricant (par reflexion : elles n'existent que sur ce systeme)
    private val getProp: Method? = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
    } catch (e: Throwable) { null }

    private var carStatus: Any? = null
    private var headMethod: Method? = null
    private var headError: String? = null
    private var mcu: Any? = null
    private var batteryMethod: Method? = null

    private fun initApis() {
        if (carStatus == null && headError == null) {
            try {
                val cls = Class.forName("android.carstatus.CarStatus")
                carStatus = cls.getDeclaredConstructor().newInstance()
                headMethod = cls.getMethod("getHeadLightStatus")
            } catch (t: Throwable) {
                headError = t.javaClass.simpleName + ": " + (t.message ?: "")
            }
        }
        if (mcu == null) {
            try {
                val cls = Class.forName("android.carsource.McuManager")
                mcu = cls.getMethod("getsInstance").invoke(null)
                batteryMethod = cls.getMethod("getBattery")
            } catch (t: Throwable) { /* pas de batterie */ }
        }
    }

    fun prop(key: String): String = try { getProp?.invoke(null, key) as? String ?: "" } catch (e: Throwable) { "" }

    @Synchronized
    fun read(): Snapshot {
        initApis()
        val head = try { headMethod?.invoke(carStatus) as? Boolean } catch (t: Throwable) {
            headError = t.javaClass.simpleName + ": " + (t.cause?.message ?: t.message ?: ""); null
        }
        val batt = try { (batteryMethod?.invoke(mcu) as? Number)?.toFloat() } catch (t: Throwable) { null }
        val speed = prop(SPEED_KEY).ifEmpty { prop(SPEED_KEY2) }
        return Snapshot(DOOR_KEYS.map { prop(it) }, head, batt, speed, headError)
    }

    /** Liste des fonctions des classes du fabricant (pour le rapport de diagnostic). */
    fun describeApis(): String {
        val sb = StringBuilder()
        for (name in listOf("android.carstatus.CarStatus", "android.carsource.McuManager")) {
            try {
                val cls = Class.forName(name)
                sb.appendLine("Classe $name :")
                cls.declaredMethods.sortedBy { it.name }.forEach { m ->
                    sb.appendLine("   ${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                }
            } catch (t: Throwable) {
                sb.appendLine("Classe $name : absente (${t.javaClass.simpleName})")
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ synchro

    /** Dernieres valeurs lues (pour l'ecran de diagnostic). */
    var last by mutableStateOf<Snapshot?>(null)
        private set

    private var lastHead: Boolean? = null
    private val lastDoors = arrayOfNulls<String>(5)
    private var started = false
    private val main = Handler(Looper.getMainLooper())

    /** Lit les donnees voiture 3 fois par seconde et met a jour VehicleState. */
    fun start() {
        if (started) return
        started = true
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "CarBridge").apply { isDaemon = true } }
            .scheduleWithFixedDelay({
                val s = try { read() } catch (t: Throwable) { null }
                if (s != null) main.post { apply(s) }
            }, 500, 330, TimeUnit.MILLISECONDS)
    }

    /**
     * On n'applique que les CHANGEMENTS venant de la voiture :
     * les boutons manuels restent utilisables pour tester.
     */
    private fun apply(s: Snapshot) {
        last = s
        s.battery?.let { if (it > 1f) VehicleState.batteryVolts = it }
        if (!AppSettings.canSync) return
        val h = s.headlight
        if (h != null && h != lastHead) {
            lastHead = h
            VehicleState.lightsOn = h
            VehicleState.lightsSource = DataSource.CAN
        }
        Door.entries.forEachIndexed { i, d ->
            val raw = s.doorsRaw[i]
            if ((raw == "1" || raw == "2") && raw != lastDoors[i]) {
                lastDoors[i] = raw
                VehicleState.doors[d] = raw == "2"
                VehicleState.doorsSource = DataSource.CAN
            }
        }
    }

    /** Quand on reactive la synchro : reappliquer l'etat actuel de la voiture. */
    fun resync() {
        lastHead = null
        for (i in lastDoors.indices) lastDoors[i] = null
    }
}
