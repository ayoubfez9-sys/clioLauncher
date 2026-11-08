package fr.ayoub.cliolauncher

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import fr.ayoub.cliolauncher.data.AppSettings
import fr.ayoub.cliolauncher.data.AppState
import fr.ayoub.cliolauncher.data.AppsRepository
import fr.ayoub.cliolauncher.data.Permissions
import fr.ayoub.cliolauncher.data.Screen
import fr.ayoub.cliolauncher.data.SpeedProvider
import fr.ayoub.cliolauncher.data.WeatherRepository
import fr.ayoub.cliolauncher.media.MediaRepository
import fr.ayoub.cliolauncher.ui.AppDrawer
import fr.ayoub.cliolauncher.ui.HomeScreen
import fr.ayoub.cliolauncher.ui.SettingsScreen
import fr.ayoub.cliolauncher.ui.VehicleScreen
import fr.ayoub.cliolauncher.ui.theme.C
import fr.ayoub.cliolauncher.ui.theme.Fonts
import kotlin.math.min

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        AppSettings.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // On cache seulement la barre d'etat : la barre de navigation Junsun
        // (accueil, retour, volume) reste visible.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { Root(this) }
    }

    override fun onResume() {
        super.onResume()
        AppState.resumeTick++
        AppsRepository.reload(this)
        MediaRepository.start(this)
        if (Permissions.hasLocation(this)) {
            SpeedProvider.start(this)
            WeatherRepository.refreshIfStale(this)
        }
    }

    /** Appui sur le bouton "Accueil" alors que le launcher est deja ouvert. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppState.screen = Screen.HOME
    }
}

@Composable
private fun Root(activity: ComponentActivity) {
    MaterialTheme(colorScheme = darkColorScheme(primary = C.Accent, background = C.Bg, surface = C.Panel)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(C.Bg)) {
            // L'interface est dessinee pour 960 x 540 puis agrandie/reduite pour remplir l'ecran.
            val factor = min(maxWidth.value / 960f, maxHeight.value / 540f)
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * factor, 1f)) {
                when (AppState.screen) {
                    Screen.HOME -> HomeScreen()
                    Screen.APPS -> AppDrawer()
                    Screen.VEHICLE -> VehicleScreen()
                    Screen.SETTINGS -> SettingsScreen()
                }
            }
            // Mode nuit : voile sombre (ne bloque pas les touches)
            if (AppState.night) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            }
        }
    }

    BackHandler(enabled = AppState.screen != Screen.HOME) { AppState.screen = Screen.HOME }

    // Mode nuit : baisse aussi la luminosite de l'ecran tant que le launcher est affiche
    LaunchedEffect(AppState.night) {
        val lp = activity.window.attributes
        lp.screenBrightness = if (AppState.night) 0.15f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = lp
    }
}
