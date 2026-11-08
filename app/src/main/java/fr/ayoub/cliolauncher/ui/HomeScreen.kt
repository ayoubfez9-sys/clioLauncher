package fr.ayoub.cliolauncher.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.ayoub.cliolauncher.data.AppSettings
import fr.ayoub.cliolauncher.data.AppState
import fr.ayoub.cliolauncher.data.AppsRepository
import fr.ayoub.cliolauncher.data.Launch
import fr.ayoub.cliolauncher.data.LeftShortcuts
import fr.ayoub.cliolauncher.data.Permissions
import fr.ayoub.cliolauncher.data.Screen
import fr.ayoub.cliolauncher.data.VehicleState
import fr.ayoub.cliolauncher.data.WeatherRepository
import fr.ayoub.cliolauncher.data.weatherIcon
import fr.ayoub.cliolauncher.data.weatherLabel
import fr.ayoub.cliolauncher.media.MediaRepository
import fr.ayoub.cliolauncher.media.NowPlayingInfo
import fr.ayoub.cliolauncher.ui.theme.C
import fr.ayoub.cliolauncher.ui.theme.Fonts
import fr.ayoub.cliolauncher.ui.theme.RoundButton
import fr.ayoub.cliolauncher.ui.theme.T
import fr.ayoub.cliolauncher.ui.theme.panel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** Ecran d'accueil : 3 colonnes, mise en page prevue pour 960 x 540 (mise a l'echelle automatique). */
@Composable
fun HomeScreen() {
    Row(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LeftColumn()
        CenterPanel(Modifier.weight(1f))
        RightColumn()
    }
}

/* ---------------------------------------------------------------- Colonne gauche */

@Composable
private fun LeftColumn() {
    val ctx = LocalContext.current
    val apps by AppsRepository.apps.collectAsState()
    Column(
        modifier = Modifier.fillMaxHeight().width(84.dp).panel().padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LeftShortcuts.forEach { s ->
            val entry = apps.firstOrNull { it.pkg == s.pkg }
            DockTile(label = s.label, image = entry?.icon, dim = entry == null) {
                if (entry != null) Launch.app(ctx, s.pkg) else Launch.playStore(ctx, s.pkg)
            }
        }
        Spacer(Modifier.weight(1f))
        DockTile("Apps", vector = Icons.Filled.Apps) { AppState.screen = Screen.APPS }
        DockTile("Véhicule", vector = Icons.Filled.DirectionsCar) { AppState.screen = Screen.VEHICLE }
        DockTile("Réglages", vector = Icons.Filled.Settings) { AppState.screen = Screen.SETTINGS }
    }
}

@Composable
private fun DockTile(
    label: String,
    image: ImageBitmap? = null,
    vector: ImageVector? = null,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(70.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp)
            .alpha(if (dim) 0.4f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(C.Panel2),
            contentAlignment = Alignment.Center,
        ) {
            when {
                image != null -> Image(image, null, Modifier.size(38.dp))
                vector != null -> Icon(vector, null, tint = C.Text, modifier = Modifier.size(24.dp))
                else -> T(label.take(1), 18.sp, Fonts.bold)
            }
        }
        Spacer(Modifier.height(3.dp))
        T(label, 11.sp, Fonts.semi, C.Muted)
    }
}

/* ---------------------------------------------------------------- Centre */

@Composable
private fun CenterPanel(modifier: Modifier) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            WeatherRepository.refreshIfStale(ctx)
            delay(60_000)
        }
    }
    val minute = now / 60_000
    val time = remember(minute) { SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(now)) }
    val date = remember(minute) {
        SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(now))
            .replaceFirstChar { it.titlecase(Locale.FRANCE) }
    }

    Box(
        modifier = modifier.fillMaxHeight().panel(
            brush = Brush.verticalGradient(listOf(Color(0xFF0E131B), Color(0xFF0A0F18)))
        ),
    ) {
        CarStage(Modifier.fillMaxSize())

        // Heure, date, meteo
        Column(Modifier.align(Alignment.TopStart).padding(22.dp)) {
            T(time, 78.sp, Fonts.bold)
            T(date, 18.sp, Fonts.medium, C.Muted)
            Spacer(Modifier.height(8.dp))
            val w = WeatherRepository.weather
            if (w != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(weatherIcon(w.code, w.isDay), null, tint = C.Text, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    T("${w.tempC}°", 24.sp, Fonts.bold)
                    Spacer(Modifier.width(8.dp))
                    T(weatherLabel(w.code), 16.sp, Fonts.medium, C.Muted)
                }
            } else {
                T(WeatherRepository.status, 13.sp, Fonts.medium, C.Muted)
            }
        }

        // Nom de la voiture
        Column(Modifier.align(Alignment.TopEnd).padding(22.dp), horizontalAlignment = Alignment.End) {
            T(AppSettings.carName, 28.sp, Fonts.bold, spacing = 3.sp)
            T(AppSettings.carSubtitle, 13.sp, Fonts.semi, C.Accent, spacing = 4.sp)
        }

        // Vitesse
        val speed = VehicleState.speedKmh
        val fresh = speed != null && now - VehicleState.speedTime < 5_000
        Row(Modifier.align(Alignment.BottomStart).padding(22.dp), verticalAlignment = Alignment.Bottom) {
            T(if (fresh) "$speed" else "--", 60.sp, Fonts.bold)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.padding(bottom = 10.dp)) {
                T("KM/H", 14.sp, Fonts.semi, C.Muted, spacing = 2.sp)
                T(
                    if (fresh) "GPS" else if (Permissions.hasLocation(ctx)) "GPS en attente" else "GPS non autorisé",
                    10.sp, Fonts.semi, if (fresh) C.Accent else C.Muted, spacing = 1.sp,
                )
            }
        }

        // Boutons ronds
        Row(
            Modifier.align(Alignment.BottomEnd).padding(22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundButton(Icons.Filled.Lightbulb, active = VehicleState.lightsOn) {
                VehicleState.lightsOn = !VehicleState.lightsOn
            }
            RoundButton(Icons.Filled.DarkMode, active = AppState.night) {
                AppState.night = !AppState.night
            }
            RoundButton(Icons.Filled.MyLocation) { AppState.recenterTick++ }
        }
    }
}

/**
 * Plateau rond avec anneau bleu lumineux.
 * A l'etape 2, le modele 3D de la Clio sera pose dessus.
 */
@Composable
private fun CarStage(modifier: Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height * 0.62f)
            val r = min(size.width * 0.34f, size.height * 1.1f)
            // tout est dessine en cercles "ecrases" pour donner la perspective
            scale(scaleX = 1f, scaleY = 0.28f, pivot = c) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(C.Accent.copy(alpha = 0.16f), Color.Transparent), center = c, radius = r * 1.5f
                    ),
                    radius = r * 1.5f, center = c,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFF1B2433), Color(0xFF0C1017)), center = c, radius = r
                    ),
                    radius = r, center = c,
                )
                for (i in 5 downTo 1) {
                    drawCircle(
                        color = C.Accent.copy(alpha = 0.07f),
                        radius = r, center = c,
                        style = Stroke(width = (i * 7).dp.toPx()),
                    )
                }
                drawCircle(color = C.Accent, radius = r, center = c, style = Stroke(width = 3.dp.toPx()))
                drawCircle(
                    color = C.Accent.copy(alpha = 0.25f), radius = r * 0.82f, center = c,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        T(
            "Modèle 3D : étape 2", 13.sp, Fonts.medium, C.Muted,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/* ---------------------------------------------------------------- Colonne droite */

@Composable
private fun RightColumn() {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier.fillMaxHeight().width(272.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Tuile CarPlay (Zlink)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .panel(brush = Brush.linearGradient(listOf(Color(0xFF17294A), Color(0xFF0F1622))))
                .clickable { Launch.openCarPlay(ctx) }
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(C.Accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PhoneIphone, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.width(14.dp))
            Column {
                T("CarPlay", 26.sp, Fonts.bold)
                T("via Zlink", 13.sp, Fonts.medium, C.Muted)
            }
        }

        MusicCard(Modifier.weight(1f))

        // Tuile Radio FM
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .panel()
                .clickable { Launch.openRadio(ctx) }
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Radio, null, tint = C.Accent, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                T("Radio FM", 20.sp, Fonts.bold)
                T("Toucher pour ouvrir", 12.sp, Fonts.medium, C.Muted)
            }
        }
    }
}

@Composable
private fun MusicCard(modifier: Modifier) {
    val ctx = LocalContext.current
    val info by MediaRepository.info.collectAsState()
    val hasAccess = remember(AppState.resumeTick) { Permissions.hasNotifAccess(ctx) }
    Column(modifier.fillMaxWidth().panel().padding(16.dp)) {
        T("EN COURS DE LECTURE", 11.sp, Fonts.semi, C.Muted, spacing = 2.sp)
        Spacer(Modifier.height(10.dp))
        val i = info
        when {
            !hasAccess -> {
                T(
                    "Autorise l'accès aux notifications pour afficher la musique ici.",
                    15.sp, Fonts.medium, C.Muted, maxLines = 3,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(C.Accent)
                        .clickable { Permissions.openNotifAccess(ctx) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) { T("Autoriser", 15.sp, Fonts.bold) }
            }
            i == null -> T("Aucune lecture en cours", 16.sp, Fonts.medium, C.Muted)
            else -> NowPlaying(i)
        }
    }
}

@Composable
private fun ColumnScope.NowPlaying(i: NowPlayingInfo) {
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(i) {
        while (true) {
            tick = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val pos = if (i.playing && i.updateTime > 0) {
        i.positionMs + ((tick - i.updateTime) * i.speed).toLong()
    } else i.positionMs
    val dur = i.durationMs
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)).background(C.Panel2),
            contentAlignment = Alignment.Center,
        ) {
            if (i.art != null) {
                Image(i.art, null, Modifier.size(58.dp), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Filled.MusicNote, null, tint = C.Muted, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(i.title, 19.sp, Fonts.bold)
            T(i.artist, 14.sp, Fonts.medium, C.Muted)
            T(i.appLabel, 11.sp, Fonts.semi, C.Accent)
        }
    }
    Spacer(Modifier.weight(1f))
    Box(
        Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Stroke)
    ) {
        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(C.Accent))
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        T(fmt(pos), 11.sp, Fonts.medium, C.Muted)
        T(if (dur > 0) fmt(dur) else "", 11.sp, Fonts.medium, C.Muted)
    }
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(Icons.Filled.SkipPrevious, size = 44.dp) { MediaRepository.previous() }
        RoundButton(if (i.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, size = 56.dp, filled = true) {
            MediaRepository.playPause()
        }
        RoundButton(Icons.Filled.SkipNext, size = 44.dp) { MediaRepository.next() }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
