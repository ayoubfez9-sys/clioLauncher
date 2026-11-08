package fr.ayoub.cliolauncher.data

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Outil de diagnostic : cherche comment le systeme de l'autoradio expose
 * les donnees du boitier CAN (portes, feux, vitesse) aux autres applis.
 * Il ne modifie rien : il lit la liste des applis et copie des fichiers dans Telechargements.
 */
object CanDiagnostic {

    /** Mots qui trahissent une appli liee a la voiture / au boitier CAN / au microcontroleur. */
    private val KEYWORDS = listOf(
        "can", "mcu", "car", "vehicle", "syu", "hiworld", "raise", "rzc", "xbs", "simple", "binary",
        "bnr", "junsun", "carinfo", "factory", "door", "serial", "uart", "bus", "auto", "kld", "hzbhd", "ts",
    )

    /** Applis a ne pas confondre avec le CAN (faux positifs frequents). */
    private val IGNORE = listOf(
        "com.android.", "com.google.", "android.", "fr.ayoub.", "com.zjinnova", "carplay", "carlink", "camera",
    )

    data class Candidate(val info: PackageInfo, val label: String, val score: Int)

    private fun scoreOf(pkg: String, label: String): Int {
        val s = (pkg + " " + label).lowercase()
        var score = 0
        for (k in KEYWORDS) {
            if (Regex("(^|[._ ])$k([._ ]|$)").containsMatchIn(s)) score += 3
            else if (k.length >= 3 && s.contains(k)) score += 1
        }
        return score
    }

    @Suppress("DEPRECATION")
    fun candidates(ctx: Context): List<Candidate> {
        val pm = ctx.packageManager
        val all = pm.getInstalledPackages(0)
        val out = ArrayList<Candidate>()
        for (p in all) {
            val ai = p.applicationInfo ?: continue
            val isSystem = (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            if (!isSystem) continue
            val pkg = p.packageName
            if (IGNORE.any { pkg.startsWith(it) || pkg.contains(it) }) continue
            val label = try { ai.loadLabel(pm).toString() } catch (e: Exception) { pkg }
            val score = scoreOf(pkg, label)
            if (score > 0) out += Candidate(p, label, score)
        }
        return out.sortedByDescending { it.score }
    }

    @Suppress("DEPRECATION")
    private fun details(ctx: Context, pkg: String): PackageInfo? = try {
        ctx.packageManager.getPackageInfo(
            pkg,
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS,
        )
    } catch (e: Exception) { null }

    /** Rapport texte lisible (affiche a l'ecran et enregistre dans le fichier). */
    @Suppress("DEPRECATION")
    fun report(ctx: Context): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE).format(Date())
        sb.appendLine("=== Diagnostic CAN Clio Launcher ($now) ===")
        sb.appendLine("Fabricant: ${Build.MANUFACTURER} | Modele: ${Build.MODEL} | Produit: ${Build.PRODUCT}")
        sb.appendLine("Carte: ${Build.BOARD} | Materiel: ${Build.HARDWARE} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Empreinte: ${Build.FINGERPRINT}")
        sb.appendLine()

        val props = listOf(
            "ro.product.board", "ro.board.platform", "ro.build.display.id", "ro.product.customize",
            "persist.sys.canbus", "persist.canbus.type", "persist.sys.can.type", "ro.mcu.version",
            "persist.sys.mcu", "sys.mcu.version", "persist.fyt.platform", "ro.fota.platform",
        )
        sb.appendLine("--- Proprietes systeme ---")
        for (k in props) {
            val v = sysProp(k)
            if (v.isNotEmpty()) sb.appendLine("$k = $v")
        }
        sb.appendLine()

        sb.appendLine("--- Fichiers serie (liaison possible avec le MCU / boitier CAN) ---")
        File("/dev").listFiles { f -> f.name.startsWith("ttyS") || f.name.startsWith("ttyUSB") || f.name.contains("mcu") || f.name.contains("can") }
            ?.sortedBy { it.name }
            ?.forEach { f -> sb.appendLine("${f.path}  lecture=${f.canRead()} ecriture=${f.canWrite()}") }
            ?: sb.appendLine("(liste /dev non lisible)")
        sb.appendLine()

        val cands = candidates(ctx)
        sb.appendLine("--- Applis systeme candidates (${cands.size}) ---")
        for (c in cands) {
            val p = details(ctx, c.info.packageName) ?: c.info
            val ai = p.applicationInfo
            sb.appendLine()
            sb.appendLine("## ${c.label}  [${p.packageName}]  score=${c.score}  version=${p.versionName}")
            sb.appendLine("   apk: ${ai?.sourceDir}  uid=${ai?.uid}")
            p.services?.forEach { s ->
                sb.appendLine("   SERVICE  ${s.name}  exporte=${s.exported}  permission=${s.permission ?: "-"}")
            }
            p.receivers?.forEach { r ->
                sb.appendLine("   RECEPTEUR ${r.name}  exporte=${r.exported}  permission=${r.permission ?: "-"}")
            }
            p.providers?.forEach { pr ->
                sb.appendLine("   PROVIDER ${pr.name}  autorite=${pr.authority}  exporte=${pr.exported}  lecture=${pr.readPermission ?: "-"}")
            }
            p.permissions?.forEach { perm -> sb.appendLine("   PERMISSION DEFINIE ${perm.name}  niveau=${perm.protectionLevel}") }
            sb.appendLine("   activites: ${p.activities?.size ?: 0}")
        }
        sb.appendLine()

        sb.appendLine("--- Toutes les applis systeme (pour reperer ce qui aurait ete rate) ---")
        val pm = ctx.packageManager
        pm.getInstalledPackages(0)
            .filter { (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0 }
            .map { it.packageName }
            .sorted()
            .forEach { sb.appendLine(it) }
        return sb.toString()
    }

    /**
     * Ecrit le rapport et une copie des applis candidates dans Telechargements/ClioDiag.
     * @return le nombre de fichiers ecrits
     */
    fun export(ctx: Context, reportText: String, maxApks: Int = 12, progress: (String) -> Unit): Int {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.FRANCE).format(Date())
        var written = 0
        progress("Rapport…")
        write(ctx, "rapport-can-$stamp.txt", "text/plain") { it.write(reportText.toByteArray()) }
        written++
        for (c in candidates(ctx).filter { it.score >= 3 }.take(maxApks)) {
            val ai = c.info.applicationInfo ?: continue
            val files = listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
            files.forEachIndexed { i, path ->
                val f = File(path)
                if (!f.canRead() || f.length() > 80L * 1024 * 1024) return@forEachIndexed
                progress("${c.label} (${f.length() / 1024} Ko)…")
                val name = c.info.packageName + (if (i == 0) "" else "-split$i") + ".apk"
                write(ctx, name, "application/vnd.android.package-archive") { out ->
                    f.inputStream().use { it.copyTo(out) }
                }
                written++
            }
        }
        return written
    }

    /** Android 9 et moins : besoin de l'autorisation "stockage" pour ecrire dans Telechargements. */
    fun needsStoragePermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    private fun write(ctx: Context, name: String, mime: String, body: (java.io.OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT < 29) {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ClioDiag")
            if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Impossible de créer ${dir.path}")
            File(dir, name).outputStream().use(body)
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClioDiag")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Impossible de créer $name")
        ctx.contentResolver.openOutputStream(uri)?.use(body)
            ?: throw IllegalStateException("Impossible d'écrire $name")
    }

    /** Lecture d'une propriete systeme (API cachee, lecture seule). */
    private fun sysProp(key: String): String = try {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
    } catch (e: Exception) { "" }
}
