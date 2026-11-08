package fr.ayoub.cliolauncher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.ayoub.cliolauncher.data.AppState
import fr.ayoub.cliolauncher.data.CanDiagnostic
import fr.ayoub.cliolauncher.data.CarBridge
import fr.ayoub.cliolauncher.data.Screen
import fr.ayoub.cliolauncher.ui.theme.C
import fr.ayoub.cliolauncher.ui.theme.Fonts
import fr.ayoub.cliolauncher.ui.theme.T
import fr.ayoub.cliolauncher.ui.theme.panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ecran "Diagnostic CAN" : rapport + export vers Telechargements/ClioDiag. */
@Composable
fun CanDiagScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun startExport() {
        val text = report ?: return
        busy = true
        status = "Export en cours…"
        scope.launch {
            status = try {
                val n = withContext(Dispatchers.IO) {
                    CanDiagnostic.export(ctx, text) { msg -> status = "Copie : $msg" }
                }
                "Terminé : $n fichier(s) dans Téléchargements/ClioDiag"
            } catch (e: Exception) {
                "Échec de l'export : ${e.message}"
            }
            busy = false
        }
    }
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startExport() else status = "Autorisation « stockage » refusée : export impossible"
    }

    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) {
            try { CanDiagnostic.report(ctx) } catch (e: Exception) { "Erreur pendant l'analyse : ${e.message}" }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(C.Panel2)
                    .clickable { AppState.screen = Screen.SETTINGS },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.Text) }
            Spacer(Modifier.width(14.dp))
            T("Diagnostic CAN", 28.sp, Fonts.bold, spacing = 1.sp)
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.width(300.dp).fillMaxHeight().panel().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                T("DONNÉES VOITURE EN DIRECT", 12.sp, Fonts.semi, C.Accent, spacing = 2.sp)
                val snap = CarBridge.last
                if (snap == null) {
                    T("Lecture…", 14.sp, Fonts.medium, C.Muted)
                } else {
                    val names = listOf("AV gauche", "AV droite", "AR gauche", "AR droite", "Coffre")
                    names.forEachIndexed { i, n ->
                        val raw = snap.doorsRaw.getOrElse(i) { "" }
                        val txt = when (raw) { "2" -> "OUVERTE"; "1" -> "fermée (1)"; "" -> "vide / illisible"; else -> "« $raw »" }
                        Row {
                            T(n, 14.sp, Fonts.medium, C.Muted, modifier = Modifier.width(110.dp))
                            T(txt, 14.sp, Fonts.bold, if (raw == "2") C.Warn else if (raw == "1") C.Ok else C.Muted)
                        }
                    }
                    Row {
                        T("Phares", 14.sp, Fonts.medium, C.Muted, modifier = Modifier.width(110.dp))
                        T(
                            when (snap.headlight) { true -> "ALLUMÉS"; false -> "éteints"; null -> "indisponible" },
                            14.sp, Fonts.bold, if (snap.headlight == null) C.Muted else C.Ok,
                        )
                    }
                    snap.battery?.let { b ->
                        Row {
                            T("Batterie", 14.sp, Fonts.medium, C.Muted, modifier = Modifier.width(110.dp))
                            T(String.format(java.util.Locale.FRANCE, "%.1f V", b), 14.sp, Fonts.bold, C.Ok)
                        }
                    }
                    snap.junsunSettings.forEach { (k, v) ->
                        Row {
                            T(k, 11.sp, Fonts.medium, C.Muted, modifier = Modifier.width(150.dp))
                            T(v, 12.sp, Fonts.bold, if (v == "1") C.Warn else C.Text)
                        }
                    }
                    if (snap.speedRaw.isNotEmpty()) Row {
                        T("Vitesse CAN", 14.sp, Fonts.medium, C.Muted, modifier = Modifier.width(110.dp))
                        T(snap.speedRaw, 14.sp, Fonts.bold, C.Text)
                    }
                }
                T(
                    "Ouvre une porte ou allume les phares : la valeur doit changer en 1 seconde. " +
                        "« Exporter » enregistre le rapport dans Téléchargements/ClioDiag.",
                    12.sp, Fonts.medium, C.Muted, maxLines = 5,
                )
                Spacer(Modifier.weight(1f))
                if (status.isNotEmpty()) T(status, 13.sp, Fonts.semi, if (busy) C.Warn else C.Ok, maxLines = 4)
                Box(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (busy || report == null) C.Panel2 else C.Accent)
                        .border(1.dp, C.Stroke, RoundedCornerShape(12.dp))
                        .clickable(enabled = !busy && report != null) {
                            if (CanDiagnostic.needsStoragePermission(ctx)) {
                                askStorage.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                startExport()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { T(if (busy) "Patiente…" else "Exporter", 18.sp, Fonts.bold) }
            }
            Box(Modifier.weight(1f).fillMaxHeight().panel().padding(14.dp)) {
                val r = report
                if (r == null) {
                    T("Analyse de l'autoradio…", 15.sp, Fonts.medium, C.Muted)
                } else {
                    Text(
                        r,
                        color = C.Text,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}
