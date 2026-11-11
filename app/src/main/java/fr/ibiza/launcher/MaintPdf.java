package fr.ibiza.launcher;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StrictMode;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.11 — carnet d'entretien en PDF (A4), dessiné avec PdfDocument à partir des données préparées par le lanceur,
 * enregistré dans Téléchargements/Ibiza (Android 10+ : MediaStore ; Android 9 et avant : dossier public si
 * l'autorisation de stockage est donnée, sinon dossier de l'appli), puis ouvrable ou partageable.
 */
final class MaintPdf {
    static Uri lastUri;
    static String lastCar = "";
    private MaintPdf() {}

    private static final float W = 595f, H = 842f, M = 40f;
    private static final int INK = 0xFF1C1C1E, GREY = 0xFF6E6E73, LIGHT = 0xFFE5E5EA, LIME = 0xFF9DB01C;
    private static final int[] LEVEL = {0xFF2E9E4F, 0xFFE07A00, 0xFFD62F2F};

    private static Paint paint(float size, boolean bold, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTextSize(size); p.setColor(color);
        p.setTypeface(bold ? Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) : Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        return p;
    }

    /** découpe un texte en lignes qui tiennent dans la largeur donnée */
    private static List<String> wrap(String text, Paint p, float width) {
        List<String> out = new ArrayList<>();
        if (text == null || text.length() == 0) return out;
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            String t = line.length() == 0 ? w : line + " " + w;
            if (p.measureText(t) <= width) { line.setLength(0); line.append(t); continue; }
            if (line.length() > 0) out.add(line.toString());
            line.setLength(0); line.append(w);
            while (p.measureText(line.toString()) > width && line.length() > 1) {   // mot plus long que la colonne
                int n = p.breakText(line.toString(), true, width, null);
                out.add(line.substring(0, Math.max(1, n))); line.delete(0, Math.max(1, n));
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** une valeur en plusieurs morceaux (séparateur) : un morceau par ligne, « — » si vide */
    private static List<String> parts(String text, String sep, String prefix, Paint p, float width) {
        List<String> out = new ArrayList<>();
        if (text == null || text.length() == 0) { out.add("—"); return out; }
        String[] ps = text.split(java.util.regex.Pattern.quote(sep));
        for (int k = 0; k < ps.length; k++) out.addAll(wrap((k > 0 ? prefix : "") + ps[k], p, width));
        return out;
    }

    /** dessine le document ; renvoie le PDF prêt à écrire */
    static PdfDocument render(JSONObject r) {
        PdfDocument doc = new PdfDocument();
        Paint title = paint(22, true, INK), sub = paint(10.5f, false, GREY), th = paint(8.5f, true, GREY);
        Paint name = paint(10.5f, true, INK), cell = paint(9.5f, false, INK), small = paint(8.5f, false, GREY), foot = paint(7.5f, false, GREY);
        Paint line = new Paint(); line.setColor(LIGHT); line.setStrokeWidth(0.8f);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        // colonnes : opération | dernière fois | intervalle | prochaine échéance + état
        final float c0 = M + 16, c1 = 222, c2 = 334, c3 = 424, cEnd = W - M;
        JSONArray items = r.optJSONArray("items"); if (items == null) items = new JSONArray();

        int pageNo = 0; PdfDocument.Page page = null; Canvas cv = null; float y = 0;
        int n = items.length();
        for (int i = 0; i < Math.max(1, n); i++) {
            JSONObject it = i < n ? items.optJSONObject(i) : null;
            // contenu et hauteur de la ligne
            List<String> nameL = new ArrayList<>(), noteL = new ArrayList<>(), lastL = new ArrayList<>(), ivL = new ArrayList<>(), nextL = new ArrayList<>(), leftL = new ArrayList<>(), histL = new ArrayList<>();
            float rowH = 0;
            if (it != null) {
                nameL = wrap(it.optString("name"), name, c1 - c0 - 8);
                noteL = wrap(it.optString("note"), small, c1 - c0 - 8);
                lastL = parts(it.optString("last"), " · ", "", cell, c2 - c1 - 8);          // date, puis km à la ligne
                ivL = parts(it.optString("interval"), " / ", "ou ", cell, c3 - c2 - 8);     // « 30 000 km » / « ou 2 ans »
                nextL = wrap(it.optString("next"), cell, cEnd - c3);
                String left = !it.optBoolean("on", true) ? "Désactivé" : it.optString("left").length() > 0 ? it.optString("left") : it.optString("last").length() == 0 ? "Pas encore renseigné" : "";
                leftL = wrap(left, paint(9.5f, true, INK), cEnd - c3);
                JSONArray h = it.optJSONArray("hist");
                if (h != null && h.length() > 1) {
                    StringBuilder sb = new StringBuilder("Historique : ");
                    for (int k = 0; k < h.length(); k++) { if (k > 0) sb.append("  ·  "); sb.append(h.optString(k).replace(" · ", " à ")); }
                    histL = wrap(sb.toString(), small, cEnd - c0);
                }
                float colA = nameL.size() * 13.5f + noteL.size() * 11.5f;
                float colB = Math.max(lastL.size(), ivL.size()) * 12.5f;
                float colC = (nextL.size() + leftL.size()) * 12.5f;
                rowH = Math.max(colA, Math.max(colB, colC)) + histL.size() * 11.5f + 16;
            }
            // nouvelle page (la première, ou quand la ligne ne tient plus)
            if (page == null || y + rowH > H - M - 24) {
                if (page != null) { drawFooter(cv, foot, pageNo); doc.finishPage(page); }
                pageNo++;
                page = doc.startPage(new PdfDocument.PageInfo.Builder((int) W, (int) H, pageNo).create());
                cv = page.getCanvas(); y = M;
                if (pageNo == 1) {
                    fill.setColor(LIME); cv.drawRect(M, y, M + 46, y + 4, fill);
                    y += 30; cv.drawText(r.optString("title", "Carnet d'entretien"), M, y, title);
                    lastCar = r.optString("car", ""); String car = r.optString("car", "") + (r.optString("engine").length() > 0 ? " · " + r.optString("engine") : "");
                    Paint carP = paint(13, true, INK); cv.drawText(car, W - M - carP.measureText(car), y, carP);
                    y += 18;
                    String meta = "Exporté le " + r.optString("date") + (r.optString("km").length() > 0 ? "   ·   Kilométrage : " + r.optString("km") : "");
                    cv.drawText(meta, M, y, sub);
                    y += 22;
                } else y += 6;
                fill.setColor(0xFFF2F2F7); cv.drawRoundRect(new RectF(M, y, W - M, y + 22), 5, 5, fill);
                cv.drawText("OPÉRATION", c0, y + 14.5f, th); cv.drawText("DERNIÈRE FOIS", c1, y + 14.5f, th);
                cv.drawText("INTERVALLE", c2, y + 14.5f, th); cv.drawText("PROCHAINE ÉCHÉANCE", c3, y + 14.5f, th);
                y += 30;
            }
            if (it == null) continue;
            int lv = it.optInt("level", -1);
            boolean on = it.optBoolean("on", true);
            fill.setColor(!on ? LIGHT : lv >= 0 && lv <= 2 ? LEVEL[lv] : 0xFFC7C7CC);
            cv.drawCircle(M + 5, y + 5, 3.6f, fill);
            Paint nm = on ? name : paint(10.5f, true, GREY);
            float yy = y + 9;
            for (String s : nameL) { cv.drawText(s, c0, yy, nm); yy += 13.5f; }
            for (String s : noteL) { cv.drawText(s, c0, yy - 1, small); yy += 11.5f; }
            float yl = y + 9; for (String s : lastL) { cv.drawText(s, c1, yl, cell); yl += 12.5f; }
            float yi = y + 9; for (String s : ivL) { cv.drawText(s, c2, yi, cell); yi += 12.5f; }
            float yn = y + 9; for (String s : nextL) { cv.drawText(s, c3, yn, cell); yn += 12.5f; }
            Paint lp = paint(9.5f, true, !on ? GREY : lv >= 0 && lv <= 2 ? LEVEL[lv] : GREY);
            for (String s : leftL) { cv.drawText(s, c3, yn, lp); yn += 12.5f; }
            float yh = Math.max(Math.max(yy, yl), Math.max(yi, yn));
            for (String s : histL) { cv.drawText(s, c0, yh, small); yh += 11.5f; }
            y += rowH;
            cv.drawLine(M, y - 7, W - M, y - 7, line);
        }
        if (page != null) { drawFooter(cv, foot, pageNo); doc.finishPage(page); }
        return doc;
    }

    private static void drawFooter(Canvas cv, Paint p, int n) {
        if (cv == null) return;
        cv.drawText("Généré par le lanceur — intervalles indicatifs, à vérifier dans le carnet d'entretien du véhicule.", M, H - M + 8, p);
        String pg = "Page " + n; cv.drawText(pg, W - M - p.measureText(pg), H - M + 8, p);
    }

    /** enregistre le PDF ; renvoie un texte « où » pour l'utilisateur (ou lève une exception) */
    static String save(Context c, PdfDocument doc, String fileName) throws Exception {
        if (fileName == null || fileName.length() == 0) fileName = "Entretien-Ibiza.pdf";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Ibiza");
            Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("MediaStore");
            OutputStream os = cr.openOutputStream(u);
            if (os == null) throw new Exception("flux");
            try { doc.writeTo(os); } finally { os.close(); }
            lastUri = u;
            return "Téléchargements › Ibiza › " + fileName;
        }
        File dir; String where;
        boolean canPublic = Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if (canPublic) { dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Ibiza"); where = "Téléchargements › Ibiza › " + fileName; }
        else { dir = c.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS); where = "Android › data › fr.ibiza.launcher › files › Documents › " + fileName; }
        if (dir == null) throw new Exception("stockage indisponible");
        if (!dir.exists() && !dir.mkdirs() && canPublic) {   // dossier public refusé : dossier de l'appli
            dir = c.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS); where = "Android › data › fr.ibiza.launcher › files › Documents › " + fileName;
            if (dir == null) throw new Exception("stockage indisponible");
            dir.mkdirs();
        }
        File f = new File(dir, fileName);
        FileOutputStream fo = new FileOutputStream(f);
        try { doc.writeTo(fo); } finally { fo.close(); }
        try { MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, new String[]{"application/pdf"}, null); } catch (Exception ignored) {}
        // Android 9 et avant : lien file:// accepté par les visionneuses (pas de FileProvider dans ce projet)
        try { StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build()); } catch (Exception ignored) {}
        lastUri = Uri.fromFile(f);
        return where;
    }

    /** true si une appli a pu ouvrir le PDF */
    static boolean open(Context c) {
        if (lastUri == null) return false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(lastUri, "application/pdf");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) { Hub.log("PDF ouvrir : " + e); return false; }
    }

    static boolean share(Context c) {
        if (lastUri == null) return false;
        try {
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("application/pdf");
            s.putExtra(Intent.EXTRA_STREAM, lastUri);
            s.putExtra(Intent.EXTRA_SUBJECT, "Carnet d'entretien " + lastCar);
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent ch = Intent.createChooser(s, "Partager le carnet d'entretien");
            ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(ch);
            return true;
        } catch (Exception e) { Hub.log("PDF partager : " + e); return false; }
    }

}
