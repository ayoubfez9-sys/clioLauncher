package fr.ayoub.cliolauncher.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Screen { HOME, APPS, VEHICLE, SETTINGS, CAN_DIAG }

/** Etat general de l'interface. */
object AppState {
    var screen by mutableStateOf(Screen.HOME)
    var night by mutableStateOf(false)
    /** Incremente a chaque appui sur "recentrer" (utilise par la 3D a l'etape 2). */
    var recenterTick by mutableIntStateOf(0)
    /** Incremente a chaque retour sur le launcher (pour reverifier les autorisations). */
    var resumeTick by mutableIntStateOf(0)
}

/** L'ordre compte : il donne les bits envoyes a la 3D. */
enum class Door(val label: String, val short: String) {
    AVG("Avant gauche", "PORTE AV GAUCHE"),
    AVD("Avant droite", "PORTE AV DROITE"),
    ARG("Arrière gauche", "PORTE AR GAUCHE"),
    ARD("Arrière droite", "PORTE AR DROITE"),
    COFFRE("Coffre", "COFFRE OUVERT"),
}

enum class DataSource(val label: String) { MANUEL("manuel"), GPS("GPS"), CAN("CAN") }

/**
 * Etat de la voiture affiche par le launcher.
 * Aujourd'hui : feux et portes = boutons manuels, vitesse = GPS de l'autoradio.
 * A l'etape 4, une source CAN pourra ecrire ici si l'autoradio le permet.
 */
object VehicleState {
    var lightsOn by mutableStateOf(false)
    val doors = mutableStateMapOf<Door, Boolean>()
    var lightsSource by mutableStateOf(DataSource.MANUEL)
    var doorsSource by mutableStateOf(DataSource.MANUEL)

    var speedKmh by mutableStateOf<Int?>(null)
    var speedTime by mutableLongStateOf(0L)
    var speedSource by mutableStateOf(DataSource.GPS)
    var batteryVolts by mutableStateOf<Float?>(null)

    fun isOpen(d: Door) = doors[d] == true

    /** Portes ouvertes sous forme de bits, pour la 3D (1 AVG, 2 AVD, 4 ARG, 8 ARD, 16 coffre). */
    fun doorMask(): Int = Door.entries.foldIndexed(0) { i, acc, d -> if (isOpen(d)) acc or (1 shl i) else acc }
    fun toggleDoor(d: Door) { doors[d] = !isOpen(d) }
}

/** Reglages enregistres sur l'autoradio. */
object AppSettings {
    private const val PREFS = "clio_prefs"

    var carName by mutableStateOf("CLIO 4")
    var carSubtitle by mutableStateOf("MA CLIO")
    var carplayPkg by mutableStateOf<String?>(null)
    var radioPkg by mutableStateOf<String?>(null)
    var liteMode by mutableStateOf(false)
    /** Suivre les portes et les feux de la vraie voiture (boitier CAN). */
    var canSync by mutableStateOf(true)
    /** Couleur de carrosserie (ARGB), utilisee par le modele 3D a l'etape 2. */
    var bodyColor by mutableStateOf(0xFF1F5FD0.toInt())

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        carName = sp.getString("carName", carName) ?: carName
        carSubtitle = sp.getString("carSubtitle", carSubtitle) ?: carSubtitle
        carplayPkg = sp.getString("carplayPkg", null)
        radioPkg = sp.getString("radioPkg", null)
        liteMode = sp.getBoolean("liteMode", false)
        canSync = sp.getBoolean("canSync", true)
        bodyColor = sp.getInt("bodyColor", bodyColor)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("carName", carName)
            .putString("carSubtitle", carSubtitle)
            .putString("carplayPkg", carplayPkg)
            .putString("radioPkg", radioPkg)
            .putBoolean("liteMode", liteMode)
            .putBoolean("canSync", canSync)
            .putInt("bodyColor", bodyColor)
            .apply()
    }
}
