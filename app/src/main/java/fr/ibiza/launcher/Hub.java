package fr.ibiza.launcher;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** État partagé (média, véhicule) + journal de diagnostic. Tout passe par le thread principal. */
public final class Hub {
    public interface Listener { void onEvent(String type, String json); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.FRANCE);   // v1.19 : millisecondes
    private static boolean observing = false;

    public static String mediaJson = "{}";
    public static String vehicleJson = "{\"doors\":{\"fl\":false,\"fr\":false,\"rl\":false,\"rr\":false,\"trunk\":false,\"hood\":false},\"radar\":null,\"reverse\":false}";
    public static boolean launcherVisible = false;
    /** émulateur Android Studio : aucune donnée voiture, on coupe les détections de secours (propriétés, textes d'écran) */
    public static final boolean EMULATOR = android.os.Build.HARDWARE.contains("goldfish") || android.os.Build.HARDWARE.contains("ranchu")
            || android.os.Build.FINGERPRINT.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("emulator");
    public static String lightsJson = null;
    private static final java.util.regex.Pattern LIGHTS = java.util.regex.Pattern.compile("(^|_|\\.)(ill|illum|illumination|headlight|head_light|headlamp|lamp|car_light|small_light|backlight_night)($|_|\\.)", java.util.regex.Pattern.CASE_INSENSITIVE);
    public static Context app;

    private Hub() {}

    public static void add(Listener l) { if (!LISTENERS.contains(l)) LISTENERS.add(l); }
    public static void remove(Listener l) { LISTENERS.remove(l); }

    public static void emit(final String type, final String json) {
        MAIN.post(new Runnable() { @Override public void run() {
            if ("media".equals(type)) mediaJson = json;
            if ("vehicle".equals(type)) { vehicleJson = json; DoorOverlay.update(json); }
            for (Listener l : new ArrayList<>(LISTENERS)) l.onEvent(type, json);
        }});
    }

    /* ------------ journal de diagnostic (anneau de 600 lignes) ------------ */
    public static synchronized void log(String line) {
        String l = TS.format(new Date()) + "  " + line;
        LOG.addLast(l);
        while (LOG.size() > 600) LOG.removeFirst();
        // v1.21 : écriture du fichier sur un fil à part (jamais d'accès disque sur le fil de l'interface)
        final String ll = l;
        JH.post(new Runnable() { public void run() { synchronized (JLOCK) { persist(ll); } } });
    }
    private static final Object JLOCK = new Object();
    private static final Handler JH;
    static { android.os.HandlerThread t = new android.os.HandlerThread("ibiza-journal", android.os.Process.THREAD_PRIORITY_BACKGROUND); t.start(); JH = new Handler(t.getLooper()); }

    /* v1.19 : le journal est aussi écrit dans Android/data/fr.ibiza.launcher/files/ibiza_journal.txt, pour qu'il survive à un
       redémarrage de l'autoradio ou du lanceur (écran noir, plantage). Taille bornée : au-delà de 400 Ko on garde les 200 derniers. */
    private static final String JFILE = "ibiza_journal.txt";
    private static java.io.Writer jout = null;
    private static final StringBuilder jpending = new StringBuilder();
    private static int jlines = 0;
    private static void persist(String l) {
        try {
            if (jout == null) {
                if (app == null) { if (jpending.length() < 30000) jpending.append(l).append('\n'); return; }
                File dir = app.getExternalFilesDir(null);
                if (dir == null) return;
                File f = new File(dir, JFILE);
                if (f.length() > 400000) trimFile(f, 200000);
                jout = new java.io.OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
                jout.write("\n===== lanceur démarré le " + new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(new Date()) + " =====\n");
                if (jpending.length() > 0) { jout.write(jpending.toString()); jpending.setLength(0); }
            }
            jout.write(l); jout.write('\n'); jout.flush();
            if (++jlines % 400 == 0) {
                File f = new File(app.getExternalFilesDir(null), JFILE);
                if (f.length() > 400000) { jout.close(); jout = null; trimFile(f, 200000); }
            }
        } catch (Throwable e) { try { if (jout != null) jout.close(); } catch (Throwable ignored) {} jout = null; }
    }
    private static void trimFile(File f, int keep) {
        try {
            long n = f.length(); if (n <= keep) return;
            java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r");
            byte[] b = new byte[keep]; r.seek(n - keep); r.readFully(b); r.close();
            FileOutputStream o = new FileOutputStream(f, false); o.write(b); o.close();
        } catch (Throwable ignored) {}
    }
    /** fin du journal enregistré (sessions précédentes comprises), pour l'export */
    static String savedTail(int max) {
        synchronized (JLOCK) { try { if (jout != null) jout.flush(); } catch (Throwable ignored) {} }
        try {
            File f = new File(app.getExternalFilesDir(null), JFILE);
            if (!f.exists()) return "";
            long n = f.length(); int len = (int) Math.min(n, max);
            java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r");
            byte[] b = new byte[len]; r.seek(n - len); r.readFully(b); r.close();
            return new String(b, "UTF-8");
        } catch (Throwable e) { return ""; }
    }
    public static synchronized String diag() {
        StringBuilder sb = new StringBuilder();
        // plus récent en premier
        Object[] a = LOG.toArray();
        for (int i = a.length - 1; i >= 0 && i >= a.length - 120; i--) sb.append(a[i]).append('\n');
        return sb.toString();
    }
    public static synchronized void clear() { LOG.clear(); }
    public static synchronized String export(Context c) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) dir = c.getFilesDir();
            File f = new File(dir, "ibiza_diag.txt");
            FileOutputStream o = new FileOutputStream(f);
            try { o.write(CanBridge.propsSnapshot().getBytes("UTF-8")); o.write(("vehicule = " + vehicleJson + "\nordi = " + carJson + "\nAndroid SDK " + android.os.Build.VERSION.SDK_INT + "\n\n").getBytes("UTF-8")); } catch (Exception ignored) {}
            for (String s : LOG) { o.write(s.getBytes("UTF-8")); o.write('\n'); }
            // v1.19 : journal enregistré, y compris avant le dernier redémarrage
            o.write("\n\n=== journal enregistré (sessions précédentes comprises, plus récent à la fin) ===\n".getBytes("UTF-8"));
            o.write(savedTail(150000).getBytes("UTF-8"));
            try { o.write(("\n\n" + Learn.summary() + "\n" + CanApp.summary()).getBytes("UTF-8")); } catch (Throwable ignored) {}
            o.close();
            return f.getAbsolutePath();
        } catch (Exception e) { return ""; }
    }

    /** Observe les réglages système : certains postes y écrivent l'état CAN (portes, marche arrière…). */
    public static void observeSettings(final Context c) {
        if (observing) return;
        observing = true;
        app = c.getApplicationContext();
        observe(app);
    }
    /* v1.27 : l'observateur gardait l'écran du lanceur (Activity) en mémoire pour toujours : après une recréation de
       l'écran (chien de garde, moteur web tué), l'ancien écran et sa WebView restaient en mémoire sur un poste à 2 Go. */
    private static void observe(final Context c) {
        final Uri[] uris = { Settings.System.CONTENT_URI, Settings.Global.CONTENT_URI, Settings.Secure.CONTENT_URI };
        for (final Uri base : uris) {
            try {
                c.getContentResolver().registerContentObserver(base, true, new ContentObserver(MAIN) {
                    @Override public void onChange(boolean self, Uri uri) {
                        if (uri == null) return;
                        String k = uri.getLastPathSegment();
                        String v = null;
                        try {
                            if (base == Settings.System.CONTENT_URI) v = Settings.System.getString(c.getContentResolver(), k);
                            else if (base == Settings.Global.CONTENT_URI) v = Settings.Global.getString(c.getContentResolver(), k);
                            else v = Settings.Secure.getString(c.getContentResolver(), k);
                        } catch (Exception ignored) {}
                        // bluetooth_name / bluetooth_address basculent toutes les 30 s sur certains postes hcn (GOCBT <-> Car BT) :
                        // ce bruit remplissait le journal et en chassait les événements portes / CAN
                        if (k != null && (k.startsWith("screen_brightness") || k.equals("next_alarm_formatted") || k.startsWith("bluetooth_"))) return;
                        log("SET " + base.getLastPathSegment() + "/" + k + " = " + v);
                        if (k != null && v != null && LIGHTS.matcher(k).find()) {
                            try { boolean on = Integer.parseInt(v.trim()) != 0; lightsJson = "{\"on\":" + on + ",\"key\":\"" + k + "\"}"; emit("lights", lightsJson); }
                            catch (Exception ignored) {}
                        }
                    }
                });
            } catch (Exception e) { log("observer KO " + base + " " + e); }
        }
    }

    /* ------------ données CAN venues des différentes sources ------------ */
    public static String carJson = null;
    private static final JSONObject CAR = new JSONObject();
    private static String lastDoorSrc = "";
    /** Fusionne des valeurs d'ordinateur de bord (outside, oil, coolant, battery, insp_km…) et prévient l'interface. */
    /** comme mergeCar, sans noter dans le journal les valeurs qui bougent sans arrêt (régime, vitesse) */
    public static synchronized void mergeCarQuiet(JSONObject part, String src) {
        try {
            boolean changed = false; JSONObject logged = new JSONObject();
            java.util.Iterator<String> it = part.keys();
            while (it.hasNext()) { String k = it.next(); Object v = part.get(k); if (!String.valueOf(v).equals(String.valueOf(CAR.opt(k)))) { CAR.put(k, v); changed = true; if (!k.equals("rpm") && !k.equals("speed")) logged.put(k, v); } }
            if (!changed) return;
            CAR.put("src", src); CAR.put("at", System.currentTimeMillis());
            carJson = CAR.toString();
            if (logged.length() > 0) log("ORDI (" + src + ") " + logged);
            emit("car", carJson);
        } catch (Exception ignored) {}
    }
    public static synchronized void mergeCar(JSONObject part, String src) {
        try {
            boolean changed = false;
            java.util.Iterator<String> it = part.keys();
            while (it.hasNext()) { String k = it.next(); Object v = part.get(k); if (!String.valueOf(v).equals(String.valueOf(CAR.opt(k)))) { CAR.put(k, v); changed = true; } }
            if (!changed) return;
            CAR.put("src", src); CAR.put("at", System.currentTimeMillis());
            carJson = CAR.toString();
            log("ORDI (" + src + ") " + part);
            emit("car", carJson);
        } catch (Exception ignored) {}
    }
    /** État des ouvrants lu par une source (diffusion, propriété…) ; garde radars / marche arrière. */
    public static void setDoors(String src, boolean[] d) {
        try {
            JSONObject v = new JSONObject(vehicleJson);
            JSONObject n = new JSONObject(vehicleFrom(d[0], d[1], d[2], d[3], d[4], d[5], false)).getJSONObject("doors");
            if (n.toString().equals(String.valueOf(v.optJSONObject("doors")))) return;
            v.put("doors", n);
            if (!src.equals(lastDoorSrc)) { log("PORTES : source = " + src); lastDoorSrc = src; }
            emit("vehicle", v.toString());
        } catch (Exception ignored) {}
    }

    /** v1.26 : angle du volant (degrés, 0 = droit), dernier connu */
    public static volatile Integer steer = null;
    /** v1.26 : radars (cm, null = rien / inconnu). rear == null : radars en veille. reverseGuess : marche arrière
        déduite des radars arrière (postes où Android ne voit pas la marche arrière), sauf si la carte apprise la donne. */
    public static void setRadar(String src, Integer[] rear, Integer[] front, int clear, boolean reverseGuess) {
        try {
            JSONObject v = new JSONObject(vehicleJson);
            Object before = v.opt("radar");
            if (rear == null && front == null) v.put("radar", JSONObject.NULL);
            else {
                JSONObject rd = new JSONObject(); org.json.JSONArray r = new org.json.JSONArray(), f = new org.json.JSONArray();
                for (int i = 0; i < 4; i++) {
                    r.put(rear == null || rear[i] == null ? JSONObject.NULL : (Object) Math.min(rear[i], clear));
                    f.put(front == null || front[i] == null ? JSONObject.NULL : (Object) Math.min(front[i], clear));
                }
                rd.put("rear", r); rd.put("front", f); v.put("radar", rd);
            }
            if (!Learn.reverseActive) v.put("reverse", reverseGuess);
            if (String.valueOf(before).equals(String.valueOf(v.opt("radar"))) && v.toString().equals(vehicleJson)) return;
            if ((before == null || before == JSONObject.NULL) != (rear == null && front == null)) log("RADARS (" + src + ") : " + (rear == null && front == null ? "en veille" : "actifs " + v.opt("radar")));
            emit("vehicle", v.toString());
        } catch (Exception ignored) {}
    }
    /** v1.25 : marche arrière lue par une source (carte apprise) ; garde portes et radars */
    public static void setReverse(String src, boolean on) {
        try {
            JSONObject v = new JSONObject(vehicleJson);
            if (v.optBoolean("reverse") == on) return;
            v.put("reverse", on);
            if (!on) v.put("radar", JSONObject.NULL);
            log("MARCHE ARRIÈRE (" + src + ") : " + (on ? "engagée" : "relâchée"));
            emit("vehicle", v.toString());
        } catch (Exception ignored) {}
    }
    /** v1.25 : lignes du journal en mémoire, plus anciennes d'abord (export par QR codes) */
    public static synchronized java.util.List<String> recent() { return new ArrayList<>(LOG); }

    public static String vehicleFrom(boolean fl, boolean fr, boolean rl, boolean rr, boolean trunk, boolean hood, boolean any) {
        try {
            JSONObject d = new JSONObject();
            d.put("fl", fl); d.put("fr", fr); d.put("rl", rl); d.put("rr", rr); d.put("trunk", trunk); d.put("hood", hood); d.put("any", any);
            JSONObject v = new JSONObject(); v.put("doors", d); v.put("radar", JSONObject.NULL); v.put("reverse", false);
            return v.toString();
        } catch (Exception e) { return vehicleJson; }
    }
}
