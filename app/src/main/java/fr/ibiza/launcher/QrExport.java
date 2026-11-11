package fr.ibiza.launcher;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;

/**
 * v1.25 : journal (ou résultat d'apprentissage) envoyé au téléphone par une suite de QR codes, sans clé USB ni internet.
 * Texte compressé (deflate brut), base64 « url », découpé en morceaux. Chaque QR code est une adresse
 *   <journalUrl>#j=<id>.<n°>.<total>.<morceau>
 * La page journal.html de l'espace Car Launcher (fournie dans docs/site/) rassemble les morceaux (appareil photo du
 * téléphone puis lecture continue dans la page), décompresse et propose d'envoyer le fichier. Le fragment « # » ne
 * part jamais sur le réseau : le contenu reste sur le téléphone tant que le client ne l'envoie pas.
 */
final class QrExport {
    private QrExport() {}

    static final int CHUNK = 560;            // caractères par QR code (version ~16, lisible sur un écran 1024x600)
    static final int MAX_PARTS = 24;

    /** kind : "learn" (résultat d'apprentissage) ou "journal" (journal récent + résultat) */
    static String build(String kind) {
        JSONObject res = new JSONObject();
        try {
            String head = "Car Launcher — " + ("learn".equals(kind) ? "apprentissage du véhicule" : "journal") + "\n" + Learn.summary() + "\n" + CanApp.summary() + "\n";
            String text;
            if ("learn".equals(kind)) text = head;
            else {
                String fixed = head + CanBridge.propsSnapshot() + "vehicule = " + Hub.vehicleJson + "\nordi = " + Hub.carJson + "\n\n=== journal récent (plus récent à la fin) ===\n";
                List<String> lines = Hub.recent();
                int lo = 0, hi = lines.size(), keep = 0;
                // nombre de lignes récentes qui tiennent dans MAX_PARTS QR codes (recherche par dichotomie)
                while (lo <= hi) {
                    int mid = (lo + hi) / 2;
                    if (pack(fixed + join(lines, lines.size() - mid)).length() <= CHUNK * MAX_PARTS) { keep = mid; lo = mid + 1; } else hi = mid - 1;
                }
                text = fixed + join(lines, lines.size() - keep) + (keep < lines.size() ? "\n(" + (lines.size() - keep) + " lignes plus anciennes non incluses)\n" : "");
            }
            String z = pack(text);
            String id = Long.toString(Math.abs(new Random().nextLong()) % 2176782336L, 36);
            int n = Math.max(1, (z.length() + CHUNK - 1) / CHUNK);
            JSONArray parts = new JSONArray();
            for (int i = 0; i < n; i++) parts.put("j=" + id + "." + (i + 1) + "." + n + "." + z.substring(i * CHUNK, Math.min(z.length(), (i + 1) * CHUNK)));
            res.put("parts", parts); res.put("bytes", text.length()); res.put("id", id);
            Hub.log("QR : " + kind + " " + text.length() + " caractères -> " + n + " QR codes");
        } catch (Exception e) {
            try { res.put("error", String.valueOf(e)); } catch (Exception ignored) {}
        }
        return res.toString();
    }

    private static String join(List<String> l, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, from); i < l.size(); i++) b.append(l.get(i)).append('\n');
        return b.toString();
    }

    static String pack(String text) throws Exception {
        byte[] raw = text.getBytes("UTF-8");
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(raw); d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192];
        while (!d.finished()) { int len = d.deflate(buf); out.write(buf, 0, len); }
        d.end();
        return Base64.encodeToString(out.toByteArray(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
}
