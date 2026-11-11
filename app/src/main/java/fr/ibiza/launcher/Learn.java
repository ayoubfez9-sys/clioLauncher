package fr.ibiza.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.25 : « Apprendre ma voiture » — sondage guidé, sans adb, qui trouve tout seul où le poste publie les portes, la
 * marche arrière et les feux, puis s'en sert en direct.
 *
 * Pendant l'apprentissage, toutes les sources lisibles par une appli normale sont relevées en continu :
 *   P  propriétés système (getprop complet toutes les 0,35 s)
 *   S  réglages système (tables Settings System / Global / Secure)
 *   B  diffusions des applis CAN (actions trouvées dans leurs APK, voir CanBridge) : chaque champ, et chaque octet
 *   T  trames du boîtier CAN sur les postes TopWay (TWUtil, voir Ts10Probe) : chaque octet
 *   A  fenêtres des autres applis (accessibilité) : identifiants des éléments visibles
 * Le client suit les étapes à l'écran (« ouvre la porte avant gauche », « referme-la »…). Chaque étape est découpée en
 * deux phases ; un signal est retenu pour un ouvrant s'il change pendant « ouvert », revient à sa valeur pendant
 * « fermé », et ne bouge pas tout seul pendant le relevé au repos (compteurs, heure… écartés). Plusieurs ouvrants
 * sur le même signal avec des bits différents (ex. octet 0x40 / 0x80 / 0x10 / 0x20) donnent un décodage par masque.
 *
 * Le résultat (« carte apprise ») est gardé dans les préférences et dans learn.json ; en direct, seules les quelques
 * clés retenues sont relues (0,3 s), et les autres sources de portes se taisent.
 */
final class Learn {
    private Learn() {}

    static final String[] EVENTS = { "fl", "fr", "rl", "rr", "trunk", "hood", "reverse", "lights" };
    private static final Pattern NOISE_KEY = Pattern.compile("time|date|uptime|boot_?count|clock|alarm|battery|volume|wifi|bluetooth|bt_|signal|gps|location|cpu|thermal|temp_?zone|mem|storage|net\\.|dhcp|dns|^P:ro\\.|^P:init\\.svc|^P:dev\\.|^P:sys\\.usb|^P:log\\.|screen_off|heads_up|zen|ringer|^S:secure/(android_id|bluetooth|enabled_accessibility)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROP_LINE = Pattern.compile("^\\[(.+?)\\]: \\[(.*)\\]$");

    private static Context app;
    private static Handler bg;
    static volatile boolean learning = false;
    /** vrai quand une carte apprise donne les portes : les autres sources de portes se taisent */
    static volatile boolean doorsActive = false;
    static volatile boolean reverseActive = false;
    /** v1.26 : premier capteur arrière des radars hcn (0 ou 4) vu en marche arrière pendant l'apprentissage, -1 inconnu */
    static volatile int radarRearStart = -1;

    /* ---------- relevé ---------- */
    private static final Map<String, String> cur = new ConcurrentHashMap<>();
    private static final Set<String> noisy = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final class Phase {
        final String ev; final boolean on; final long t0 = System.currentTimeMillis();
        final Map<String, String> start; final Map<String, List<String>> seen = new HashMap<>(); final Map<String, Long> first = new HashMap<>();
        Map<String, String> end; long t1;
        Phase(String ev, boolean on, Map<String, String> start) { this.ev = ev; this.on = on; this.start = start; }
    }
    private static final List<Phase> phases = new ArrayList<>();
    /* v1.25.1 : signaux déjà attribués aux étapes précédentes (clé -> élément -> valeur « ouvert »). Pour aller ouvrir le
       coffre ou le capot, on ouvre sa portière : ce signal-là est ignoré dans les étapes suivantes (avant, il était pris
       pour le coffre et l'écran passait trop tôt à « referme le coffre »). */
    private static final Map<String, Map<String, String>> claims = new HashMap<>();
    private static final Map<String, String> claimBase = new HashMap<>();
    private static Phase phase;
    private static final Object LOCK = new Object();
    private static int samples = 0;
    private static final Map<String, Integer> srcCount = new HashMap<>();

    /* ---------- carte apprise, en direct ---------- */
    private static JSONObject map;                       // { v, at, dev, rules: { fl: {s,k,m,on,base,mask}, … } }
    private static final Set<String> liveKeys = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static String lastDoors = "", lastRev = "", lastLights = "";

    static synchronized void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        HandlerThread t = new HandlerThread("ibiza-learn"); t.start();
        bg = new Handler(t.getLooper());
        try {
            String s = app.getSharedPreferences("ibz_learn", Context.MODE_PRIVATE).getString("map", null);
            if (s != null) apply(new JSONObject(s), false);
        } catch (Exception e) { Hub.log("APPRIS : carte illisible : " + e); }
    }

    /* =================== sources poussées par les autres classes =================== */
    static boolean wants() { return learning || !liveKeys.isEmpty(); }
    /** v1.27 : applis dont une fenêtre sert à la carte apprise (clés « A:paquet#identifiant ») : leurs changements de
        contenu doivent déclencher un balayage, même si rien dans leurs identifiants ne ressemble à « door » */
    private static final Set<String> livePkgs = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    static boolean watches(String pkg) { return !livePkgs.isEmpty() && livePkgs.contains(pkg); }

    static void feed(String key, String value) {
        if (key == null) return;
        if (!learning && !liveKeys.contains(key)) return;
        if (value == null) value = "∅";
        if (value.length() > 120) value = value.substring(0, 120);
        String prev = cur.put(key, value);
        if (value.equals(prev)) return;
        if (learning) record(key, value, prev == null);
        else if (liveKeys.contains(key)) evaluate();
    }

    /** diffusion : chaque champ, et chaque octet des tableaux */
    static void feedBroadcast(String action, Bundle x) {
        if (!wants() || action == null) return;
        if (x == null || x.isEmpty()) { feed("B:" + action, "×" + System.currentTimeMillis() / 1000); return; }
        for (String k : x.keySet()) {
            Object v; try { v = x.get(k); } catch (Exception e) { continue; }
            String base = "B:" + action + "/" + k;
            if (v instanceof byte[]) { byte[] b = (byte[]) v; for (int i = 0; i < b.length && i < 24; i++) feed(base + "[" + i + "]", String.valueOf(b[i] & 0xFF)); }
            else if (v instanceof int[]) { int[] b = (int[]) v; for (int i = 0; i < b.length && i < 24; i++) feed(base + "[" + i + "]", String.valueOf(b[i])); }
            else if (v instanceof Bundle) { Bundle bb = (Bundle) v; for (String kk : bb.keySet()) { Object o; try { o = bb.get(kk); } catch (Exception e) { continue; } feed(base + "." + kk, String.valueOf(o)); } }
            else feed(base, String.valueOf(v));
        }
    }

    /** TopWay : chaque octet de chaque message (what / arg1) */
    static void feedTw(int what, int arg1, Object obj) {
        if (!wants()) return;
        String base = String.format(Locale.US, "T:%04X/%d", what & 0xFFFF, arg1);
        if (obj instanceof byte[]) { byte[] b = (byte[]) obj; for (int i = 0; i < b.length && i < 16; i++) feed(base + "[" + i + "]", String.valueOf(b[i] & 0xFF)); }
        else feed(base, "×");
    }

    /** accessibilité : identifiants visibles de chaque appli (absents = « 0 ») */
    private static final Set<String> aKeys = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    static void feedWindows(Map<String, List<String>> idsByPkg) {
        if (!wants()) return;
        Set<String> now = new HashSet<>();
        for (Map.Entry<String, List<String>> e : idsByPkg.entrySet()) for (String id : e.getValue()) if (now.size() < 600) now.add("A:" + e.getKey() + "#" + id);
        for (String k : now) { if (learning || liveKeys.contains(k)) aKeys.add(k); feed(k, "1"); }
        for (String k : new ArrayList<>(aKeys)) if (!now.contains(k)) feed(k, "0");
    }

    /* =================== sources relevées ici =================== */
    private static final Runnable poll = new Runnable() { public void run() {
        if (learning) {
            try { pollProps(); } catch (Throwable ignored) {}
            try { pollSettings(); } catch (Throwable ignored) {}
            try { pollFiles(); } catch (Throwable ignored) {}
            samples++;
            bg.postDelayed(this, 350);
        } else if (!liveKeys.isEmpty()) {
            try { pollLive(); } catch (Throwable ignored) {}
            bg.postDelayed(this, 300);
        }
    }};

    /* v1.26.2 : fichiers du noyau (sysfs). Sur les postes où le microcontrôleur gère la caméra de recul (Junsun hcn),
       Android ne voit la marche arrière ni dans les propriétés ni dans les réglages ; le fil « marche arrière » (et
       parfois « éclairage », « frein à main ») peut en revanche apparaître dans un petit fichier du noyau, par exemple
       /sys/class/switch/…/state. On repère au début de l'apprentissage les fichiers lisibles dont le nom y fait penser,
       puis on les relit comme les propriétés (source « F »). Beaucoup sont fermés aux applis : le journal dit combien. */
    private static final String[] FROOTS = { "/sys/class/switch", "/sys/devices/virtual/switch", "/sys/class/gpio", "/sys/class/misc",
        "/sys/devices/virtual/misc", "/sys/devices/platform", "/sys/class/extcon", "/sys/kernel", "/proc/driver", "/sys/module" };
    private static final Pattern FNAME = Pattern.compile("revers|back_?car|carback|bk_?car|backlight_?det|camera|cvbs|avin|gear|(^|[/_])ill|lamp|light_?det|acc_?(det|state|status)|park|brake|hand_?brake|mcu|canbus", Pattern.CASE_INSENSITIVE);
    private static final List<String> files = new ArrayList<>();
    private static void findFiles() {
        files.clear(); int seen = 0;
        java.util.ArrayDeque<Object[]> q = new java.util.ArrayDeque<>();
        for (String r : FROOTS) q.add(new Object[]{ new File(r), 0 });
        Set<String> canon = new HashSet<>();
        while (!q.isEmpty() && seen < 6000 && files.size() < 160) {
            Object[] it = q.poll(); File f = (File) it[0]; int depth = (Integer) it[1];
            seen++;
            String path = f.getPath();
            try { String cp = f.getCanonicalPath(); if (!canon.add(cp)) continue; } catch (Exception e) { continue; }
            if (f.isDirectory()) {
                if (depth >= 5) continue;
                File[] l = f.listFiles(); if (l == null) continue;
                for (File c : l) { String n = c.getName(); if (n.equals("subsystem") || n.equals("power") || n.equals("driver") || n.equals("firmware_node") || n.startsWith("uevent")) continue; q.add(new Object[]{ c, depth + 1 }); }
            } else if (FNAME.matcher(path).find() || path.startsWith("/sys/class/switch") || path.startsWith("/sys/devices/virtual/switch") || path.startsWith("/sys/class/extcon")) {
                String n = f.getName();
                if (!(n.equals("state") || n.equals("status") || n.equals("value") || n.equals("name") || FNAME.matcher(n).find())) continue;
                if (f.length() > 4096 || !f.canRead()) continue;
                if (readFile(path) != null) files.add(path);
            }
        }
        Hub.log("APPRENTISSAGE : " + files.size() + " fichiers système lisibles retenus (" + seen + " parcourus)" + (files.isEmpty() ? "" : " : " + files.subList(0, Math.min(12, files.size()))));
    }
    static String readFile(String path) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(path); byte[] b = new byte[64]; int n = in.read(b);
            if (n < 0) return "";
            String s = new String(b, 0, n, "UTF-8").trim();
            return s.length() > 40 ? s.substring(0, 40) : s;
        } catch (Throwable t) { return null; } finally { try { if (in != null) in.close(); } catch (Exception ignored) {} }
    }
    private static void pollFiles() {
        if (files.isEmpty()) return;
        for (String f : files) { String v = readFile(f); if (v != null) feed("F:" + f, v); }
    }

    private static void pollProps() throws Exception {
        Process pr = Runtime.getRuntime().exec("getprop");
        BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
        String line;
        while ((line = r.readLine()) != null) { Matcher m = PROP_LINE.matcher(line); if (m.find()) feed("P:" + m.group(1), m.group(2)); }
        r.close(); pr.destroy();
    }
    private static final Uri[] TABLES = { Settings.System.CONTENT_URI, Settings.Global.CONTENT_URI, Settings.Secure.CONTENT_URI };
    private static void pollSettings() {
        ContentResolver cr = app.getContentResolver();
        for (Uri u : TABLES) {
            Cursor c = null;
            try {
                c = cr.query(u, new String[]{"name", "value"}, null, null, null);
                if (c == null) continue;
                String t = u.getLastPathSegment();
                while (c.moveToNext()) feed("S:" + t + "/" + c.getString(0), c.getString(1));
            } catch (Throwable ignored) {} finally { if (c != null) c.close(); }
        }
    }
    private static java.lang.reflect.Method spGet; private static int spOk = 0;   // lecture directe des propriétés (sinon getprop)
    private static String prop(String k) {
        if (spOk >= 0) {
            try { if (spGet == null) spGet = Class.forName("android.os.SystemProperties").getMethod("get", String.class); String v = (String) spGet.invoke(null, k); spOk = 1; return v; }
            catch (Throwable t) { spOk = -1; }
        }
        return null;
    }
    private static Map<String, String> propsExec(List<String> keys) {
        Map<String, String> m = new HashMap<>(); Process pr = null;
        try {
            StringBuilder cmd = new StringBuilder(); for (String k : keys) cmd.append("getprop ").append(k).append(';');
            pr = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd.toString()});
            BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
            for (String k : keys) { String line = r.readLine(); if (line == null) break; m.put(k, line.trim()); }
            r.close();
        } catch (Exception ignored) {} finally { if (pr != null) pr.destroy(); }
        return m;
    }
    private static void pollLive() {
        boolean changed = false;
        List<String> viaExec = new ArrayList<>();
        for (String k : liveKeys) {
            String v = null;
            if (k.startsWith("P:")) { v = prop(k.substring(2)); if (v == null && spOk < 0) { viaExec.add(k.substring(2)); continue; } }
            else if (k.startsWith("F:")) v = readFile(k.substring(2));
            else if (k.startsWith("S:")) {
                int s = k.indexOf('/'); String t = k.substring(2, s), n = k.substring(s + 1);
                try {
                    ContentResolver cr = app.getContentResolver();
                    v = t.equals("system") ? Settings.System.getString(cr, n) : t.equals("global") ? Settings.Global.getString(cr, n) : Settings.Secure.getString(cr, n);
                } catch (Throwable ignored) {}
            } else continue;
            if (v == null) v = "∅";
            String prev = cur.put(k, v);
            if (!v.equals(prev)) changed = true;
        }
        if (!viaExec.isEmpty()) for (Map.Entry<String, String> e : propsExec(viaExec).entrySet()) { String prev = cur.put("P:" + e.getKey(), e.getValue()); if (!e.getValue().equals(prev)) changed = true; }
        if (changed) evaluate();
    }

    /* =================== apprentissage guidé =================== */
    static synchronized String start() {
        if (app == null) return "{\"error\":\"pas prêt\"}";
        synchronized (LOCK) { phases.clear(); phase = null; noisy.clear(); srcCount.clear(); samples = 0; claims.clear(); claimBase.clear(); }
        aKeys.clear();
        learning = true;
        Hub.log("APPRENTISSAGE : démarré (propriétés, réglages, diffusions" + (Ts10Probe.present() ? ", trames TopWay" : "") + (CanWatchService.self != null ? ", fenêtres" : "") + ")");
        if (Ts10Probe.present()) Ts10Probe.startLive();
        bg.removeCallbacks(poll);
        bg.post(new Runnable() { public void run() { try { findFiles(); } catch (Throwable t) { Hub.log("APPRENTISSAGE : fichiers système : " + t); } } });
        bg.post(poll);
        JSONObject o = new JSONObject();
        try { o.put("ok", true); o.put("access", CanWatchService.self != null); o.put("tw", Ts10Probe.present()); } catch (Exception ignored) {}
        return o.toString();
    }

    /** nouvelle phase : ev = "base" ou un des EVENTS ; on = vrai pour « ouvert / allumé » */
    static void step(String ev, boolean on) {
        synchronized (LOCK) {
            Phase prev = phase;
            closePhase();
            if (prev != null && !prev.on && !prev.ev.equals("base")) claim(prev.ev, prev);
            phase = new Phase(ev, on, new HashMap<>(cur));
            phases.add(phase);
        }
        Hub.log("APPRENTISSAGE : étape " + ev + (ev.equals("base") ? "" : on ? " (ouvert)" : " (fermé)"));
    }
    /** fin d'une étape : son meilleur signal est réservé (ignoré pour les éléments suivants) */
    private static void claim(String ev, Phase off) {
        Phase on = lastOn(ev); if (on == null) return;
        List<Cand> cs = candidates(on, off, ev);
        if (cs.isEmpty()) { Hub.log("APPRENTISSAGE : " + ev + " : rien trouvé"); return; }
        for (Cand c : cs) {     // tous les signaux qui ont suivi cet élément (ex. propriété + trame + fenêtre)
            Map<String, String> m = claims.get(c.k); if (m == null) claims.put(c.k, m = new HashMap<>());
            if (!m.containsKey(ev)) m.put(ev, c.on);
            if (!claimBase.containsKey(c.k)) claimBase.put(c.k, c.base);
        }
        Cand c = cs.get(0);
        Hub.log("APPRENTISSAGE : " + ev + " = " + label(c.k) + " (" + c.base + " → " + c.on + ")" + (cs.size() > 1 ? " + " + (cs.size() - 1) + " autre(s)" : ""));
    }
    /** valeur de repos d'une clé déjà attribuée à un autre élément (celle relevée à son étape), sinon base */
    private static String rest(String k, String ev, String base) {
        Map<String, String> m = claims.get(k); if (m == null) return base;
        for (String o : m.keySet()) if (!o.equals(ev)) { String r = claimBase.get(k); return r != null ? r : base; }
        return base;
    }
    /** bits déjà pris par d'autres éléments sur cette clé (valeurs numériques), sinon 0 */
    private static long otherBits(String k, String ev, Long nb) {
        Map<String, String> m = claims.get(k); if (m == null || nb == null) return 0;
        long o = 0;
        for (Map.Entry<String, String> e : m.entrySet()) { if (e.getKey().equals(ev)) continue; Long v = num(e.getValue()); if (v != null) o |= (v ^ nb); }
        return o;
    }
    /** première valeur « ouvert » propre à cet élément : ni la valeur de repos, ni une valeur réservée par un autre
        élément ; sur un octet partagé (bits), les bits des autres éléments sont retirés */
    private static String effOn(String k, List<String> seen, String base, String ev) {
        Map<String, String> m = claims.get(k);
        String r = rest(k, ev, base);
        Long nb = num(r); long ob = otherBits(k, ev, nb);
        for (String s : seen) {
            if (base != null && s.equals(base)) continue;
            if (r != null && s.equals(r)) continue;                 // l'autre élément revient au repos (portière refermée)
            if (m != null) {
                boolean taken = false;
                for (Map.Entry<String, String> e : m.entrySet()) if (!e.getKey().equals(ev) && e.getValue().equals(s)) taken = true;
                if (taken) continue;
                Long ns = num(s);
                if (ob != 0 && ns != null && nb != null) {
                    long d = (ns ^ nb) & ~ob;
                    if (d == 0) continue;
                    s = String.valueOf(nb ^ d);
                }
            }
            return s;
        }
        return null;
    }
    /** la valeur est-elle revenue au repos (en ignorant les bits des autres éléments) ? */
    private static boolean isBack(String k, String base, String now, String ev) {
        base = rest(k, ev, base);
        if (base == null || now == null) return false;
        if (base.equals(now) || k.startsWith("A:") && "0".equals(now)) return true;
        Long nb = num(base), nn = num(now); long ob = otherBits(k, ev, nb);
        return ob != 0 && nb != null && nn != null && ((nn ^ nb) & ~ob) == 0;
    }
    private static boolean claimedValue(String k, String v, String ev) {
        Map<String, String> m = claims.get(k); if (m == null) return false;
        for (Map.Entry<String, String> e : m.entrySet()) if (!e.getKey().equals(ev) && e.getValue().equals(v)) return true;
        return false;
    }

    private static void closePhase() {
        if (phase == null) return;
        phase.end = new HashMap<>(cur); phase.t1 = System.currentTimeMillis();
        if (phase.ev.equals("base")) noisy.addAll(phase.seen.keySet());
        phase = null;
    }

    private static void record(String key, String value, boolean firstSeen) {
        synchronized (LOCK) {
            String src = key.substring(0, 1);
            srcCount.put(src, (srcCount.containsKey(src) ? srcCount.get(src) : 0) + 1);
            if (phase == null || firstSeen && phase.ev.equals("base") && System.currentTimeMillis() - phase.t0 < 1200) return;   // premier relevé complet
            List<String> l = phase.seen.get(key);
            if (l == null) { l = new ArrayList<>(); phase.seen.put(key, l); phase.first.put(key, System.currentTimeMillis()); }
            if (l.size() < 40 && (l.isEmpty() || !l.get(l.size() - 1).equals(value))) l.add(value);
        }
    }

    /** état de la phase en cours pour l'écran : signaux qui ont bougé (hors bruit) */
    static String status() {
        JSONObject o = new JSONObject();
        try {
            synchronized (LOCK) {
                o.put("learning", learning); o.put("samples", samples);
                if (phase != null) {
                    long now = System.currentTimeMillis();
                    o.put("ev", phase.ev); o.put("on", phase.on); o.put("ms", now - phase.t0);
                    JSONArray ch = new JSONArray(); long lastMove = 0; int reverted = 0, total = 0;
                    Phase onPh = phase.on ? null : lastOn(phase.ev);
                    for (Map.Entry<String, List<String>> e : phase.seen.entrySet()) {
                        String k = e.getKey();
                        if (noisy.contains(k) || NOISE_KEY.matcher(k).find()) continue;
                        if (phase.ev.equals("base")) continue;
                        String st = phase.start.get(k), now1 = cur.get(k);
                        String eff = effOn(k, e.getValue(), st, phase.ev);
                        if (eff == null) continue;                                       // portière déjà apprise, etc.
                        if (phase.on && isBack(k, st, now1, phase.ev) && !k.startsWith("A:") && !k.startsWith("B:")) continue;   // déjà revenu
                        if (phase.on && now1 != null && claimedValue(k, now1, phase.ev)) continue;
                        if (ch.length() < 6) ch.put(label(k) + " : " + (st == null ? "∅" : st) + " → " + eff);
                        lastMove = Math.max(lastMove, phase.first.get(k));
                    }
                    if (onPh != null) {   // phase « fermé » : les signaux de la phase « ouvert » sont-ils revenus ?
                        for (Map.Entry<String, List<String>> e : onPh.seen.entrySet()) {
                            String k = e.getKey();
                            if (noisy.contains(k) || NOISE_KEY.matcher(k).find()) continue;
                            String st = onPh.start.get(k);
                            if (effOn(k, e.getValue(), st, phase.ev) == null) continue;
                            total++;
                            if (isBack(k, st, cur.get(k), phase.ev)) reverted++;
                        }
                    }
                    o.put("changes", ch); o.put("count", ch.length());
                    o.put("quiet", lastMove == 0 ? 0 : now - lastMove);
                    o.put("reverted", reverted); o.put("total", total);
                }
            }
        } catch (Exception ignored) {}
        return o.toString();
    }
    private static Phase lastOn(String ev) { for (int i = phases.size() - 1; i >= 0; i--) { Phase p = phases.get(i); if (p.ev.equals(ev) && p.on) return p; } return null; }

    /* =================== analyse =================== */
    private static final class Cand { String k, base, on; long lat; double score; int nOther, nvals; }

    static synchronized String finish() {
        JSONObject res = new JSONObject();
        try {
            synchronized (LOCK) { closePhase(); }
            learning = false;
            JSONObject rules = new JSONObject(), labels = new JSONObject(), alts = new JSONObject();
            Map<String, Cand> best = new LinkedHashMap<>();
            Map<String, List<Cand>> all = new HashMap<>();
            for (String ev : EVENTS) {
                Phase on = null, off = null;
                for (Phase p : phases) if (p.ev.equals(ev)) { if (p.on) on = p; else if (on != null) off = p; }
                if (on == null) continue;
                List<Cand> cs = candidates(on, off, ev);
                all.put(ev, cs);
                if (!cs.isEmpty()) best.put(ev, cs.get(0));
            }
            // spécificité : un même couple clé / valeur pour plusieurs ouvrants = « une porte ouverte », pas un ouvrant précis
            for (Map.Entry<String, Cand> e : best.entrySet()) {
                List<Cand> cs = all.get(e.getKey());
                for (Cand c : cs) {
                    c.nOther = 0;
                    for (Map.Entry<String, List<Cand>> o : all.entrySet()) {
                        if (o.getKey().equals(e.getKey())) continue;
                        for (Cand d : o.getValue()) if (d.k.equals(c.k) && d.on.equals(c.on)) c.nOther++;
                    }
                    if (c.nOther > 0) c.score -= 4;
                }
                Collections.sort(cs, (a, b) -> Double.compare(b.score, a.score));
                e.setValue(cs.get(0));
            }
            // même clé + même valeur pour deux éléments : on garde celui à qui elle a été réservée (ex. portière / coffre)
            for (String ev : new ArrayList<>(best.keySet())) {
                Cand c = best.get(ev); if (c == null) continue;
                for (String o : new ArrayList<>(best.keySet())) {
                    if (o.equals(ev) || !best.containsKey(o)) continue;
                    Cand d = best.get(o);
                    if (d.k.equals(c.k) && d.on.equals(c.on)) {
                        Map<String, String> m = claims.get(c.k);
                        String owner = m != null && c.on.equals(m.get(ev)) ? ev : m != null && c.on.equals(m.get(o)) ? o : ev;
                        best.remove(owner.equals(ev) ? o : ev);
                        Hub.log("APPRENTISSAGE : " + (owner.equals(ev) ? o : ev) + " écarté (même signal que " + owner + ")");
                        if (!owner.equals(ev)) break;
                    }
                }
            }
            // même clé pour plusieurs ouvrants avec des bits différents -> masque
            Map<String, List<String>> byKey = new HashMap<>();
            for (Map.Entry<String, Cand> e : best.entrySet()) { List<String> l = byKey.get(e.getValue().k); if (l == null) byKey.put(e.getValue().k, l = new ArrayList<>()); l.add(e.getKey()); }
            for (Map.Entry<String, Cand> e : best.entrySet()) {
                Cand c = e.getValue(); JSONObject r = new JSONObject();
                r.put("s", c.k.substring(0, 1)); r.put("k", c.k); r.put("base", c.base); r.put("on", c.on);
                Long b = num(c.base), v = num(c.on);
                boolean mask = false;
                if (b != null && v != null) {
                    long m = b ^ v;
                    boolean single = Long.bitCount(m) == 1;
                    boolean disjoint = true;
                    for (String other : byKey.get(c.k)) {
                        if (other.equals(e.getKey())) continue;
                        Cand o = best.get(other); Long ob = num(o.base), ov = num(o.on);
                        if (ob == null || ov == null || ((ob ^ ov) & m) != 0) disjoint = false;
                    }
                    if (m != 0 && (single || byKey.get(c.k).size() > 1 && disjoint)) { mask = true; r.put("m", "mask"); r.put("mask", m); r.put("val", v & m); }
                }
                if (!mask && b != null && v != null && c.k.toLowerCase(Locale.US).contains("brightness")) r.put("m", "near");   // v1.26.1 : luminosité jour / nuit -> la plus proche des deux
                else if (!mask && c.nvals >= 3 && b != null && v != null) r.put("m", "ne");   // valeur qui varie (distance radar…) : « différent du repos »
                else if (!mask) r.put("m", "eq");
                rules.put(e.getKey(), r);
                labels.put(e.getKey(), label(c.k) + " (" + c.base + " → " + c.on + ")");
                JSONArray a = new JSONArray(); List<Cand> cs = all.get(e.getKey());
                for (int i = 1; i < cs.size() && i < 4; i++) a.put(label(cs.get(i).k) + " (" + cs.get(i).base + " → " + cs.get(i).on + ")");
                alts.put(e.getKey(), a);
            }
            JSONObject m = new JSONObject();
            m.put("v", 1); m.put("at", System.currentTimeMillis()); m.put("dev", Build.MANUFACTURER + " " + Build.MODEL); m.put("rules", rules);
            res.put("map", m); res.put("labels", labels); res.put("alts", alts);
            JSONArray tried = new JSONArray(); for (Phase p : phases) if (p.on && !p.ev.equals("base")) tried.put(p.ev);
            res.put("tried", tried);
            JSONObject sc = new JSONObject(); for (Map.Entry<String, Integer> e : srcCount.entrySet()) sc.put(e.getKey(), e.getValue()); res.put("sources", sc);
            res.put("noise", noisy.size());
            pending = res;
            Hub.log("APPRENTISSAGE : terminé. " + labels + " ; bruit écarté : " + noisy.size() + " signaux ; relevés : " + sc);
        } catch (Exception e) {
            try { res.put("error", String.valueOf(e)); } catch (Exception ignored) {}
            Hub.log("APPRENTISSAGE : analyse impossible : " + e);
        }
        bg.removeCallbacks(poll);
        if (!liveKeys.isEmpty()) bg.post(poll);
        return res.toString();
    }
    private static JSONObject pending;

    private static List<Cand> candidates(Phase on, Phase off, String ev) {
        List<Cand> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : on.seen.entrySet()) {
            String k = e.getKey();
            if (noisy.contains(k) || NOISE_KEY.matcher(k).find()) continue;
            String base = rest(k, ev, on.start.get(k));                 // repos réel (une portière peut être restée ouverte)
            String v = effOn(k, e.getValue(), on.start.get(k), ev);       // première valeur propre à cet élément
            if (v == null) continue;
            if (k.startsWith("A:") && !"1".equals(v)) continue;
            String endOff = off == null ? null : off.end != null ? off.end.get(k) : cur.get(k);
            boolean back;
            if (base != null) back = isBack(k, base, endOff, ev);
            else back = endOff != null && !endOff.equals(v);
            if (off != null && !back) continue;
            if (base == null) base = endOff == null ? "∅" : endOff;
            if (base.equals(v)) continue;
            Cand c = new Cand(); c.k = k; c.base = base; c.on = v; c.lat = on.first.get(k) - on.t0;
            Set<String> dv = new HashSet<>(e.getValue()); dv.remove(base); c.nvals = dv.size();
            char s = k.charAt(0);
            c.score = (s == 'T' ? 6 : s == 'P' ? 5 : s == 'S' ? 5 : s == 'F' ? 5 : s == 'B' ? 4 : 2) + (off != null ? 2 : 0) - Math.min(3, c.lat / 3000.0)
                    - (e.getValue().size() > 3 ? 2 : 0) + (k.toLowerCase(Locale.US).matches(".*(door|porte|trunk|boot|hood|reverse|back|gear|light|lamp|ill).*") ? 1 : 0);
            out.add(c);
        }
        Collections.sort(out, (a, b) -> Double.compare(b.score, a.score));
        return out;
    }
    private static Long num(String s) {
        if (s == null) return null;
        try { s = s.trim(); if (s.startsWith("0x") || s.startsWith("0X")) return Long.parseLong(s.substring(2), 16); return Long.parseLong(s); } catch (Exception e) { return null; }
    }
    static String label(String k) {
        if (k == null || k.length() < 2) return String.valueOf(k);
        String r = k.substring(2);
        switch (k.charAt(0)) {
            case 'P': return "propriété " + r;
            case 'S': return "réglage " + r;
            case 'B': return "diffusion " + r;
            case 'T': return "trame TopWay " + r;
            case 'F': return "fichier " + r;
            case 'A': return "fenêtre " + r;
            default: return k;
        }
    }

    /* =================== enregistrer / appliquer =================== */
    static synchronized String save() {
        if (pending == null) return "rien à enregistrer";
        try {
            JSONObject m = pending.getJSONObject("map");
            app.getSharedPreferences("ibz_learn", Context.MODE_PRIVATE).edit().putString("map", m.toString()).apply();
            try { File f = new File(app.getExternalFilesDir(null), "learn.json"); FileOutputStream o = new FileOutputStream(f); o.write(pending.toString(1).getBytes("UTF-8")); o.close(); } catch (Exception ignored) {}
            apply(m, true);
            return "ok";
        } catch (Exception e) { return String.valueOf(e); }
    }
    static synchronized void clear() {
        app.getSharedPreferences("ibz_learn", Context.MODE_PRIVATE).edit().remove("map").apply();
        map = null; liveKeys.clear(); livePkgs.clear(); doorsActive = false; reverseActive = false; radarRearStart = -1;
        Hub.log("APPRIS : carte effacée, retour à la détection automatique");
    }
    private static void apply(JSONObject m, boolean log) {
        map = m; liveKeys.clear(); livePkgs.clear();
        JSONObject r = m.optJSONObject("rules");
        boolean d = false, rev = false;
        radarRearStart = -1;
        if (r != null) for (Iterator<String> it = r.keys(); it.hasNext(); ) {
            String ev = it.next(); JSONObject x = r.optJSONObject(ev); if (x == null) continue;
            // v1.26 : marche arrière apprise sur un radar hcn = indique seulement quels capteurs sont à l'arrière ;
            // la marche arrière elle-même est déduite de tous les radars arrière (CanBridge), pas d'un seul capteur
            java.util.regex.Matcher rm = java.util.regex.Pattern.compile("^P:persist\\.vendor\\.cem\\.radar(\\d)$").matcher(x.optString("k"));
            if (ev.equals("reverse") && rm.find()) { radarRearStart = Integer.parseInt(rm.group(1)) >= 4 ? 4 : 0; continue; }
            String lk = x.optString("k");
            liveKeys.add(lk);
            if (lk.startsWith("A:") && lk.indexOf('#') > 2) livePkgs.add(lk.substring(2, lk.indexOf('#')));
            if (ev.equals("reverse")) rev = true; else if (!ev.equals("lights")) d = true;
        }
        doorsActive = d; reverseActive = rev;
        lastDoors = ""; lastRev = ""; lastLights = "";
        if (log) Hub.log("APPRIS : carte enregistrée, " + liveKeys.size() + " signaux suivis en direct");
        else if (!liveKeys.isEmpty()) Hub.log("APPRIS : carte chargée (" + r + ")");
        if (bg != null && !liveKeys.isEmpty()) { bg.removeCallbacks(poll); bg.post(poll); }
    }
    static boolean hasMap() { return map != null && map.optJSONObject("rules") != null && map.optJSONObject("rules").length() > 0; }
    static String mapJson() { return map == null ? "null" : map.toString(); }

    private static boolean match(JSONObject r) {
        String v = cur.get(r.optString("k"));
        if (v == null) return false;
        if ("ne".equals(r.optString("m"))) return !v.equals(r.optString("base"));
        if ("near".equals(r.optString("m"))) {   // ex. luminosité 162 le jour, 55 phares allumés : 60 ou 70 restent « nuit »
            Long n = num(v), on = num(r.optString("on")), base = num(r.optString("base"));
            if (n == null || on == null || base == null) return v.equals(r.optString("on"));
            return Math.abs(n - on) < Math.abs(n - base);
        }
        if ("mask".equals(r.optString("m"))) {
            Long n = num(v); if (n == null) return false;
            long mk = r.optLong("mask"); return (n & mk) == r.optLong("val");
        }
        return v.equals(r.optString("on"));
    }
    private static synchronized void evaluate() {     // v1.27 : appelé depuis trois fils (fenêtres, trames, relevé) : un seul à la fois
        if (map == null || learning) return;
        JSONObject r = map.optJSONObject("rules"); if (r == null) return;
        if (System.currentTimeMillis() < CanBridge.simUntil) return;
        try {
            if (doorsActive) {
                boolean[] d = new boolean[6]; String[] k = { "fl", "fr", "rl", "rr", "trunk", "hood" };
                StringBuilder sig = new StringBuilder();
                for (int i = 0; i < 6; i++) { JSONObject x = r.optJSONObject(k[i]); d[i] = x != null && match(x); sig.append(d[i] ? '1' : '0'); }
                if (!sig.toString().equals(lastDoors)) { lastDoors = sig.toString(); Hub.setDoors("carte apprise", d); }
            }
            JSONObject rv = r.optJSONObject("reverse");
            if (rv != null && radarRearStart < 0) { String s = match(rv) ? "1" : "0"; if (!s.equals(lastRev)) { lastRev = s; Hub.setReverse("carte apprise", s.equals("1")); } }
            JSONObject li = r.optJSONObject("lights");
            if (li != null) {
                String s = match(li) ? "1" : "0";
                if (!s.equals(lastLights)) { lastLights = s; Hub.lightsJson = "{\"on\":" + s.equals("1") + ",\"key\":\"appris\"}"; Hub.emit("lights", Hub.lightsJson); }
            }
        } catch (Exception ignored) {}
    }

    /** résumé lisible (QR code, journal) */
    static String summary() {
        StringBuilder b = new StringBuilder("=== Apprentissage du véhicule ===\n");
        b.append("Poste : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        JSONObject src = pending != null ? pending : null;
        if (src != null) {
            b.append("Dernier apprentissage :\n");
            JSONObject l = src.optJSONObject("labels"), a = src.optJSONObject("alts");
            JSONArray tried = src.optJSONArray("tried");
            for (String ev : EVENTS) {
                boolean t = false; if (tried != null) for (int i = 0; i < tried.length(); i++) if (ev.equals(tried.optString(i))) t = true;
                if (!t) continue;
                b.append("  ").append(ev).append(" : ").append(l != null && l.has(ev) ? l.optString(ev) : "non trouvé").append('\n');
                JSONArray x = a == null ? null : a.optJSONArray(ev);
                if (x != null) for (int i = 0; i < x.length(); i++) b.append("      autre : ").append(x.optString(i)).append('\n');
            }
            b.append("Relevés par source : ").append(src.optJSONObject("sources")).append(" · bruit écarté : ").append(src.optInt("noise")).append('\n');
        }
        b.append("Carte en service : ").append(mapJson()).append('\n');
        return b.append("=== fin ===\n").toString();
    }
}
