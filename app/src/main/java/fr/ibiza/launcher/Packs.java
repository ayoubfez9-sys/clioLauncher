package fr.ibiza.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.Enumeration;

/**
 * v1.23 : packs voiture installables sans recompiler ni réinstaller.
 *
 * Un pack est un fichier .zip :
 *   pack.json              {"format":1, "id":"seat-ibiza-6j-blanc", "name":"Seat Ibiza 6J blanche", "version":"1",
 *                           "config":{ frames, doorViews, doorSteps, homeView, hiView, comboAnim, topView, topBox,
 *                                      cockpit, flowMax, car:{name, model} }}
 *   preview.jpg            (facultatif) aperçu
 *   img/car/…  img/top/…  img/toph/…  img/cockpit/…   mêmes noms de fichiers que dans l'appli
 *
 * Installé dans Android/data/fr.ibiza.launcher/files/packs/<id>/. Le pack actif remplace les images de l'appli
 * (MainActivity.serve) et ses réglages d'images remplacent ceux de js/config.js (v1.24.1). Il reste installé après une
 * mise à jour du lanceur.
 * Import : sélecteur de fichiers d'Android (clé USB), ou zip déposé dans le dossier packs puis « Importer depuis le dossier ».
 */
final class Packs {
    private Packs() {}

    static final String PREF = "carpack";
    private static final long MAX_TOTAL = 120L * 1024 * 1024, MAX_ENTRY = 40L * 1024 * 1024;
    private static final int MAX_ENTRIES = 4000;
    private static final String[] CONFIG_KEYS = { "frames", "doorViews", "doorSteps", "homeView", "hiView", "comboAnim",
            "topView", "topBox", "cockpit", "flowMax" };

    static File dir(Context c) {
        File d = c.getExternalFilesDir("packs");
        if (d == null) d = new File(c.getFilesDir(), "packs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static String activeId(Context c) {
        return c.getSharedPreferences("ibz", Context.MODE_PRIVATE).getString(PREF, "");
    }

    /** dossier du pack actif, ou null (images d'origine de l'appli) */
    static File activeDir(Context c) {
        String id = activeId(c);
        if (!validId(id)) return null;
        File d = new File(dir(c), id);
        return new File(d, "pack.json").isFile() ? d : null;
    }

    static boolean validId(String id) { return id != null && id.matches("[a-z0-9][a-z0-9-]{1,60}"); }

    /** v1.24.1 : change à chaque changement de voiture (pack activé, supprimé, réinstallé) ; les fenêtres natives
     *  (DoorOverlay, MovingAlert) rechargent alors leur vue du dessus */
    static volatile int gen = 0;

    /** identifiant du pack réellement affiché ("" = voiture de l'appli), pour le contrôle fait par la page au chargement */
    static String shownId(Context c) { File d = activeDir(c); return d == null ? "" : d.getName(); }

    static void setActive(Context c, String id) {
        SharedPreferences.Editor e = c.getSharedPreferences("ibz", Context.MODE_PRIVATE).edit();
        if (id == null || id.isEmpty()) e.remove(PREF); else e.putString(PREF, id);
        e.commit();          // v1.24.1 : écrit tout de suite (la page est rechargée juste après)
        gen++;
    }

    /** v1.24.1 : vrai si ce fichier appartient à la voiture (img/car, img/top, img/toph, img/cockpit) : quand un pack est
     *  actif, ces fichiers viennent du pack SEULEMENT. Avant, un fichier absent du pack était pris dans l'appli : la vue du
     *  dessus (rl, rr), les flux de rotation ou les vues de face de la voiture intégrée se mélangeaient à celles du pack. */
    static boolean carFile(String rel) { return rel != null && rel.matches("img/(car|top|toph|cockpit)/.*"); }

    /** vue du dessus couchée (avant à gauche) pour les fenêtres natives : celle du pack actif, sinon celle de l'appli.
     *  Pack sans img/toph : img/top tournée de -90°. Ouvrant absent du pack (ex. 3 portes : rl, rr) : null. */
    static android.graphics.Bitmap topBitmap(Context c, String name) {
        try {
            File d = activeDir(c);
            if (d != null) {
                if (new File(d, "img/toph/closed.webp").isFile()) {
                    File f = new File(d, "img/toph/" + name + ".webp");
                    return f.isFile() ? android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
                }
                File f = new File(d, "img/top/" + name + ".webp");
                if (!f.isFile()) return null;
                android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                if (b == null || b.getWidth() < 2) return b;
                android.graphics.Matrix m = new android.graphics.Matrix(); m.postRotate(-90);
                android.graphics.Bitmap r = android.graphics.Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                return r;
            }
            InputStream in = c.getAssets().open("www/img/toph/" + name + ".webp");
            try { return android.graphics.BitmapFactory.decodeStream(in); } finally { in.close(); }
        } catch (Throwable e) { return null; }
    }

    static JSONObject manifest(File packDir) {
        try { return new JSONObject(readText(new File(packDir, "pack.json"))); } catch (Exception e) { return null; }
    }

    /** image d'un pack demandée par la page (chemin du type « img/car/hero_00.webp »), ou null */
    static File file(Context c, String rel) {
        File d = activeDir(c);
        if (d == null || !allowedPath(rel) || rel.equals("pack.json")) return null;
        File f = new File(d, rel);
        return f.isFile() ? f : null;
    }

    /** aperçu d'un pack installé, pour la liste des réglages */
    static File preview(Context c, String id) {
        if (!validId(id)) return null;
        File f = new File(new File(dir(c), id), "preview.jpg");
        return f.isFile() ? f : null;
    }

    /** ligne ajoutée à js/config.js quand un pack est actif : ses réglages complètent ceux de l'appli */
    static String configPatch(Context c) {
        File d = activeDir(c);
        if (d == null) return "";
        JSONObject m = manifest(d);
        if (m == null) return "";
        JSONObject cfg = m.optJSONObject("config");
        if (cfg == null) cfg = new JSONObject();
        JSONObject out = new JSONObject();
        try {
            for (String k : CONFIG_KEYS) if (cfg.has(k)) out.put(k, cfg.get(k));
            // v1.24.1 : les réglages d'images REMPLACENT ceux de la voiture intégrée. Avant, une clé absente du pack gardait la
            // valeur de l'appli (flowMax, topView, cockpit, vue de face…) et allait chercher des images que le pack n'a pas.
            // Chaque option n'est gardée que si le pack contient vraiment ses images.
            out.put("doorViews", cfg.optJSONObject("doorViews") != null ? cfg.getJSONObject("doorViews") : new JSONObject());
            out.put("topView", cfg.optBoolean("topView", false) && new File(d, "img/top/closed.webp").isFile());
            out.put("cockpit", cfg.optBoolean("cockpit", false) && anyFile(new File(d, "img/cockpit"), ""));
            out.put("hiView", cfg.optBoolean("hiView", false) && anyFile(new File(d, "img/car"), "door_hi_"));
            out.put("comboAnim", cfg.optBoolean("comboAnim", false) && anyFile(new File(d, "img/car"), "door_m"));
            out.put("flowMax", new File(d, "img/car/flow_00.png").isFile() ? cfg.optInt("flowMax", 0) : 0);   // sans flux : fondu enchaîné
            out.put("carPack", true);
            JSONObject car = cfg.optJSONObject("car");
            JSONObject info = new JSONObject();
            info.put("id", d.getName()); info.put("name", m.optString("name")); info.put("version", m.optString("version"));
            String carJson = car == null ? "null" : car.toString();
            return "\n/* pack voiture actif */\n(function (c, p, car) { for (var k in p) c[k] = p[k];"
                    + " if (car) { c.car = c.car || {}; if (car.name) c.car.name = car.name; if (car.model) c.car.model = car.model; } })"
                    + "(window.IBZ_CONFIG = window.IBZ_CONFIG || {}, " + out.toString() + ", " + carJson + ");\n"
                    + "window.IBZ_PACK = " + info.toString() + ";\n";
        } catch (Exception e) { return ""; }
    }

    /** liste des packs installés, pour les réglages */
    static String list(Context c) {
        JSONArray a = new JSONArray();
        String active = activeId(c);
        java.util.Set<String> bundled = new java.util.HashSet<>();     // v1.24.3 : packs fournis avec l'appli (« modèle par défaut »)
        try { String[] zs0 = c.getAssets().list("packs"); if (zs0 != null) for (String z : zs0) if (z.endsWith(".zip")) bundled.add(z.substring(0, z.length() - 4)); } catch (Exception ignored) {}
        File[] ds = dir(c).listFiles();
        if (ds != null) for (File d : ds) {
            if (!d.isDirectory() || d.getName().startsWith(".")) continue;
            JSONObject m = manifest(d);
            if (m == null) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("id", d.getName()); o.put("name", m.optString("name", d.getName())); o.put("version", m.optString("version"));
                o.put("size", size(d)); o.put("active", d.getName().equals(active));
                o.put("preview", new File(d, "preview.jpg").isFile());
                o.put("bundled", bundled.contains(d.getName()));
                a.put(o);
            } catch (Exception ignored) {}
        }
        int waiting = 0;
        File[] zs = dir(c).listFiles();
        if (zs != null) for (File z : zs) if (z.isFile() && z.getName().toLowerCase().endsWith(".zip")) waiting++;
        boolean builtin;
        try { c.getAssets().open("www/img/car/hero_00.webp").close(); builtin = true; } catch (Exception e) { builtin = false; }
        try { return new JSONObject().put("packs", a).put("active", active).put("waiting", waiting).put("builtin", builtin).put("folder", dir(c).getAbsolutePath()).toString(); }
        catch (Exception e) { return "{\"packs\":[]}"; }
    }

    static boolean delete(Context c, String id) {
        if (!validId(id)) return false;
        File d = new File(dir(c), id);
        if (!d.isDirectory()) return false;
        if (id.equals(activeId(c))) setActive(c, "");
        deleteTree(d);
        gen++;
        Hub.log("PACK : supprimé " + id);
        return true;
    }

    /** installe un pack depuis un fichier zip et l'affiche ; renvoie {ok, id, name} ou {ok:false, error} */
    static JSONObject install(Context c, File zipFile) { return install(c, zipFile, true); }

    static JSONObject install(Context c, File zipFile, boolean activate) {
        JSONObject res = new JSONObject();
        File root = dir(c);
        File tmp = new File(root, ".import-" + System.nanoTime());
        ZipFile zf = null;
        try {
            zf = new ZipFile(zipFile);
            // pack.json peut être à la racine du zip ou dans un dossier de tête (zip fait en compressant le dossier du pack)
            String prefix = null; int n = 0;
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (++n > MAX_ENTRIES) throw new Exception("trop de fichiers dans le zip");
                String name = e.getName().replace('\\', '/');
                if (name.startsWith("__MACOSX/")) continue;
                if (name.equals("pack.json")) { prefix = ""; break; }
                if (name.endsWith("/pack.json") && name.indexOf('/') == name.length() - 10 && prefix == null) prefix = name.substring(0, name.length() - 9);
            }
            if (prefix == null) throw new Exception("pack.json absent : ce zip n'est pas un pack Car Launcher");
            tmp.mkdirs();
            long total = 0; int files = 0;
            byte[] buf = new byte[65536];
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory() || !name.startsWith(prefix)) continue;
                String rel = name.substring(prefix.length());
                if (!allowedPath(rel)) continue;                          // fichier sans rapport : ignoré
                File out = new File(tmp, rel);
                if (!out.getCanonicalPath().startsWith(tmp.getCanonicalPath() + File.separator)) throw new Exception("chemin interdit dans le zip");
                out.getParentFile().mkdirs();
                long entry = 0;
                InputStream in = zf.getInputStream(e);
                OutputStream os = new FileOutputStream(out);
                try {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        entry += r; total += r;
                        if (entry > MAX_ENTRY || total > MAX_TOTAL) throw new Exception("pack trop gros");
                        os.write(buf, 0, r);
                    }
                } finally { os.close(); in.close(); }
                files++;
            }
            JSONObject m = new JSONObject(readText(new File(tmp, "pack.json")));
            String id = m.optString("id");
            if (!validId(id)) throw new Exception("identifiant de pack invalide");
            if (m.optInt("format", 1) > 1) throw new Exception("pack trop récent : mets à jour le lanceur");
            JSONObject cfg = m.optJSONObject("config");
            int frames = cfg == null ? 48 : cfg.optInt("frames", 48);
            for (int i = 0; i < Math.max(1, frames); i++) {
                String f = String.format(java.util.Locale.US, "img/car/hero_%02d.webp", i);
                if (!new File(tmp, f).isFile()) throw new Exception("image manquante dans le pack : " + f);
            }
            File dest = new File(root, id);
            if (dest.exists()) deleteTree(dest);
            if (!tmp.renameTo(dest)) throw new Exception("installation impossible (stockage plein ?)");
            if (activate) setActive(c, id); else gen++;
            Hub.log("PACK : installé" + (activate ? " et activé " : " ") + id + " (" + files + " fichiers, " + (total / 1024) + " Ko)");
            res.put("ok", true); res.put("id", id); res.put("name", m.optString("name", id));
        } catch (Exception ex) {
            deleteTree(tmp);
            String msg = ex instanceof java.util.zip.ZipException ? "fichier zip abîmé ou incomplet" : String.valueOf(ex.getMessage());
            Hub.log("PACK : import refusé : " + msg);
            try { res.put("ok", false); res.put("error", msg); } catch (Exception ignored) {}
        } finally {
            try { if (zf != null) zf.close(); } catch (Exception ignored) {}
        }
        return res;
    }

    /** installe depuis un flux (sélecteur de fichiers) : copie d'abord dans un fichier temporaire */
    static JSONObject install(Context c, InputStream in) {
        File t = new File(c.getCacheDir(), "pack-import.zip");
        try {
            OutputStream os = new FileOutputStream(t);
            try { byte[] b = new byte[65536]; int r; long tot = 0; while ((r = in.read(b)) > 0) { tot += r; if (tot > MAX_TOTAL) throw new Exception("pack trop gros"); os.write(b, 0, r); } }
            finally { os.close(); in.close(); }
            return install(c, t);
        } catch (Exception e) {
            JSONObject r = new JSONObject();
            try { r.put("ok", false); r.put("error", "lecture du fichier impossible : " + e.getMessage()); } catch (Exception ignored) {}
            return r;
        } finally { t.delete(); }
    }

    /** packs livrés avec l'appli (assets/packs/<id>.zip) : installés une fois au premier lancement, sans changer la
     *  voiture affichée. S'ils sont supprimés ensuite dans les réglages, ils ne reviennent pas. */
    /** v1.24.3 : la Clio 4 est fournie (assets/packs) : installée au premier lancement, sans être activée */
    static void installBundled(Context c) {
        try {
            String[] zs = c.getAssets().list("packs");
            if (zs == null || zs.length == 0) return;
            SharedPreferences sp = c.getSharedPreferences("ibz", Context.MODE_PRIVATE);
            String done = sp.getString("packs_bundled", "");
            for (String z : zs) {
                if (!z.endsWith(".zip")) continue;
                String id = z.substring(0, z.length() - 4);
                if (!validId(id) || ("," + done + ",").contains("," + id + ",")) continue;
                if (!new File(new File(dir(c), id), "pack.json").isFile()) {
                    File t = new File(c.getCacheDir(), "pack-bundled.zip");
                    InputStream in = c.getAssets().open("packs/" + z);
                    OutputStream os = new FileOutputStream(t);
                    try { byte[] b = new byte[65536]; int r; while ((r = in.read(b)) > 0) os.write(b, 0, r); } finally { os.close(); in.close(); }
                    JSONObject r = install(c, t, false);
                    t.delete();
                    if (!r.optBoolean("ok")) { Hub.log("PACK : pack fourni " + id + " non installé : " + r.optString("error")); continue; }
                }
                done = done.isEmpty() ? id : done + "," + id;
                sp.edit().putString("packs_bundled", done).apply();
            }
        } catch (Exception e) { Hub.log("PACK : packs fournis : " + e); }
    }

    /** vue de la voiture intégrée à l'appli (pour sa miniature dans les réglages), même si un pack est actif */
    static String builtinView(Context c) {
        try {
            InputStream in = c.getAssets().open("www/js/config.js");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try { byte[] b = new byte[8192]; int r; while ((r = in.read(b)) > 0) bo.write(b, 0, r); } finally { in.close(); }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"homeView\"\\s*:\\s*(\\d+)").matcher(bo.toString("UTF-8"));
            int v = m.find() ? Integer.parseInt(m.group(1)) : 8;
            return String.format(java.util.Locale.US, "www/img/car/hero_%02d.webp", v);
        } catch (Exception e) { return "www/img/car/hero_08.webp"; }
    }

    /** importe les .zip déposés dans le dossier packs (sans sélecteur de fichiers) */
    static JSONObject importFolder(Context c) {
        JSONObject res = new JSONObject();
        int ok = 0; String last = null, err = null;
        File[] zs = dir(c).listFiles();
        if (zs != null) for (File z : zs) {
            if (!z.isFile() || !z.getName().toLowerCase().endsWith(".zip")) continue;
            try {
                JSONObject r = install(c, z);
                if (r.optBoolean("ok")) { ok++; last = r.optString("name"); z.delete(); } else err = z.getName() + " : " + r.optString("error");
            } catch (Exception e) { err = z.getName() + " : " + e.getMessage(); }
        }
        try {
            res.put("ok", ok > 0); res.put("count", ok);
            if (last != null) res.put("name", last);
            if (err != null) res.put("error", err); else if (ok == 0) res.put("error", "aucun fichier .zip dans le dossier " + dir(c).getAbsolutePath());
        } catch (Exception ignored) {}
        return res;
    }

    static boolean allowedPath(String rel) {
        if (rel == null || rel.isEmpty() || rel.startsWith("/") || rel.contains("..") || rel.contains("\\")) return false;
        if (rel.equals("pack.json") || rel.equals("preview.jpg")) return true;
        if (!rel.matches("img/(car|top|toph|cockpit)/[A-Za-z0-9_.-]{1,80}")) return false;
        String l = rel.toLowerCase();
        return l.endsWith(".webp") || l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".mp4");
    }

    private static boolean anyFile(File dir, String prefix) {
        String[] fs = dir.list();
        if (fs != null) for (String f : fs) if (f.startsWith(prefix) && !f.startsWith(".")) return true;
        return false;
    }

    private static long size(File d) {
        long s = 0; File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) s += f.isDirectory() ? size(f) : f.length();
        return s;
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) deleteTree(x);
        f.delete();
    }

    static String readText(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int r;
            while ((r = in.read(b)) > 0) { bo.write(b, 0, r); if (bo.size() > 1_000_000) throw new Exception("pack.json trop gros"); }
            return bo.toString("UTF-8");
        } finally { in.close(); }
    }
}
