package fr.ayoub.cliolauncher.ui

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
                T("CE QUE ÇA FAIT", 12.sp, Fonts.semi, C.Accent, spacing = 2.sp)
                T(
                    "Cet outil repère les applis de l'autoradio qui gèrent le boîtier CAN SimpleSoft. " +
                        "Il ne modifie rien.\n\n" +
                        "1. Touche « Exporter ».\n" +
                        "2. Copie le dossier Téléchargements/ClioDiag sur une clé USB.\n" +
                        "3. Envoie ces fichiers à Claude pour analyse.",
                    14.sp, Fonts.medium, C.Muted, maxLines = 14,
                )
                Spacer(Modifier.weight(1f))
                if (status.isNotEmpty()) T(status, 13.sp, Fonts.semi, if (busy) C.Warn else C.Ok, maxLines = 4)
                Box(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (busy || report == null) C.Panel2 else C.Accent)
                        .border(1.dp, C.Stroke, RoundedCornerShape(12.dp))
                        .clickable(enabled = !busy && report != null) {
                            val text = report ?: return@clickable
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
