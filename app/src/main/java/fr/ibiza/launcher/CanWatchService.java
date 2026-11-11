package fr.ibiza.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Observe (en lecture seule) les fenêtres affichées par les autres apps — en particulier la fenêtre
 * flottante « portes / radars » de l'autoradio — pour en déduire l'état des ouvrants.
 * Rien n'est modifié dans le système. Tout ce qui est vu est noté dans le journal de diagnostic.
 */
public class CanWatchService extends AccessibilityService {
    private static final Pattern DOORISH = Pattern.compile("door|trunk|hood|bonnet|tailgate|radar|rada|leida|parking|park_|sensor|probe", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_FL = Pattern.compile("(front_?left|left_?front|\\bf_?l\\b|_fl\\b|_lf\\b|\\blf_|\\bfl_|zuoqian|qianzuo|door_?1\\b|door_?lf|door_?fl)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_FR = Pattern.compile("(front_?right|right_?front|\\bf_?r\\b|_fr\\b|_rf\\b|\\brf_|\\bfr_|youqian|qianyou|door_?2\\b|door_?rf|door_?fr)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_RL = Pattern.compile("(rear_?left|left_?rear|back_?left|left_?back|_rl\\b|_lr\\b|_bl\\b|\\brl_|\\blr_|zuohou|houzuo|door_?3\\b|door_?lr|door_?rl)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_RR = Pattern.compile("(rear_?right|right_?rear|back_?right|right_?back|_rr\\b|_br\\b|\\brr_|youhou|houyou|door_?4\\b|door_?rr)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_TRUNK = Pattern.compile("(trunk|tail_?gate|tailgate|\\bboot\\b|back_?door|rear_?door_?box|houbei|houmen|hou_?gai)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_HOOD = Pattern.compile("(hood|bonnet|engine_?cover|front_?cover|qiangai|yinqing|qian_?gai)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_RADAR = Pattern.compile("(radar|rada|leida|park|sensor|probe)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CM = Pattern.compile("(\\d{1,3})\\s*(cm|CM)");

    private final Map<String, String> lastSig = new HashMap<>();
    private JSONObject userMap = null;
    private boolean overlaySeen = false;
    private final Set<String> candidates = new HashSet<>();
    private final Set<String> textApps = new HashSet<>();     // appli CAN (ordinateur de bord) et radio : textes lus
    private long lastScan = 0;
    private boolean rescanPending = false;
    private boolean lastRadar = false;
    private static final Pattern RADIOPKG = Pattern.compile("radio|\\bfm\\b|\\.fm|tuner", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENWORD = Pattern.compile("ouvert|open|开", Pattern.CASE_INSENSITIVE);

    /** « Porte avant gauche ouverte », « Coffre ouvert », « Door open »… */
    private void doorsFromSentence(String tx, String src) {
        if (tx == null || !OPENWORD.matcher(tx).find()) return;
        String l = tx.toLowerCase();
        boolean[] d = new boolean[6]; boolean any = false;
        boolean av = l.contains("avant") || l.contains("front"), ar = l.contains("arrière") || l.contains("arriere") || l.contains("rear");
        boolean g = l.contains("gauche") || l.contains("left") || l.contains("conducteur") || l.contains("driver"), dr = l.contains("droite") || l.contains("right") || l.contains("passager") || l.contains("passenger");
        if (l.contains("porte") || l.contains("door")) {
            if (g && !ar) { d[0] = true; any = true; } if (dr && !ar) { d[1] = true; any = true; }
            if (g && ar) { d[2] = true; any = true; } if (dr && ar) { d[3] = true; any = true; }
        }
        if (l.contains("coffre") || l.contains("hayon") || l.contains("trunk") || l.contains("boot") || l.contains("tailgate")) { d[4] = true; any = true; }
        if (l.contains("capot") || l.contains("hood") || l.contains("bonnet")) { d[5] = true; any = true; }
        if (any) Hub.setDoors(src, d);
    }
    private String radioRole() { return role("radio"); }
    /** paquet de l'appli choisie dans Réglages > Applis par défaut (« radio », « can »…), "" si aucune */
    private String role(String which) {
        try { String r = getSharedPreferences("ibz", MODE_PRIVATE).getString("roles", null); if (r == null) return ""; Object o = new org.json.JSONTokener(r).nextValue(); if (o instanceof String) o = new JSONObject((String) o); String v = ((JSONObject) o).optString(which, ""); int i = v.indexOf('/'); return i > 0 ? v.substring(0, i) : v; }
        catch (Exception e) { return ""; }
    }
    private static final Pattern CARPKG = Pattern.compile("can|car|bus|vehicle|radar|door|park", Pattern.CASE_INSENSITIVE);

    /* ======== lecture automatique de l'ordinateur de bord CarCan ========
       CarCan (com.hcn.autocan) ne publie ses données (carburant, km, batterie…) qu'à son propre écran : son
       service « ICustomDataServices » existe mais n'envoie rien (vérifié dans l'APK v43). On ouvre donc CarCan,
       on touche « CarInfo », on lit l'écran, puis on revient sur le lanceur — masqué par le rideau depuis la v1.10. */
    static volatile CanWatchService self;
    static volatile long refreshUntil = 0;
    static final String HCN = "com.hcn.autocan";
    /** v1.27 : appli lue pendant la lecture automatique : celle du profil appris (CanApp), sinon CarCan */
    static String refreshPkg() { return CanApp.has() ? CanApp.pkg() : HCN; }
    /** la lecture automatique est-elle possible ? (accessibilité active + appli CAN connue et installée) */
    static boolean refreshPossible(android.content.Context c) {
        if (self == null) return false;
        try { return c.getPackageManager().getLaunchIntentForPackage(refreshPkg()) != null; } catch (Exception e) { return false; }
    }
    private static boolean finishing = false;
    private static int gen = 0;
    static volatile boolean returning = false;   // retour discret : le lanceur ne repasse pas à l'accueil            // numéro de lecture (un ancien délai de sécurité ne coupe pas la suivante)
    private static final Handler UI = new Handler(Looper.getMainLooper());
    /* v1.10 : lecture « invisible » — un rideau (image figée du lanceur) couvre l'écran pendant que CarCan
       s'ouvre dessous ; si l'accessibilité n'est pas active, on garde l'ancien comportement (CarCan visible). */
    /* v1.18 : au premier démarrage, CarCan plante parfois quand on l'ouvre directement sur sa page d'infos
       (« CarCan Program exception exit now! »). On relance alors CarCan normalement et on passe par son menu ;
       ce mode reste actif jusqu'au prochain redémarrage de l'autoradio. */
    static volatile boolean plainLaunch = false;
    /** page des infos véhicule de CarCan ouverte directement (profil VW : com.hcn.autocan.SimpleSoft.VW.VWInfoFragment) */
    static final String CARCAN_INFO_FRAGMENT = "com.hcn.autocan.hiworld.ui.carinfo.CarInfoUIFragment";
    private static boolean retried = false;
    private static Intent carcanIntent(android.content.Context c, boolean direct) {
        if (CanApp.has()) {                    // v1.27 : appli CAN apprise ; le chemin enregistré est rejoué par CanApp.drive
            Intent g = CanApp.launchIntent(c, CanApp.pkg(), CanApp.blindTaps());
            if (g == null) Hub.log("Appli CAN " + CanApp.pkg() + " introuvable");
            return g;
        }
        Intent i;
        try { i = c.getPackageManager().getLaunchIntentForPackage("com.hcn.autocan"); } catch (Exception e) { Hub.log("CarCan refresh : " + e); return null; }
        if (i == null) { Hub.log("CarCan introuvable"); return null; }
        // v1.15.1 : CarCan accepte l'extra « ToFragment » (nom de classe du fragment) -> ouverture directe sur la page des infos,
        // sans passer par le menu (relevé dans CanActivity.ProcessExtraData / onResume, CarCan v43)
        // Clio : profil SimpleSoft Renault -> page générique « infos véhicule » de CarCan (la page VW n'existe pas pour ce profil)
        if (direct) i.putExtra("ToFragment", CARCAN_INFO_FRAGMENT);
        // CLEAR_TOP + SINGLE_TOP : si CarCan tourne déjà, l'intention (et son extra « ToFragment ») lui est bien remise
        // (avec NEW_TASK seul, Android ramène juste l'ancienne tâche, restée sur le menu « Infos car »)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return i;
    }
    private static void launch(final android.content.Context c, Intent i, final int my, long timeout) {
        android.os.Bundle o = null;
        try { o = android.app.ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle(); } catch (Exception ignored) {}
        c.startActivity(i, o);
        // un menu immobile n'envoie plus d'événement : on relit l'écran de CarCan régulièrement pendant la lecture
        UI.postDelayed(new Runnable() { public void run() {
            if (refreshUntil == 0 || gen != my || self == null) return;
            try { self.scanWindows(); } catch (Throwable ignored) {}
            UI.postDelayed(this, CanApp.has() ? 600 : 1400);
        }}, CanApp.has() ? 800 : 1200);
        UI.postDelayed(new Runnable() { public void run() { if (refreshUntil > 0 && gen == my) { Hub.log("CarCan : délai dépassé"); finishRefresh(); } } }, timeout);
    }
    static void requestRefresh(final android.content.Context c) {
        // v1.27 : une lecture restée « en cours » bien après son délai (capture d'écran jamais revenue…) ne bloque plus les suivantes
        if (refreshUntil > 0 && System.currentTimeMillis() < refreshUntil + 5000) return;          // lecture déjà en cours
        if (CanApp.recording) return;          // apprentissage de l'appli CAN en cours
        if (refreshUntil > 0) { Hub.log("CarCan : lecture précédente restée en attente, remise à zéro"); refreshUntil = 0; ++gen; Curtain.hideNow(); }
        if (LogCan.live()) {                   // v1.15 : valeurs déjà reçues en direct par le journal, pas besoin d'ouvrir CarCan
            Hub.log("CarCan : inutile, données en direct (journal)");
            if (Hub.carJson != null) Hub.emit("car", Hub.carJson);
            return;
        }
        final Intent i = carcanIntent(c, !plainLaunch);
        if (i == null) return;
        final long budget = CanApp.has() ? CanApp.budget() : 9000;
        CanApp.runStart();
        refreshUntil = System.currentTimeMillis() + budget; finishing = false; retried = false; final int my = ++gen; if (self != null) { self.menuTries = 0; self.lastMenu = ""; }
        final Runnable go = new Runnable() { public void run() {
            try {
                Hub.log((CanApp.has() ? CanApp.label() : "CarCan") + " : lecture automatique de l'ordinateur de bord" + (Curtain.shown() ? " (masquée)" : "") + (plainLaunch && !CanApp.has() ? " par le menu" : ""));
                if (budget > 8000) Curtain.keep(budget + 1500);
                launch(c, i, my, budget);
            } catch (Exception e) { Hub.log("CarCan refresh : " + e); refreshUntil = 0; Curtain.hide(); }
        }};
        if (self != null && c instanceof MainActivity) {
            Curtain.snapshot((MainActivity) c, ((MainActivity) c).contentView(), new Curtain.Snap() { public void done(android.graphics.Bitmap b) {
                if (Curtain.show(self, b)) { postponed = 0; UI.postDelayed(go, 60); return; }   // le rideau est à l'écran avant que CarCan ne s'ouvre
                // v1.19 : pas de rideau (capture noire : lanceur pas encore affiché) -> lecture reportée, jamais d'écran noir
                refreshUntil = 0; ++gen;
                if (postponed++ < 2) {
                    Hub.log("CarCan : lecture reportée de 4 s (écran du lanceur pas encore prêt)");
                    UI.postDelayed(new Runnable() { public void run() { if (Hub.launcherVisible && !((MainActivity) c).isFinishing()) requestRefresh(c); } }, 4000);
                } else { postponed = 0; Hub.log("CarCan : lecture abandonnée (écran du lanceur pas prêt)"); }
            }});
        } else go.run();
    }
    /** CarCan vient d'afficher « Program exception exit now! » pendant une lecture : un seul nouvel essai, par le menu. */
    static void crashRetry() {
        if (refreshUntil == 0 || retried || CanApp.has()) return;
        retried = true; plainLaunch = true;
        final android.content.Context c = Hub.app;
        if (c == null) return;
        Hub.log("CarCan a planté à l'ouverture directe : nouvel essai par le menu");
        refreshUntil = System.currentTimeMillis() + 11000;
        final int my = ++gen; if (self != null) { self.menuTries = 0; self.lastMenu = ""; }
        Curtain.keep(12000);
        UI.postDelayed(new Runnable() { public void run() {
            if (gen != my || refreshUntil == 0) return;
            Intent i = carcanIntent(c, false);
            if (i == null) { finishRefresh(); return; }
            try { launch(c, i, my, 9500); } catch (Exception e) { Hub.log("CarCan refresh : " + e); finishRefresh(); }
        }}, 1500);
    }
    private static int postponed = 0;
    /** v1.19 : une autre appli (CarPlay, téléphone…) passe au premier plan pendant la lecture de CarCan : on arrête tout,
        on retire le rideau tout de suite et on ne revient PAS au lanceur par-dessus elle. */
    static void abortRefresh(String who) {
        if (refreshUntil == 0) return;
        Hub.log("CarCan : lecture interrompue, " + who + " au premier plan (rideau retiré, pas de retour au lanceur)");
        refreshUntil = 0; ++gen; finishing = false;
        UI.removeCallbacksAndMessages(EARLY);
        Curtain.hideNow();
    }
    /** vraie activité d'une autre appli (pas CarCan, pas la barre système, pas une fenêtre flottante ni une boîte de dialogue) */
    private boolean foreignActivity(CharSequence pkg, CharSequence cls) {
        if (pkg == null || cls == null) return false;
        String p = pkg.toString();
        if (p.equals(refreshPkg()) || p.equals("com.android.systemui") || p.equals("android") || p.equals(getPackageName())) return false;
        try { getPackageManager().getActivityInfo(new android.content.ComponentName(p, cls.toString()), 0); return true; }
        catch (Exception e) { return false; }
    }
    static void finishRefresh() {
        if (finishing) return;
        finishing = true;
        refreshUntil = 0;
        android.content.Context c = Hub.app;
        if (c == null) { Curtain.hide(); return; }
        Intent h = new Intent(c, MainActivity.class);
        h.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        h.putExtra("ibz_return", true); returning = true;    // retour discret : le lanceur reste sur la page où l'on était
        android.os.Bundle o = null;
        try { o = android.app.ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle(); } catch (Exception ignored) {}
        try { c.startActivity(h, o); } catch (Exception ignored) {}
        // le rideau ne se lève qu'une fois le lanceur revenu au premier plan
        final long t0 = System.currentTimeMillis();
        UI.post(new Runnable() { public void run() {
            if (Hub.launcherVisible || System.currentTimeMillis() - t0 > 2000) UI.postDelayed(new Runnable() { public void run() { Curtain.hide(); } }, 180);
            else UI.postDelayed(this, 40);
        }});
    }
    private static final Object EARLY = new Object();
    private long lastMenuClick = 0;
    /** pendant une lecture auto : ouvre la page « CarInfo » si on est sur le menu de CarCan */
    /* v1.14.2 : CarCan s'ouvre d'abord sur un menu (« Infos car », « CarInfo »…) ; on cherche l'entrée par son texte
       normalisé (casse, espaces, accents ignorés), on la touche par l'accessibilité, et si la page ne change pas,
       par un vrai toucher à l'écran (certains menus de ces autoradios n'écoutent que les touchers). */
    private static final Pattern[] MENU = {
            Pattern.compile("^(car ?infos?|infos? ?(du |de la )?(car|voiture|vehicule))$"),
            Pattern.compile("(car ?infos?|infos? ?car\\b|infos? (du |de la )?(voiture|vehicule)|obd|odb|ordinateur de bord|trip ?computer|drive ?info)"),
            Pattern.compile("(informations? de (voyage|conduite)|informations?$)")
    };
    private int menuTries = 0;
    private String lastMenu = "";
    private static String norm(CharSequence cs) {
        if (cs == null) return "";
        String t = java.text.Normalizer.normalize(cs.toString(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.toLowerCase(java.util.Locale.FRANCE).replaceAll("[_\\-/]+", " ").replaceAll("\\s+", " ").trim();
    }
    private AccessibilityNodeInfo findMenu(AccessibilityNodeInfo n, Pattern p, int depth) {
        if (n == null || depth > 25) return null;
        String t = norm(n.getText()), cd = norm(n.getContentDescription());
        if ((t.length() > 0 && t.length() < 40 && p.matcher(t).find()) || (cd.length() > 0 && cd.length() < 40 && p.matcher(cd).find())) return n;
        for (int i = 0; i < n.getChildCount(); i++) { AccessibilityNodeInfo r = findMenu(n.getChild(i), p, depth + 1); if (r != null) return r; }
        return null;
    }
    private void driveRefresh(AccessibilityNodeInfo root, List<String> ids) {
        if (refreshUntil == 0 || System.currentTimeMillis() > refreshUntil) return;
        if (ids.contains("ODBpage") || ids.contains("odbtextValue") || ids.contains("textValue") || ids.contains("odbtextName")) {
            menuTries = 0;
            UI.postDelayed(new Runnable() { public void run() { if (refreshUntil > 0) { Hub.log("CarCan : lu, retour au lanceur"); finishRefresh(); } } }, 900);
            return;
        }
        if (System.currentTimeMillis() - lastMenuClick < 1300) return;
        AccessibilityNodeInfo hit = null; Pattern used = null;
        for (Pattern p : MENU) { hit = findMenu(root, p, 0); if (hit != null) { used = p; break; } }
        if (hit == null) { if (menuTries == 0) { Hub.log("CarCan : entrée « Infos car » introuvable sur cet écran"); menuTries = -1; } return; }
        String label = hit.getText() != null ? hit.getText().toString() : String.valueOf(hit.getContentDescription());
        if (!label.equals(lastMenu)) { menuTries = 0; lastMenu = label; }
        menuTries++;
        lastMenuClick = System.currentTimeMillis();
        // les cases du menu CarCan ne réagissent qu'au TOUCHER (OnTouchListener sur l'image), pas au clic d'accessibilité :
        // on touche l'image de la case (voisine du libellé), sinon la case entière, sinon le libellé
        android.graphics.Rect r = new android.graphics.Rect();
        AccessibilityNodeInfo img = imageNear(hit);
        if (menuTries == 1 && img != null) { img.getBoundsInScreen(r); Hub.log("CarCan : toucher l'image de « " + label + " » " + r.toShortString()); }
        else if (menuTries <= 2 && hit.getParent() != null) { hit.getParent().getBoundsInScreen(r); Hub.log("CarCan : toucher la case de « " + label + " » " + r.toShortString()); }
        else if (menuTries <= 4) { hit.getBoundsInScreen(r); Hub.log("CarCan : toucher le libellé « " + label + " » " + r.toShortString()); }
        else { if (menuTries == 5) hit.performAction(AccessibilityNodeInfo.ACTION_CLICK); return; }
        if (r.width() > 0 && r.height() > 0) tapAt(r.centerX(), r.centerY());
    }
    /** l'image (ImageView) de la même case que le libellé trouvé */
    private static AccessibilityNodeInfo imageNear(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n;
        for (int up = 0; up < 3 && p != null; up++) {
            p = p.getParent(); if (p == null) break;
            for (int i = 0; i < p.getChildCount(); i++) {
                AccessibilityNodeInfo c = p.getChild(i);
                if (c != null && c.getClassName() != null && c.getClassName().toString().contains("ImageView")) return c;
            }
        }
        return null;
    }
    /** toucher simulé (Android 7+) ; le rideau laisse passer le toucher le temps du geste */
    private void tapAt(int x, int y) { gesture(x, y, x, y, 80, null); }   // v1.27.1 : attend que le rideau laisse vraiment passer le toucher

    @Override protected void onServiceConnected() {
        self = this;
        Hub.app = getApplicationContext();
        Hub.log("Accessibilité connectée : surveillance des fenêtres active");
        loadMap();
        CanApp.init(this);
        CanBridge.start(this);
        Ts10Probe.startLive();            // v1.24.2 : portes TopWay lues en direct (sans effet sur les autres postes)
    }

    private void loadMap() {
        try {
            File f = new File(getExternalFilesDir(null), "ibiza_map.json");
            if (!f.exists()) return;
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()]; int n = in.read(b); in.close();
            userMap = new JSONObject(new String(b, 0, n, "UTF-8"));
            Hub.log("Correspondance perso chargée : " + userMap.toString());
        } catch (Exception e) { Hub.log("ibiza_map.json illisible : " + e); }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence p = e.getPackageName();
        if (p != null && getPackageName().contentEquals(p)) return;
        int t = e.getEventType();
        if (t == AccessibilityEvent.TYPE_VIEW_CLICKED) { CanApp.onClicked(e); return; }   // v1.27 : apprentissage de l'appli CAN sur Android 6
        if (t == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) { // toasts et notifications : « Porte ouverte »…
            String tx = String.valueOf(e.getText());
            Hub.log("TOAST/NOTIF " + p + " · " + tx);
            if (p != null && HCN.contentEquals(p) && tx.contains("exception")) crashRetry();
            if (!CanBridge.cemDoors && !Ts10Probe.liveDoors && !Learn.doorsActive && !Hub.EMULATOR) doorsFromSentence(tx, "message " + p);
            return;
        }
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && p != null) {
            Hub.log("FENÊTRE " + p + " · " + e.getClassName() + (e.getText().isEmpty() ? "" : " · " + e.getText()));
            if (CanApp.recording) CanApp.onWindowState(p, e.getClassName());   // v1.27.1 : écran des infos ouvrable directement ?
            if (refreshUntil > 0 && foreignActivity(p, e.getClassName())) abortRefresh(p.toString());   // v1.19
            if (!CanBridge.cemDoors && !Ts10Probe.liveDoors && !Learn.doorsActive && !Hub.EMULATOR) doorsFromSentence(String.valueOf(e.getText()), "fenêtre " + p);
        }
        // les changements de contenu ne déclenchent un balayage que pour les apps intéressantes (portes, CAN, radio)
        // v1.27 : pendant « Apprendre ma voiture », et pour les fenêtres retenues par la carte apprise, tout changement compte
        // (avant, une porte qui s'ajoutait dans une fenêtre déjà affichée d'une appli inconnue n'était pas vue)
        if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && !Learn.learning
                && (p == null || !(candidates.contains(p.toString()) || textApps.contains(p.toString()) || Learn.watches(p.toString())))) return;
        long now = System.currentTimeMillis();
        if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastScan < 400) {   // la radio rafraîchit très souvent
            // un changement ignoré ici est relu un peu plus tard (sinon le dernier état d'une fenêtre peut être manqué)
            if (!rescanPending) { rescanPending = true; UI.postDelayed(new Runnable() { public void run() { rescanPending = false; lastScan = System.currentTimeMillis(); scanWindows(); } }, 450); }
            return;
        }
        lastScan = now;
        scanWindows();
    }

    private void scanWindows() {
        List<AccessibilityWindowInfo> ws;
        try { ws = getWindows(); } catch (Exception ex) { return; }
        boolean overlayNow = false;
        boolean[] door = new boolean[6]; // fl fr rl rr trunk hood
        boolean radar = false, reverse = false;
        boolean[] popup = null; int popupWin = -1;          // fenêtre portes de CarCan (door_layout)
        List<Integer> dists = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        java.util.Map<String, List<String>> learnIds = Learn.wants() ? new java.util.HashMap<String, List<String>>() : null;   // v1.25
        final String radio = radioRole(), canPkg = CanApp.has() ? CanApp.pkg() : "", canRole = role("can");   // v1.27 : lus une fois par balayage
        List<ScreenRead.Item> profItems = null; int[] itemNo = { 0 };
        for (AccessibilityWindowInfo w : ws) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            CharSequence pk = root.getPackageName();
            String pkg = pk == null ? "?" : pk.toString();
            if (getPackageName().equals(pkg)) continue;
            List<String> ids = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            List<String[]> pairs = new ArrayList<>();
            collect(root, ids, texts, pairs, 0);
            if (learnIds != null && w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) { List<String> l = learnIds.get(pkg); if (l == null) learnIds.put(pkg, l = new ArrayList<>()); l.addAll(ids); }
            final boolean prof = pkg.equals(canPkg);
            if (prof && w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {     // v1.27 : appli CAN apprise
                if (profItems == null) profItems = new ArrayList<>();
                collectItems(root, profItems, -1, -1, itemNo, 0);
                textApps.add(pkg);
            }
            if (pkg.equals(HCN) && !prof) driveRefresh(root, ids);
            if (pkg.equals(HCN) && ids.contains("carbg") && ids.contains("closedoor")) {
                popup = new boolean[]{ ids.contains("leftfrontdoor"), ids.contains("rightfrontdoor"), ids.contains("leftreardoor"),
                                       ids.contains("rightreardoor"), ids.contains("reardoor"), ids.contains("frontdoor") };
                popupWin = w.getId();
            }
            // --- appli radio : fréquence / nom de station ; appli CAN : ordinateur de bord ---
            boolean radioApp = RADIOPKG.matcher(pkg).find() || pkg.equals(radio);
            if (radioApp) {
                textApps.add(pkg);
                String[] rd = CarText.parseRadio(pairs);
                if (rd != null) CanBridge.radio(rd[0], rd[1], rd[2], "écran " + pkg);
            } else if (prof) {
                // lue plus bas avec son profil (CanApp)
            } else if (pkg.equals(HCN)) {
                JSONObject car = CarText.parseHcn(pairs);
                if (car.length() > 0) { textApps.add(pkg); Hub.mergeCar(car, "CarCan"); }
                if (refreshUntil > 0 && !finishing && car.length() >= 3) {   // valeurs lues : retour immédiat
                    refreshUntil = System.currentTimeMillis() + 2000;
                    UI.removeCallbacksAndMessages(EARLY); UI.postAtTime(new Runnable() { public void run() { if (refreshUntil > 0) { Hub.log("CarCan : lu, retour au lanceur"); finishRefresh(); } } }, EARLY, android.os.SystemClock.uptimeMillis() + 350);
                }
            } else if (w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD && texts.size() > 1) {
                JSONObject car = CarText.parseScreen(texts);
                boolean carApp = CARPKG.matcher(pkg).find() || pkg.equals(canRole);
                if (car.length() >= 2 || (car.length() >= 1 && carApp)) { textApps.add(pkg); Hub.mergeCar(car, "écran " + pkg); }
                else if (carApp && canPkg.isEmpty() && !Hub.EMULATOR) {
                    // v1.27 : appli « voiture » pas encore apprise : lecture par la position des textes (libellés en 10 langues)
                    List<ScreenRead.Item> its = new ArrayList<>(); collectItems(root, its, -1, -1, new int[]{ 0 }, 0);
                    JSONObject auto = CanApp.auto(its);
                    if (auto.length() >= 2) { textApps.add(pkg); Hub.mergeCar(auto, "écran " + pkg); }
                }
            }
            Rect r = new Rect(); w.getBoundsInScreen(r);
            String key = pkg + "#" + w.getId();
            seen.add(key);
            boolean candidate = false;
            for (String id : ids) if (DOORISH.matcher(id).find()) { candidate = true; break; }
            if (userMap != null && pkg.equals(userMap.optString("package"))) candidate = true;
            if (candidate) candidates.add(pkg);
            boolean interesting = candidate || w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM || CARPKG.matcher(pkg).find();
            String sig = interesting ? ids.toString() + texts.toString() : ids.toString();
            if (!sig.equals(lastSig.get(key))) {
                lastSig.put(key, sig);
                if (interesting) Hub.log("VUE " + pkg + " type=" + w.getType() + " " + r.toShortString() + "\n    ids=" + ids + "\n    textes=" + texts);
                else Hub.log("vue " + pkg + " type=" + w.getType() + " (" + ids.size() + " éléments)");
            }
            if (!candidate || w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            // fenêtre portes / radars détectée
            overlayNow = true;
            if (userMap != null && pkg.equals(userMap.optString("package"))) {
                JSONObject d = userMap.optJSONObject("doors");
                String[] k = {"fl", "fr", "rl", "rr", "trunk", "hood"};
                if (d != null) for (int i = 0; i < 6; i++) { String id = d.optString(k[i], ""); if (id.length() > 0 && ids.contains(id)) door[i] = true; }
                String rid = userMap.optString("radar", "");
                if (rid.length() > 0 && ids.contains(rid)) { radar = true; reverse = true; }
            } else {
                for (String id : ids) {
                    if (P_RADAR.matcher(id).find()) { radar = true; reverse = true; continue; }
                    if (P_TRUNK.matcher(id).find()) door[4] = true;
                    else if (P_HOOD.matcher(id).find()) door[5] = true;
                    else if (P_FL.matcher(id).find()) door[0] = true;
                    else if (P_FR.matcher(id).find()) door[1] = true;
                    else if (P_RL.matcher(id).find()) door[2] = true;
                    else if (P_RR.matcher(id).find()) door[3] = true;
                }
            }
            for (String s : texts) { java.util.regex.Matcher m = CM.matcher(s); while (m.find()) dists.add(Integer.parseInt(m.group(1))); }
        }
        // oublie les fenêtres disparues
        lastSig.keySet().retainAll(seen);
        if (profItems != null && !CanApp.recording) {            // v1.27 : appli CAN apprise à l'écran
            int[] wh = screen();
            if (refreshUntil > 0 && !finishing && System.currentTimeMillis() < refreshUntil) {
                int r = CanApp.drive(this, profItems, wh[0], wh[1]);
                if (r == CanApp.DONE) { Hub.log(CanApp.label() + " : lu, retour au lanceur"); finishRefresh(); }
                else if (r == CanApp.FAILED) finishRefresh();
                else if (r == CanApp.RESTART) {
                    try { Intent i = CanApp.launchIntent(this, canPkg, true); if (i != null) startActivity(i); } catch (Exception ex) { Hub.log("APPLI CAN : relance impossible : " + ex); finishRefresh(); }
                }
            } else if (refreshUntil == 0) CanApp.passive(profItems, wh[0], wh[1]);
        }
        if (learnIds != null) Learn.feedWindows(learnIds);
        CanBridge.popupDoors(popup, popupWin);
        if (CanBridge.cemDoors || Ts10Probe.liveDoors || Learn.doorsActive) { // les portes viennent des propriétés CEM ou du CAN TopWay (v1.24.2) (+ avance de la fenêtre CarCan) ; ici on ne garde que les radars
            if (radar != lastRadar && !Learn.reverseActive) {
                lastRadar = radar;
                try {
                    JSONObject v = new JSONObject(Hub.vehicleJson);
                    if (radar) {
                        JSONObject rd = new JSONObject(); org.json.JSONArray rear = new org.json.JSONArray();
                        for (int i = 0; i < 4; i++) rear.put(i < dists.size() ? (Object) dists.get(i) : JSONObject.NULL);
                        rd.put("rear", rear); rd.put("front", new org.json.JSONArray("[null,null,null,null]"));
                        v.put("radar", rd); v.put("reverse", true);
                    } else { v.put("radar", JSONObject.NULL); v.put("reverse", false); }
                    Hub.emit("vehicle", v.toString());
                } catch (Exception ignored) {}
            }
            overlaySeen = overlayNow; return;
        }
        if (Hub.EMULATOR) { overlaySeen = false; return; }      // émulateur : pas de fenêtre portes à interpréter
        if (overlayNow || overlaySeen) {
            boolean anySpecific = door[0] || door[1] || door[2] || door[3] || door[4] || door[5];
            try {
                JSONObject v = new JSONObject(Hub.vehicleFrom(door[0], door[1], door[2], door[3], door[4], door[5], overlayNow && !anySpecific && !radar));
                if (radar) {
                    JSONObject rd = new JSONObject();
                    org.json.JSONArray rear = new org.json.JSONArray();
                    for (int i = 0; i < 4; i++) rear.put(i < dists.size() ? (Object) dists.get(i) : JSONObject.NULL);
                    rd.put("rear", rear); rd.put("front", new org.json.JSONArray("[null,null,null,null]"));
                    v.put("radar", rd); v.put("reverse", reverse);
                }
                String js = v.toString();
                if (!js.equals(Hub.vehicleJson)) Hub.emit("vehicle", js);
            } catch (Exception ignored) {}
        }
        overlaySeen = overlayNow;
    }

    private void collect(AccessibilityNodeInfo n, List<String> ids, List<String> texts, List<String[]> pairs, int depth) {
        if (n == null || depth > 25 || ids.size() > 250) return;
        if (n.isVisibleToUser()) {
            String id = n.getViewIdResourceName(), sid = null;
            if (id != null) { int s = id.indexOf(":id/"); sid = s >= 0 ? id.substring(s + 4) : id; ids.add(sid); }
            CharSequence tx = n.getText(); if (tx != null && tx.length() > 0 && tx.length() < 80) { texts.add(tx.toString()); pairs.add(new String[]{sid, tx.toString()}); }
            CharSequence cd = n.getContentDescription(); if (cd != null && cd.length() > 0 && cd.length() < 60) texts.add("[" + cd + "]");
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), ids, texts, pairs, depth + 1);
    }

    @Override public void onInterrupt() {}

    /* v1.27 : service coupé (réglage désactivé, mise à jour du lanceur) : avant, « self » gardait l'ancien service et le
       lanceur croyait encore pouvoir lire l'appli CAN (rideau refusé, lecture sans fin). */
    @Override public boolean onUnbind(Intent i) { gone(); return super.onUnbind(i); }
    @Override public void onDestroy() { gone(); super.onDestroy(); }
    private void gone() {
        if (self != this) return;
        Hub.log("Accessibilité arrêtée : plus de lecture des fenêtres");
        if (CanApp.recording) CanApp.finish(true);
        if (refreshUntil > 0) { refreshUntil = 0; ++gen; }
        Curtain.hideNow();
        self = null;
    }

    /* ======== v1.27 : outils pour les applis CAN apprises (CanApp) ======== */
    /** taille réelle de l'écran (les cadres donnés par l'accessibilité sont dans ce repère) */
    int[] screen() {
        try {
            android.graphics.Point pt = new android.graphics.Point();
            ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(pt);
            if (pt.x > 0 && pt.y > 0) return new int[]{ pt.x, pt.y };
        } catch (Throwable ignored) {}
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return new int[]{ Math.max(1, dm.widthPixels), Math.max(1, dm.heightPixels) };
    }
    /** textes visibles (avec leur cadre) de toutes les fenêtres d'une appli */
    List<ScreenRead.Item> itemsOf(String pkg) {
        List<ScreenRead.Item> out = new ArrayList<>();
        try {
            int[] n = { 0 };
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null || !pkg.contentEquals(root.getPackageName())) continue;
                collectItems(root, out, -1, -1, n, 0);
            }
        } catch (Throwable e) { Hub.log("APPLI CAN : lecture de l'écran : " + e); }
        return out;
    }
    private void collectItems(AccessibilityNodeInfo n, List<ScreenRead.Item> out, int p1, int p2, int[] count, int depth) {
        if (n == null || depth > 30 || out.size() > 400) return;
        int me = count[0]++;
        if (n.isVisibleToUser()) {
            CharSequence tx = n.getText();
            if (tx != null && tx.length() > 0 && tx.length() <= 80 && tx.toString().trim().length() > 0) {
                Rect r = new Rect(); n.getBoundsInScreen(r);
                if (!r.isEmpty()) {
                    String id = n.getViewIdResourceName(); if (id != null) { int s = id.indexOf(":id/"); if (s >= 0) id = id.substring(s + 4); }
                    out.add(new ScreenRead.Item(id, tx.toString(), r.left, r.top, r.right, r.bottom, p1, p2));
                }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) collectItems(n.getChild(i), out, me, p1, count, depth + 1);
    }
    /** toucher ou glissé simulé (Android 7+) ; le rideau laisse passer le geste ; done est appelé à la fin (réussi ou non) */
    /* v1.27.1 : relevé sur le Junsun, aucun toucher simulé n'arrivait à l'appli CAN pendant l'apprentissage : la fenêtre du
       lanceur posée par-dessus, rendue « traversable » juste avant, l'interceptait encore (le changement n'est appliqué
       qu'à l'image suivante de l'écran). Quand le rideau est affiché, on attend maintenant deux images + 70 ms après l'avoir
       rendu traversable avant d'envoyer le toucher. */
    boolean gesture(final float x1, final float y1, final float x2, final float y2, final int ms, final Runnable done) {
        if (android.os.Build.VERSION.SDK_INT < 24) return false;
        final boolean cur = Curtain.shown();
        final Runnable end = new Runnable() { public void run() { if (cur) Curtain.passThrough(false); if (done != null) done.run(); } };
        final Runnable go = new Runnable() { public void run() {
            try {
                android.graphics.Path p = new android.graphics.Path(); p.moveTo(x1, y1);
                if (Math.abs(x2 - x1) > 1 || Math.abs(y2 - y1) > 1) p.lineTo(x2, y2);
                android.accessibilityservice.GestureDescription g = new android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, Math.max(40, ms))).build();
                boolean ok = dispatchGesture(g, new GestureResultCallback() {
                    @Override public void onCompleted(android.accessibilityservice.GestureDescription d) { UI.postDelayed(end, 120); }
                    @Override public void onCancelled(android.accessibilityservice.GestureDescription d) { Hub.log("APPLI CAN : geste annulé par le système"); end.run(); }
                }, null);
                if (!ok) { Hub.log("APPLI CAN : geste refusé (désactive puis réactive l'accessibilité du lanceur)"); end.run(); }
            } catch (Throwable e) { Hub.log("APPLI CAN : geste " + e); end.run(); }
        }};
        if (cur) { Curtain.passThrough(true); afterFrames(go, 2, 70); } else go.run();
        return true;
    }
    private static void afterFrames(final Runnable r, final int n, final long extra) {
        try {
            android.view.Choreographer.getInstance().postFrameCallback(new android.view.Choreographer.FrameCallback() {
                int left = n;
                @Override public void doFrame(long t) { if (--left > 0) android.view.Choreographer.getInstance().postFrameCallback(this); else UI.postDelayed(r, extra); }
            });
        } catch (Throwable e) { UI.postDelayed(r, 120); }
    }
    /** v1.27.1 : clic d'accessibilité sur l'élément cliquable situé à cet endroit de l'écran (secours d'un toucher sans effet) */
    boolean clickAt(String pkg, int x, int y) {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null || !pkg.contentEquals(root.getPackageName())) continue;
                AccessibilityNodeInfo n = deepestAt(root, x, y, 0);
                for (int up = 0; n != null && up < 6; up++, n = n.getParent()) if (n.isClickable() && n.isEnabled()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        } catch (Throwable ignored) {}
        return false;
    }
    private AccessibilityNodeInfo deepestAt(AccessibilityNodeInfo n, int x, int y, int depth) {
        if (n == null || depth > 30 || !n.isVisibleToUser()) return null;
        Rect r = new Rect(); n.getBoundsInScreen(r);
        if (!r.contains(x, y)) return null;
        for (int i = n.getChildCount() - 1; i >= 0; i--) { AccessibilityNodeInfo c = deepestAt(n.getChild(i), x, y, depth + 1); if (c != null) return c; }
        return n;
    }
    /** touche Retour (chemin retrouvé : une entrée de menu a ouvert la mauvaise page) */
    boolean back() { try { return performGlobalAction(GLOBAL_ACTION_BACK); } catch (Throwable e) { return false; } }
    /** Android 6 : clic d'accessibilité sur l'élément qui porte ce texte (ou son conteneur cliquable) */
    boolean clickText(String pkg, String normText) {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null || !pkg.contentEquals(root.getPackageName())) continue;
                AccessibilityNodeInfo n = findText(root, normText, 0);
                for (int up = 0; n != null && up < 4; up++, n = n.getParent()) if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        } catch (Throwable ignored) {}
        return false;
    }
    private AccessibilityNodeInfo findText(AccessibilityNodeInfo n, String t, int depth) {
        if (n == null || depth > 30) return null;
        CharSequence tx = n.getText() != null ? n.getText() : n.getContentDescription();
        if (tx != null && ScreenRead.norm(tx.toString()).equals(t)) return n;
        for (int i = 0; i < n.getChildCount(); i++) { AccessibilityNodeInfo r = findText(n.getChild(i), t, depth + 1); if (r != null) return r; }
        return null;
    }
}
