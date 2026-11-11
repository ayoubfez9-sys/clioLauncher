package fr.ibiza.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

/**
 * v1.24 : rapport de compatibilité pour le QR code des réglages (Diagnostic CAN).
 * Autoradio, Android, puce, firmware, écran, applis CAN, données CAN reçues : tout tient dans l'adresse du QR code
 * (JSON aux clés courtes, compressé zlib, base64 « url »). Rien n'est envoyé depuis la voiture : le client scanne le
 * code avec son téléphone, la page de l'espace Car Launcher décode le rapport et il l'envoie d'un geste.
 */
final class Report {
    private Report() {}

    /** propriétés système utiles pour reconnaître le poste, le MCU et le boîtier CAN (les plus parlantes d'abord) */
    private static final Pattern[] KEEP = {
        Pattern.compile("mcu", Pattern.CASE_INSENSITIVE),
        Pattern.compile("canbox|canbus|can[._]?(type|mode|protocol|car|name)|car[._]?(type|model|name)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("soft_?ver|sw_?ver|hw_?ver|fyt|topway|ts10|ts18|hcn|syu|microntek|joying|customer|uis|unisoc", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^ro\\.(board\\.platform|product\\.board|build\\.version\\.incremental|hardware\\.chipname|chipname|soc\\.model)$")
    };
    private static final Pattern NOISE = Pattern.compile("dalvik|fingerprint|\\.sha|serial|imei|mac|wifi|bt_addr|bluetooth|password|token|key|time|date|boot_?count|uptime", Pattern.CASE_INSENSITIVE);

    static String build(Context c, String jsJson) {
        JSONObject res = new JSONObject();
        try {
            JSONObject r = new JSONObject();
            r.put("f", 1);
            // lanceur
            String ver = "?"; long code = 0;
            try {
                PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
                ver = pi.versionName; code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            } catch (Exception ignored) {}
            String pack = ""; try { pack = Packs.activeId(c); } catch (Throwable ignored) {}
            r.put("l", ver + "/" + code + "/" + c.getPackageName() + (pack.isEmpty() ? "" : "/" + pack));
            // poste
            r.put("d", Build.MANUFACTURER + "|" + Build.MODEL + "|" + Build.BRAND + "|" + Build.DEVICE + "|" + Build.BOARD + "|" + Build.HARDWARE);
            r.put("a", Build.VERSION.RELEASE + "/" + Build.VERSION.SDK_INT);
            r.put("b", cut(Build.DISPLAY, 60));
            Map<String, String> props = CanBridge.propsCopy();
            String soc = "";
            if (Build.VERSION.SDK_INT >= 31) { try { soc = (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).trim(); } catch (Throwable ignored) {} }
            if (soc.isEmpty() || soc.toLowerCase().contains("unknown")) {
                for (String k : new String[]{"ro.soc.model", "ro.hardware.chipname", "ro.chipname", "ro.board.platform", "ro.hardware"}) {
                    String v = props.get(k); if (v != null && !v.trim().isEmpty()) { soc = v.trim(); break; }
                }
            }
            if (soc.isEmpty()) soc = Build.HARDWARE;
            r.put("s", cut(soc, 40));
            try {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
                r.put("m", mi.totalMem / 1048576L);
            } catch (Throwable ignored) {}
            r.put("k", Runtime.getRuntime().availableProcessors());
            try {
                DisplayMetrics dm = c.getResources().getDisplayMetrics();
                r.put("e", dm.widthPixels + "x" + dm.heightPixels + "@" + dm.densityDpi);
            } catch (Throwable ignored) {}
            // applis CAN du poste
            JSONArray apps = new JSONArray();
            try {
                PackageManager pm = c.getPackageManager();
                for (String p : CanBridge.candidatePackages()) {
                    if (apps.length() >= 6) break;
                    try {
                        PackageInfo pi = pm.getPackageInfo(p, 0);
                        ApplicationInfo ai = pi.applicationInfo;
                        apps.put(new JSONArray().put(cut(String.valueOf(pm.getApplicationLabel(ai)), 24)).put(p).put(cut(String.valueOf(pi.versionName), 16)));
                    } catch (Exception ignored) {}
                }
            } catch (Throwable ignored) {}
            r.put("c", apps);
            // données CAN reçues
            JSONObject n = new JSONObject();
            n.put("fr", LogCan.frames);
            n.put("live", LogCan.live() ? 1 : 0);
            try { n.put("logs", CanBridge.logsAllowed() ? 1 : 0); } catch (Throwable ignored) {}
            r.put("n", n);
            try {
                if (Hub.carJson != null) {
                    JSONObject car = new JSONObject(Hub.carJson); List<String> ks = new ArrayList<>();
                    for (Iterator<String> it = car.keys(); it.hasNext(); ) { String k = it.next(); if (!"src".equals(k)) ks.add(k); }
                    StringBuilder j = new StringBuilder(); for (String k : ks) j.append(j.length() == 0 ? "" : ",").append(k);
                    r.put("o", cut(j.toString(), 160));
                }
            } catch (Throwable ignored) {}
            // propriétés système parlantes (MCU, boîtier CAN, firmware)
            JSONObject pr = new JSONObject(); int count = 0;
            for (Pattern pat : KEEP) {
                for (Map.Entry<String, String> e : new java.util.TreeMap<>(props).entrySet()) {
                    if (count >= 14) break;
                    String k = e.getKey(), v = e.getValue();
                    if (v == null || v.trim().isEmpty() || pr.has(k) || NOISE.matcher(k).find() || !pat.matcher(k).find()) continue;
                    if (v.trim().equals(soc) || v.trim().equals(Build.BOARD) || v.trim().equals(Build.HARDWARE)) continue;   // déjà dans « s » / « d »
                    pr.put(k, cut(v.trim(), 40)); count++;
                }
            }
            r.put("p", pr);
            // complété par la page (WebGL, version du WebView, voiture réglée)
            try {
                JSONObject js = new JSONObject(jsJson == null || jsJson.isEmpty() ? "{}" : jsJson);
                for (Iterator<String> it = js.keys(); it.hasNext(); ) { String k = it.next(); if (!r.has(k)) r.put(k, js.get(k)); }
            } catch (Exception ignored) {}
            byte[] raw = r.toString().getBytes("UTF-8");
            Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
            d.setInput(raw); d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[4096];
            while (!d.finished()) { int len = d.deflate(buf); out.write(buf, 0, len); }
            d.end();
            res.put("z", "z" + Base64.encodeToString(out.toByteArray(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
            res.put("r", r);
            Hub.log("RAPPORT : " + raw.length + " octets, " + out.size() + " compressés");
        } catch (Exception e) {
            try { res.put("error", String.valueOf(e)); } catch (Exception ignored) {}
        }
        return res.toString();
    }

    private static String cut(String s, int n) { if (s == null) return ""; return s.length() > n ? s.substring(0, n) : s; }
}
