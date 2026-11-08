package fr.ayoub.cliolauncher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import fr.ayoub.cliolauncher.data.AppEntry
import fr.ayoub.cliolauncher.data.AppSettings
import fr.ayoub.cliolauncher.data.AppState
import fr.ayoub.cliolauncher.data.AppsRepository
import fr.ayoub.cliolauncher.data.Door
import fr.ayoub.cliolauncher.data.Launch
import fr.ayoub.cliolauncher.data.Permissions
import fr.ayoub.cliolauncher.data.Screen
import fr.ayoub.cliolauncher.data.SpeedProvider
import fr.ayoub.cliolauncher.data.VehicleState
import fr.ayoub.cliolauncher.data.WeatherRepository
import fr.ayoub.cliolauncher.ui.theme.C
import fr.ayoub.cliolauncher.ui.theme.Fonts
import fr.ayoub.cliolauncher.ui.theme.T
import fr.ayoub.cliolauncher.ui.theme.panel

@Composable
private fun Header(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(C.Panel2)
                .clickable { AppState.screen = Screen.HOME },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.Text) }
        Spacer(Modifier.width(14.dp))
        T(title, 28.sp, Fonts.bold, spacing = 1.sp)
    }
}

/* ---------------------------------------------------------------- Toutes les applis */

@Composable
fun AppDrawer() {
    val ctx = LocalContext.current
    val apps by AppsRepository.apps.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Header("Applications")
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            modifier = Modifier.fillMaxSize().panel().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(apps, key = { it.pkg }) { app ->
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .clickable { Launch.app(ctx, app.pkg) }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(app.icon, null, Modifier.size(52.dp))
                    Spacer(Modifier.height(4.dp))
                    T(app.label, 13.sp, Fonts.medium, C.Text)
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- Vehicule */

@Composable
fun VehicleScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Header("Véhicule")
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().panel().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                T("FEUX", 12.sp, Fonts.semi, C.Muted, spacing = 2.sp)
                StateTile(
                    "Phares", if (VehicleState.lightsOn) "ALLUMÉS" else "ÉTEINTS", VehicleState.lightsOn,
                    Modifier.fillMaxWidth(),
                ) { VehicleState.lightsOn = !VehicleState.lightsOn }
                Spacer(Modifier.height(6.dp))
                T("PORTES", 12.sp, Fonts.semi, C.Muted, spacing = 2.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DoorTile(Door.AVG, Modifier.weight(1f))
                    DoorTile(Door.AVD, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DoorTile(Door.ARG, Modifier.weight(1f))
                    DoorTile(Door.ARD, Modifier.weight(1f))
                }
                DoorTile(Door.COFFRE, Modifier.fillMaxWidth())
            }
            Column(
                Modifier.width(330.dp).fillMaxHeight().panel().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                T("D'OÙ VIENNENT LES DONNÉES", 12.sp, Fonts.semi, C.Muted, spacing = 2.sp)
                InfoLine("Vitesse", "GPS de l'autoradio (réel)", C.Ok)
                InfoLine("Feux", "Bouton manuel (${VehicleState.lightsSource.label})", C.Warn)
                InfoLine("Portes", "Boutons manuels (${VehicleState.doorsSource.label})", C.Warn)
                Spacer(Modifier.height(6.dp))
                T(
                    "Les feux s'allument sur la Clio 3D et une porte ouverte clignote en orange sur la voiture. " +
                        "La lecture du boîtier CAN SimpleSoft sera étudiée à l'étape 4.",
                    14.sp, Fonts.medium, C.Muted, maxLines = 6,
                )
            }
        }
    }
}

@Composable
private fun DoorTile(d: Door, modifier: Modifier) {
    val open = VehicleState.isOpen(d)
    StateTile(d.label, if (open) "OUVERTE" else "FERMÉE", open, modifier) { VehicleState.toggleDoor(d) }
}

@Composable
private fun StateTile(title: String, state: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(60.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) C.Accent.copy(alpha = 0.18f) else C.Panel2)
            .border(1.5.dp, if (on) C.Accent else C.Stroke, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (title == "Phares") {
            Icon(Icons.Filled.Lightbulb, null, tint = if (on) C.Accent else C.Muted)
            Spacer(Modifier.width(10.dp))
        }
        T(title, 18.sp, Fonts.semi, modifier = Modifier.weight(1f))
        T(state, 14.sp, Fonts.bold, if (on) C.Accent else C.Muted, spacing = 1.sp)
    }
}

@Composable
private fun InfoLine(label: String, value: String, dot: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Column {
            T(label, 16.sp, Fonts.bold)
            T(value, 13.sp, Fonts.medium, C.Muted)
        }
    }
}

/* ---------------------------------------------------------------- Reglages */

/** Couleurs de carrosserie proposees (approximations). */
private val BodyColors = listOf(
    "Bleu Enedis" to 0xFF1F5FD0.toInt(),
    "Bleu nuit" to 0xFF1B2A4A.toInt(),
    "Blanc" to 0xFFE6EAEE.toInt(),
    "Gris" to 0xFF7D838A.toInt(),
    "Noir" to 0xFF111216.toInt(),
    "Rouge" to 0xFFB3121C.toInt(),
)

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf<String?>(null) } // "carplay" ou "radio"
    val hasNotif = remember(AppState.resumeTick) { Permissions.hasNotifAccess(ctx) }
    var hasLoc by remember(AppState.resumeTick) { mutableStateOf(Permissions.hasLocation(ctx)) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLoc = Permissions.hasLocation(ctx)
        if (hasLoc) {
            SpeedProvider.start(ctx)
            WeatherRepository.refreshIfStale(ctx)
        }
    }
    fun save() = AppSettings.save(ctx)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Header("Réglages")
            Column(
                Modifier.fillMaxSize().panel().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Section("AFFICHAGE")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextSetting("Nom affiché", AppSettings.carName, Modifier.weight(1f)) {
                        AppSettings.carName = it; save()
                    }
                    TextSetting("Sous-titre", AppSettings.carSubtitle, Modifier.weight(1f)) {
                        AppSettings.carSubtitle = it; save()
                    }
                }

                Section("APPLIS DES TUILES")
                SettingRow(
                    "Appli CarPlay",
                    appName(AppSettings.carplayPkg, AppsRepository.guessCarPlay(), "Zlink"),
                    "Choisir",
                ) { picking = "carplay" }
                SettingRow(
                    "Appli Radio FM",
                    appName(AppSettings.radioPkg, AppsRepository.guessRadio(), "radio"),
                    "Choisir",
                ) { picking = "radio" }

                Section("AUTORISATIONS")
                SettingRow(
                    "Musique en cours",
                    if (hasNotif) "Autorisé" else "Accès aux notifications nécessaire",
                    if (hasNotif) "Ouvrir" else "Autoriser",
                ) { Permissions.openNotifAccess(ctx) }
                SettingRow(
                    "Position (vitesse GPS et météo)",
                    if (hasLoc) "Autorisé" else "Non autorisé",
                    if (hasLoc) "OK" else "Autoriser",
                ) {
                    if (!hasLoc) askLocation.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }
                SettingRow("Infos de l'appli", "Autorisations, paramètres restreints, désinstaller", "Ouvrir") {
                    Launch.openAppInfo(ctx)
                }

                Section("MODÈLE 3D")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("Mode allégé", 18.sp, Fonts.semi)
                        T("Moins de reflets et résolution réduite, si la 3D saccade", 13.sp, Fonts.medium, C.Muted, maxLines = 2)
                    }
                    Switch(checked = AppSettings.liteMode, onCheckedChange = { AppSettings.liteMode = it; save() })
                }
                T("Couleur de carrosserie", 18.sp, Fonts.semi)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BodyColors.forEach { (name, argb) ->
                        val selected = AppSettings.bodyColor == argb
                        Column(
                            Modifier.clip(RoundedCornerShape(10.dp))
                                .clickable { AppSettings.bodyColor = argb; save() }
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(Color(argb))
                                    .border(3.dp, if (selected) C.Accent else C.Stroke, CircleShape)
                            )
                            T(name, 11.sp, Fonts.medium, if (selected) C.Text else C.Muted)
                        }
                    }
                }

                Section("ÉCRAN D'ACCUEIL")
                SettingRow(
                    "Launcher par défaut",
                    "Choisir entre ce launcher et celui d'origine",
                    "Ouvrir",
                ) { Launch.openHomeSettings(ctx) }

                Section("CRÉDITS")
                T(
                    "Modèle 3D « clio 4 » par claveranass (Sketchfab), licence CC BY 4.0.\n" +
                        "Police Rajdhani (Indian Type Foundry), licence SIL OFL.\n" +
                        "Météo : Open-Meteo.com, licence CC BY 4.0.",
                    13.sp, Fonts.medium, C.Muted, maxLines = 6,
                )
            }
        }

        val p = picking
        if (p != null) {
            AppPicker(
                title = if (p == "carplay") "Appli CarPlay (Zlink)" else "Appli Radio FM",
                onDismiss = { picking = null },
            ) { app ->
                if (p == "carplay") AppSettings.carplayPkg = app.pkg else AppSettings.radioPkg = app.pkg
                save()
                picking = null
            }
        }
    }
}

private fun appName(chosen: String?, guess: AppEntry?, what: String): String {
    val chosenEntry = AppsRepository.find(chosen)
    return when {
        chosenEntry != null -> chosenEntry.label
        chosen != null -> "$chosen (introuvable)"
        guess != null -> "Auto : ${guess.label}"
        else -> "Aucune appli $what trouvée"
    }
}

@Composable
private fun Section(title: String) {
    T(title, 12.sp, Fonts.semi, C.Accent, spacing = 2.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun SettingRow(title: String, value: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, 18.sp, Fonts.semi)
            T(value, 13.sp, Fonts.medium, C.Muted)
        }
        Box(
            Modifier.clip(RoundedCornerShape(10.dp)).background(C.Panel2)
                .border(1.dp, C.Stroke, RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) { T(action, 15.sp, Fonts.bold, C.Accent) }
    }
}

@Composable
private fun TextSetting(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(20)) },
        label = { T(label, 13.sp, Fonts.medium, C.Muted) },
        singleLine = true,
        textStyle = TextStyle(color = C.Text, fontFamily = Fonts.semi, fontSize = 18.sp),
        modifier = modifier,
    )
}

@Composable
private fun AppPicker(title: String, onDismiss: () -> Unit, onPick: (AppEntry) -> Unit) {
    val apps by AppsRepository.apps.collectAsState()
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(520.dp).fillMaxHeight(0.85f).panel(brush = androidx.compose.ui.graphics.SolidColor(C.Panel2))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(16.dp)
        ) {
            T(title, 22.sp, Fonts.bold)
            Spacer(Modifier.height(10.dp))
            LazyColumn {
                items(apps, key = { it.pkg }) { app ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(app) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(app.icon, null, Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            T(app.label, 17.sp, Fonts.semi)
                            T(app.pkg, 11.sp, Fonts.regular, C.Muted)
                        }
                    }
                }
            }
        }
    }
}
