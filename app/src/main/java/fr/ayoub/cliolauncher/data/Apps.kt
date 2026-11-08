package fr.ayoub.cliolauncher.data

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap)

/** Raccourcis de la colonne de gauche. */
data class Shortcut(val label: String, val pkg: String)

val LeftShortcuts = listOf(
    Shortcut("Waze", "com.waze"),
    Shortcut("Maps", "com.google.android.apps.maps"),
    Shortcut("Chrome", "com.android.chrome"),
    Shortcut("Play Store", "com.android.vending"),
)

/** Liste des applis installees (avec leurs icones). */
object AppsRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    fun reload(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                val pm = app.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val list = pm.queryIntentActivities(intent, 0)
                    .filter { it.activityInfo.packageName != app.packageName }
                    .distinctBy { it.activityInfo.packageName }
                    .map { ri ->
                        AppEntry(
                            pkg = ri.activityInfo.packageName,
                            label = ri.loadLabel(pm).toString(),
                            icon = ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap(),
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                _apps.value = list
            } catch (e: Exception) {
                // on garde l'ancienne liste
            }
        }
    }

    fun find(pkg: String?): AppEntry? = pkg?.let { p -> _apps.value.firstOrNull { it.pkg == p } }

    private fun guess(known: List<String>, words: List<String>): AppEntry? {
        val list = _apps.value
        known.forEach { k -> list.firstOrNull { it.pkg == k }?.let { return it } }
        return list.firstOrNull { e ->
            words.any { w -> e.label.contains(w, ignoreCase = true) || e.pkg.contains(w, ignoreCase = true) }
        }
    }

    /** Devine l'appli Zlink (CarPlay) si elle n'a pas ete choisie dans les reglages. */
    fun guessCarPlay() = guess(listOf("com.zjinnova.zlink"), listOf("zlink", "carplay", "autokit", "carlink"))

    /** Devine l'appli Radio FM de l'autoradio. */
    fun guessRadio() = guess(emptyList(), listOf("radio", "fm"))
}

/** Lancer des applis et ouvrir des ecrans systeme. */
object Launch {
    fun app(ctx: Context, pkg: String?): Boolean {
        if (pkg == null) return false
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (e: Exception) { false }
    }

    fun playStore(ctx: Context, pkg: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!start(ctx, i)) toast(ctx, "Appli non installée : $pkg")
    }

    fun start(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun openCarPlay(ctx: Context) {
        val pkg = AppSettings.carplayPkg ?: AppsRepository.guessCarPlay()?.pkg
        if (!app(ctx, pkg)) {
            toast(ctx, "Appli Zlink introuvable : choisis-la dans Réglages")
            AppState.screen = Screen.SETTINGS
        }
    }

    fun openRadio(ctx: Context) {
        val pkg = AppSettings.radioPkg ?: AppsRepository.guessRadio()?.pkg
        if (!app(ctx, pkg)) {
            toast(ctx, "Appli Radio introuvable : choisis-la dans Réglages")
            AppState.screen = Screen.SETTINGS
        }
    }

    /** Ecran Android "Appli d'accueil par defaut". */
    fun openHomeSettings(ctx: Context) {
        if (!start(ctx, Intent(Settings.ACTION_HOME_SETTINGS)) &&
            !start(ctx, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) &&
            !start(ctx, Intent(Settings.ACTION_SETTINGS))
        ) toast(ctx, "Impossible d'ouvrir les paramètres")
    }

    fun openAppInfo(ctx: Context) {
        start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }
}

object Permissions {
    fun hasLocation(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasNotifAccess(ctx: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun openNotifAccess(ctx: Context) {
        if (!Launch.start(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
            Launch.toast(ctx, "Écran introuvable sur cet autoradio : voir le README (commande adb)")
        }
    }
}
