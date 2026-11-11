package fr.ibiza.launcher;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * v1.22.1 : sondage du CAN sur les autoradios TopWay (TS10 / TS18, firmware « tw », appli système com.tw.service).
 *
 * Sur ces postes, il n'y a ni CarCan ni propriétés persist.vendor.cem.* : les trames du boîtier CAN arrivent du
 * microcontrôleur par la classe système android.tw.john.TWUtil (message 0x0501 = trame CAN : arg1 = commande du
 * protocole du boîtier, obj = octets). com.tw.service et com.tw.service.xt l'ouvrent chacun de leur côté, avec les mêmes
 * numéros : plusieurs lecteurs sont donc prévus.
 *
 * Ce sondage est un outil de diagnostic, lancé à la main depuis les réglages, pour une durée limitée :
 *  - lecture seule (aucune écriture vers le microcontrôleur) ;
 *  - chaque message n'est noté dans le journal que quand son contenu change, avec un plafond, pour ne pas le noyer ;
 *  - un résumé (nombre de messages par type) est noté à la fin.
 * Les trames notées servent ensuite à décoder portes, coffre, vitesse, température, carburant… pour le boîtier du client.
 * Sur un poste qui n'est pas un TopWay, la classe n'existe pas : rien n'est fait.
 * v1.24.2 : TWUtil reste ouvert en permanence (lecture seule) pour lire les portes en direct ; le sondage ne fait plus
 * qu'activer la prise de notes dans le journal pendant sa durée.
 */
final class Ts10Probe {
    private Ts10Probe() {}

    private static final String CLS = "android.tw.john.TWUtil";
    private static final String NAME = "CarLauncher";
    // numéros ouverts par com.tw.service (sauf touches et volume) : version MCU, état système, CAN, marche arrière…
    private static final short[] IDS = { 266, 267, 769, 770, 773, 774, 1028, 1281, 1285, 1287, 1288, 1289, 1294,
            (short) 0x9E00, (short) 0x9E11, (short) 0x9F10, (short) 0x9F1B, (short) 0x9F1C };
    private static final int MAX_LINES = 350, MAX_PER_KEY = 25;

    private static Boolean present;
    private static volatile boolean running;
    private static Object tw;
    private static Class<?> cls;
    private static HandlerThread thread;
    private static final Map<String, String> last = new HashMap<>();
    private static final Map<String, Integer> count = new TreeMap<>(), logged = new HashMap<>();
    private static int lines;
    private static final Object LOCK = new Object();   // compteurs (le fil de TWUtil n'attend jamais le verrou de la classe)

    /** vrai sur un TopWay (classe TWUtil présente dans le système) */
    static synchronized boolean present() {
        if (present == null) {
            try { Class.forName(CLS); present = true; } catch (Throwable t) { present = false; }
        }
        return present;
    }

    static boolean running() { return running; }

    /** noté une fois au démarrage du lanceur, pour savoir tout de suite sur quel type de poste on est */
    private static boolean platformLogged;
    static synchronized void logPlatform() {
        if (platformLogged) return;
        platformLogged = true;
        if (present()) Hub.log("Plateforme : TopWay (TWUtil présent). Portes lues sur le CAN du poste (TWUtil), secours : fenêtre portes "
                + "de com.tw.service ; sondage du CAN possible dans Réglages > Données CAN du poste");
    }

    /* ======== v1.24.2 : lecture permanente des portes (lecture seule) ========
       Relevé sur un TS10 (Unisoc UIS8581) + boîtier Raise, profil « Ford 09-12 Fiesta / highconfig » :
       à chaque ouvrant, le microcontrôleur envoie 0x0505 (arg1 = 0) puis 0x0501 (arg1 = 0x24) ; 1er octet :
         0x40 avant gauche, 0x80 avant droite, 0x10 arrière gauche, 0x20 arrière droite (vérifiés avec la fenêtre
         portes de com.tw.service), 0x08 coffre et 0x04 capot (supposés : bits restants, à confirmer), 0x00 tout fermé.
       Avant (1.22.1 à 1.24.1), les portes n'étaient lues que dans la fenêtre portes de com.tw.service (accessibilité) :
       elle reste la solution de secours tant qu'aucune trame portes n'est reçue.
       Bits modifiables dans ibiza_map.json : "ts10DoorBits": [64,128,16,32,8,4] (fl fr rl rr coffre capot) ;
       "ts10Doors": false désactive cette lecture. */
    static volatile boolean liveDoors = false;          // vrai dès qu'une trame portes est reçue : source de référence
    private static final int[] BITS_DEF = { 0x40, 0x80, 0x10, 0x20, 0x08, 0x04 };
    private static int[] bits = BITS_DEF;
    private static volatile boolean[] lastDoors;
    private static volatile boolean probing;
    private static volatile Handler handler;
    private static final String[] DK = { "avant gauche", "avant droite", "arrière gauche", "arrière droite", "coffre", "capot" };

    /** ouvre TWUtil une fois pour toutes (appelé au démarrage du lanceur et de l'accessibilité) */
    static synchronized void startLive() {
        if (!present() || tw != null) return;
        try {
            org.json.JSONObject u = CanBridge.userMap();
            if (u != null && !u.optBoolean("ts10Doors", true)) { Hub.log("TS10 : lecture des portes désactivée (ibiza_map.json)"); return; }
            org.json.JSONArray a = u == null ? null : u.optJSONArray("ts10DoorBits");
            if (a != null && a.length() == 6) { int[] b = new int[6]; for (int i = 0; i < 6; i++) b[i] = a.getInt(i); bits = b; Hub.log("TS10 : bits des portes perso " + a); }
        } catch (Throwable ignored) {}
        if (open() == null) Hub.log("TS10 : lecture des portes en direct sur le CAN du poste (lecture seule)");
    }

    /** ouvre TWUtil et branche le lecteur ; renvoie null si tout va bien, sinon un message court */
    private static String open() {
        try {
            cls = Class.forName(CLS);
            Object o;
            try { o = cls.getConstructor().newInstance(); }
            catch (Throwable e) { o = cls.getConstructor(int.class).newInstance(16); }
            Object r = cls.getMethod("open", short[].class).invoke(o, (Object) IDS);
            if (r instanceof Integer && (Integer) r != 0) {
                Hub.log("TS10 : ouverture de TWUtil refusée (code " + r + ")");
                return "Ouverture refusée par le poste (code " + r + ")";
            }
            tw = o;
            cls.getMethod("start").invoke(o);
            thread = new HandlerThread("ibiza-ts10"); thread.start();
            Handler h = new Handler(thread.getLooper()) {
                @Override public void handleMessage(Message m) { onMessage(m); }
            };
            handler = h;
            cls.getMethod("addHandler", String.class, Handler.class).invoke(o, NAME, h);
            h.postDelayed(alertTick, 500);
            return null;
        } catch (Throwable e) {
            Throwable t = e instanceof InvocationTargetException ? ((InvocationTargetException) e).getTargetException() : e;
            Hub.log("TS10 : TWUtil inaccessible pour le lanceur : " + t);
            release();
            return "TWUtil inaccessible (" + t.getClass().getSimpleName() + ")";
        }
    }

    /** alerte « ouvrant ouvert en roulant » : elle attend un appel toutes les 0,5 s, comme sur les postes hcn */
    private static final Runnable alertTick = new Runnable() { public void run() {
        try {
            boolean[] d = lastDoors;
            if (liveDoors && d != null) {
                int kmh = CanBridge.canSpeed && CanBridge.canKmh >= 0 ? CanBridge.canKmh
                        : (System.currentTimeMillis() - CanBridge.gpsAt < 5000 ? CanBridge.gpsKmh : 0);
                MovingAlert.check(d, kmh);
            }
        } catch (Throwable ignored) {}
        Handler h = handler; if (h != null) h.postDelayed(this, 500);
    }};

    /** lance le sondage pour « seconds » secondes ; renvoie un message court pour l'interface */
    static synchronized String start(int seconds) {
        if (!present()) return "Ce poste n'est pas un TopWay (TS10 / TS18) : rien à sonder";
        if (probing) return "Sondage déjà en cours";
        if (tw == null) { String err = open(); if (err != null) return err; }
        synchronized (LOCK) { last.clear(); count.clear(); logged.clear(); lines = 0; }
        probing = true; running = true;
        Hub.log("TS10 : sondage du CAN démarré pour " + seconds + " s (lecture seule)");
        handler.postDelayed(new Runnable() { public void run() { stop(); } }, seconds * 1000L);
        return "Sondage lancé pour " + Math.max(1, seconds / 60) + " min";
    }

    /** fin du sondage : résumé dans le journal ; TWUtil reste ouvert pour les portes */
    static synchronized void stop() {
        if (!probing) return;
        probing = false; running = false;
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            for (Map.Entry<String, Integer> e : count.entrySet()) sb.append(e.getKey()).append(" ×").append(e.getValue()).append(", ");
        }
        Hub.log(sb.length() == 0 ? "TS10 : fin du sondage, aucun message reçu (TWUtil ouvert mais muet)"
                : "TS10 : fin du sondage. Messages reçus : " + sb.substring(0, sb.length() - 2));
    }

    private static void release() {
        try { if (tw != null) cls.getMethod("removeHandler", String.class).invoke(tw, NAME); } catch (Throwable ignored) {}
        try { if (tw != null) cls.getMethod("stop").invoke(tw); } catch (Throwable ignored) {}
        try { if (tw != null) cls.getMethod("close").invoke(tw); } catch (Throwable ignored) {}
        tw = null; handler = null;
        if (thread != null) { thread.quitSafely(); thread = null; }
    }

    /** trame portes : 0x0505 / arg1 0 ou 0x0501 / arg1 0x24 (même octet) */
    private static void doors(Message m) {
        int what = m.what & 0xFFFF;
        if (!((what == 0x0501 && (m.arg1 & 0xFF) == 0x24) || (what == 0x0505 && m.arg1 == 0))) return;
        if (!(m.obj instanceof byte[]) || ((byte[]) m.obj).length == 0) return;
        int s = ((byte[]) m.obj)[0] & 0xFF;
        boolean[] d = new boolean[6];
        for (int i = 0; i < 6; i++) d[i] = (s & bits[i]) != 0;
        if (!liveDoors) { liveDoors = true; Hub.log("PORTES : lues en direct sur le CAN du poste TopWay (TWUtil)"); }
        if (System.currentTimeMillis() < CanBridge.simUntil) { lastDoors = null; return; }   // « Simuler » en cours
        boolean[] prev = lastDoors;
        if (prev != null && java.util.Arrays.equals(d, prev)) return;
        lastDoors = d;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) if (d[i]) sb.append(DK[i]).append(", ");
        Hub.log(String.format(Locale.US, "PORTES TS10 : octet %02X -> ", s) + (sb.length() == 0 ? "tout fermé" : sb.substring(0, sb.length() - 2)));
        if (!Learn.doorsActive) Hub.setDoors("CAN TopWay", d);
    }

    private static void onMessage(Message m) {
        try { Learn.feedTw(m.what, m.arg1, m.obj); } catch (Throwable ignored) {}   // v1.25 : apprentissage du véhicule
        try { doors(m); } catch (Throwable ignored) {}
        if (!probing) return;
        int what = m.what & 0xFFFF;
        String key = String.format(Locale.US, "0x%04X/%d", what, m.arg1);
        String data = format(m.obj);
        String sig = m.arg2 + "|" + data;
        String line = null;
        synchronized (LOCK) {
            Integer c = count.get(key); count.put(key, c == null ? 1 : c + 1);
            if (sig.equals(last.get(key))) return;                 // contenu identique : déjà noté
            last.put(key, sig);
            Integer k = logged.get(key); int nk = k == null ? 1 : k + 1; logged.put(key, nk);
            if (nk > MAX_PER_KEY || lines >= MAX_LINES) return;    // message qui change sans cesse (vitesse, régime…)
            lines++;
            line = String.format(Locale.US, "TS10 0x%04X a1=%d (0x%02X) a2=%d %s%s", what, m.arg1, m.arg1 & 0xFF, m.arg2, data,
                    nk == MAX_PER_KEY ? " (dernière notée pour ce message)" : "");
        }
        Hub.log(line);
    }

    private static String format(Object o) {
        if (o == null) return "-";
        if (o instanceof byte[]) {
            byte[] b = (byte[]) o;
            StringBuilder sb = new StringBuilder("[").append(b.length).append("] ");
            for (int i = 0; i < b.length && i < 48; i++) sb.append(String.format(Locale.US, "%02X ", b[i] & 0xFF));
            if (b.length > 48) sb.append("…");
            return sb.toString().trim();
        }
        if (o instanceof String) return "\"" + o + "\"";
        return o.getClass().getSimpleName() + " " + o;
    }
}
