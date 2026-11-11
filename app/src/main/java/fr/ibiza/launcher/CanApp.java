package fr.ibiza.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * v1.27 : « Apprendre l'ordinateur de bord » — l'appli CAN de n'importe quel autoradio, sans adb ni ordinateur.
 *
 * Avant, seul CarCan (com.hcn.autocan, postes Junsun « hcn ») était lu : son nom de paquet, l'extra qui ouvre sa page
 * d'infos et les identifiants de ses vues étaient écrits dans le code. Or chaque fabricant (TopWay, FYT, Microntek,
 * XY Auto, Zhangxun…) livre sa propre appli CAN. Ici, c'est le client qui montre le chemin une fois :
 *
 *  1. Il choisit l'appli CAN dans une liste. Le lanceur l'ouvre avec une barre « Capturer / Terminer / Main libre ».
 *     Mode normal : chaque toucher du client est reçu par une fenêtre transparente du lanceur, noté, puis rejoué dans
 *     l'appli (toucher simulé de l'accessibilité) ; on vérifie que la page a bien changé.
 *     Mode « main libre » : la fenêtre transparente est retirée, le client touche l'appli lui-même ; le lanceur note
 *     seulement les changements de page, et retrouvera tout seul quel toucher mène d'une page à l'autre (au premier
 *     usage, en essayant les entrées du menu).
 *  2. « Capturer » relève les textes de la page ; ScreenRead propose quelle valeur est le carburant, la batterie…
 *     De retour dans le lanceur, le client corrige ou complète d'un toucher, puis enregistre.
 *  3. Le « profil » obtenu (appli, chemin, repères des valeurs) sert ensuite à la lecture automatique sous le rideau
 *     (CanWatchService), et à la lecture en direct quand le client ouvre lui-même l'appli CAN.
 *
 * Le profil est un petit fichier JSON (Android/data/fr.ibiza.launcher/files/canapp.json) : il part avec le journal
 * par QR codes, et un profil reçu d'un client peut être livré dans l'appli (assets/canapps/<paquet>.json) : les postes
 * qui ont cette appli CAN sont alors reconnus sans apprentissage.
 *
 * v1.27.1 : relevé réel sur le Junsun (CarCan v3.88) — aucun toucher rejoué n'arrivait à CarCan : la fenêtre
 * transparente, rendue « traversable » 70 ms avant le toucher simulé, l'interceptait encore. Elle est maintenant
 * retirée pendant le toucher (et la barre est une fenêtre à part), chaque toucher est vérifié (clic d'accessibilité en
 * secours), et après deux touchers sans effet le lanceur passe tout seul en main libre.
 */
final class CanApp {
    private CanApp() {}

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Context app;

    /* =============================== profil =============================== */
    static final class Step {
        String t = "tap";       // tap, swipe, nav, read
        boolean read, swipe, nav, click, fx = true;
        // toucher / glissé : position (fractions de l'écran), durée, texte repère et décalage toucher - centre du texte
        double x, y, x2, y2, tx, ty, ox, oy; int dur = 80; String txt = "";
        List<String> on = new ArrayList<>();      // textes de la page où ce toucher se fait (vide : pas de contrôle)
        List<String> from = new ArrayList<>(), to = new ArrayList<>();   // changement de page vu en main libre
        List<ScreenRead.Field> fields = new ArrayList<>();
        JSONObject src;                           // entrée du profil (mise à jour quand un chemin est retrouvé)
    }
    private static volatile JSONObject profile;
    private static volatile List<Step> steps = new ArrayList<>();
    private static volatile String pkg = "", label = "", act = "";
    private static volatile boolean builtin;

    static boolean has() { return profile != null && !pkg.isEmpty() && !steps.isEmpty(); }
    static String pkg() { return pkg; }
    static String label() { return label; }

    static synchronized void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        try {
            String s = app.getSharedPreferences("ibz_canapp", Context.MODE_PRIVATE).getString("profile", null);
            if (s != null) { use(new JSONObject(s), false); Hub.log("APPLI CAN : profil appris chargé (" + label + ", " + fieldCount() + " données)"); return; }
        } catch (Exception e) { Hub.log("APPLI CAN : profil illisible : " + e); }
        try {                                             // profil déposé à la main (copié depuis un autre poste)
            File f = new File(app.getExternalFilesDir(null), "canapp.json");
            if (f.isFile()) { JSONObject p = new JSONObject(Packs.readText(f)); if (installed(p.optString("pkg"))) { use(p, false); Hub.log("APPLI CAN : profil lu dans canapp.json (" + label + ")"); return; } }
        } catch (Exception e) { Hub.log("APPLI CAN : canapp.json illisible : " + e); }
        try {                                             // profils livrés avec l'appli
            String[] fs = app.getAssets().list("canapps");
            if (fs != null) for (String n : fs) {
                if (!n.endsWith(".json")) continue;
                InputStream in = app.getAssets().open("canapps/" + n);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                try { byte[] b = new byte[8192]; int r; while ((r = in.read(b)) > 0) bo.write(b, 0, r); } finally { in.close(); }
                JSONObject p = new JSONObject(bo.toString("UTF-8"));
                if (!installed(p.optString("pkg"))) continue;
                use(p, true);
                Hub.log("APPLI CAN : profil fourni reconnu pour " + pkg + " (" + label + ")");
                return;
            }
        } catch (Exception e) { Hub.log("APPLI CAN : profils fournis : " + e); }
    }
    private static boolean installed(String p) {
        if (p == null || p.isEmpty()) return false;
        try { app.getPackageManager().getPackageInfo(p, 0); return true; } catch (Exception e) { return false; }
    }
    private static int fieldCount() { int n = 0; for (Step s : steps) n += s.fields.size(); return n; }
    private static List<String> strings(JSONArray a) {
        List<String> l = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) { String s = a.optString(i, ""); if (!s.isEmpty()) l.add(s); }
        return l;
    }

    private static void use(JSONObject p, boolean fromAssets) throws Exception {
        List<Step> l = new ArrayList<>();
        JSONArray seq = p.getJSONArray("seq");
        for (int i = 0; i < seq.length(); i++) {
            JSONObject o = seq.getJSONObject(i); Step s = new Step(); s.src = o;
            s.t = o.optString("t", "tap");
            if ("read".equals(s.t)) {
                s.read = true;
                JSONArray fs = o.optJSONArray("f");
                if (fs != null) for (int j = 0; j < fs.length(); j++) {
                    JSONObject x = fs.getJSONObject(j); ScreenRead.Field f = new ScreenRead.Field();
                    f.key = x.getString("k"); f.id = x.optString("id"); f.ord = x.optInt("ord"); f.same = x.optInt("same"); f.lab = x.optString("lab");
                    f.rx = x.optDouble("rx", 0); f.ry = x.optDouble("ry", 0); f.px = x.optDouble("px", 0); f.py = x.optDouble("py", 0);
                    f.inline = x.optBoolean("inl"); f.txt = x.optBoolean("txt"); f.u = x.optString("u");
                    s.fields.add(f);
                }
                if (s.fields.isEmpty()) continue;
            } else if ("nav".equals(s.t)) {
                s.nav = true; s.from = strings(o.optJSONArray("from")); s.to = strings(o.optJSONArray("to"));
                if (s.to.isEmpty()) continue;
            } else {
                s.swipe = "swipe".equals(s.t); s.click = o.optBoolean("click"); s.fx = o.optBoolean("fx", true);
                if (!s.fx) continue;                      // toucher qui n'avait rien changé pendant l'apprentissage
                s.x = o.optDouble("x", 0.5); s.y = o.optDouble("y", 0.5); s.x2 = o.optDouble("x2", s.x); s.y2 = o.optDouble("y2", s.y);
                s.dur = o.optInt("d", 80); s.txt = o.optString("txt"); s.tx = o.optDouble("tx", s.x); s.ty = o.optDouble("ty", s.y);
                s.ox = o.optDouble("ox", 0); s.oy = o.optDouble("oy", 0); s.on = strings(o.optJSONArray("on"));
            }
            l.add(s);
        }
        while (!l.isEmpty() && !l.get(l.size() - 1).read) l.remove(l.size() - 1);   // touchers après la dernière lecture : inutiles
        if (l.isEmpty()) throw new Exception("profil sans donnée");
        pkg = p.getString("pkg"); label = p.optString("label", pkg); act = p.optString("act", ""); builtin = fromAssets;
        steps = l; profile = p;
    }

    static synchronized void clear() {
        if (app == null) return;
        app.getSharedPreferences("ibz_canapp", Context.MODE_PRIVATE).edit().remove("profile").apply();
        try { new File(app.getExternalFilesDir(null), "canapp.json").delete(); } catch (Exception ignored) {}
        profile = null; steps = new ArrayList<>(); pkg = ""; label = ""; act = ""; builtin = false;
        Hub.log("APPLI CAN : profil oublié");
    }

    /** profil modifié en cours d'usage (chemin retrouvé) : enregistré à nouveau */
    private static void persist() {
        final JSONObject p = profile; final Context c = app;
        if (p == null || c == null || builtin) return;
        new Thread(new Runnable() { public void run() {
            try {
                c.getSharedPreferences("ibz_canapp", Context.MODE_PRIVATE).edit().putString("profile", p.toString()).apply();
                FileOutputStream o = new FileOutputStream(new File(c.getExternalFilesDir(null), "canapp.json")); o.write(p.toString(1).getBytes("UTF-8")); o.close();
            } catch (Exception e) { Hub.log("APPLI CAN : profil non réenregistré : " + e); }
        }}, "ibiza-canapp").start();
    }

    static String info() {
        JSONObject o = new JSONObject();
        try {
            o.put("has", has()); o.put("pkg", pkg); o.put("label", label); o.put("fields", fieldCount()); o.put("builtin", builtin);
            o.put("access", CanWatchService.self != null);
            boolean hcn = false; try { hcn = app != null && app.getPackageManager().getLaunchIntentForPackage(CanWatchService.HCN) != null; } catch (Exception ignored) {}
            o.put("hcn", hcn);
            o.put("gestures", Build.VERSION.SDK_INT >= 24);
        } catch (Exception ignored) {}
        return o.toString();
    }

    private static boolean exported(Context c, String p, String cls) {
        try { ActivityInfo a = c.getPackageManager().getActivityInfo(new ComponentName(p, cls), 0); return a.exported && a.enabled; } catch (Exception e) { return false; }
    }
    /** ouverture de l'appli CAN ; fresh : repart de son premier écran (le chemin enregistré part de là), sinon elle
        reprend là où elle était, ou s'ouvre directement sur l'écran des infos quand c'est un écran à part (« act ») */
    static Intent launchIntent(Context c, String p, boolean fresh) {
        Intent i = null;
        if (!fresh && p.equals(pkg) && !act.isEmpty() && exported(c, p, act)) i = new Intent(Intent.ACTION_MAIN).setClassName(p, act);
        if (i == null) { try { i = c.getPackageManager().getLaunchIntentForPackage(p); } catch (Exception e) { return null; } }
        if (i == null) return null;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | (fresh ? Intent.FLAG_ACTIVITY_CLEAR_TASK : 0));
        if (!fresh && p.equals(CanWatchService.HCN)) {        // CarCan : son extra connu ouvre directement la page des infos
            i.putExtra("ToFragment", "com.hcn.autocan.SimpleSoft.VW.VWInfoFragment");
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        return i;
    }

    /* =============================== liste des applis =============================== */
    private static final Pattern CANISH = Pattern.compile("can|car ?info|carinfo|vehicle|v[ée]hicule|voiture|\\bcar\\b|bus|raise|hiworld|simple ?soft|xinbas|obd|trip|车|原车", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_CAN = Pattern.compile("carplay|zlink|autokit|tlink|carlink|easyconn|speedplay|android ?auto|carbit|cancel|scan|canva|candy|launcher|settings|r[ée]glages|param[èe]tres", Pattern.CASE_INSENSITIVE);
    static String listApps(Context c) {
        JSONArray sug = new JSONArray(), rest = new JSONArray();
        try {
            PackageManager pm = c.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_LAUNCHER);
            java.util.Set<String> seen = new java.util.HashSet<>();
            java.util.TreeMap<String, JSONObject> a = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER), b = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                String p = r.activityInfo.packageName;
                if (p.equals(c.getPackageName()) || !seen.add(p)) continue;
                String l = String.valueOf(r.loadLabel(pm));
                boolean s = (CANISH.matcher(p).find() || CANISH.matcher(l).find()) && !NOT_CAN.matcher(p).find() && !NOT_CAN.matcher(l).find();
                JSONObject o = new JSONObject(); o.put("pkg", p); o.put("label", l); o.put("sug", s);
                (s ? a : b).put(l + "\u0000" + p, o);
            }
            for (JSONObject o : a.values()) sug.put(o);
            for (JSONObject o : b.values()) rest.put(o);
            for (int k = 0; k < rest.length(); k++) sug.put(rest.get(k));
        } catch (Exception e) { Hub.log("APPLI CAN : liste des applis : " + e); }
        return sug.toString();
    }

    /* =============================== enregistrement guidé =============================== */
    static volatile boolean recording = false;
    private static String recPkg = "", recLabel = "", rootAct = "", curAct = "";
    private static final List<JSONObject> recSeq = new ArrayList<>();
    private static final List<List<ScreenRead.Item>> recItems = new ArrayList<>();
    private static final List<List<ScreenRead.Cand>> recCands = new ArrayList<>();
    private static final List<String> recActs = new ArrayList<>();
    private static int recW = 1024, recH = 600, taps, effective, fails;
    private static boolean usedFree;
    private static volatile String pendingJson = "";
    private static String lastDump = "";
    /* deux fenêtres : la barre (toujours touchable) et, dessous, une fenêtre transparente qui reçoit les touchers du client
       sur le reste de l'écran. Elles ne se chevauchent pas : la fenêtre transparente peut être retirée puis remise sans
       passer par-dessus la barre. */
    private static FrameLayout barV, catchV;
    private static WindowManager.LayoutParams barLp, catchLp;
    private static TextView rStatus, freeBtn;
    private static boolean catchOn, free, rBusy, rBottom;
    private static int barH, tapGen;
    private static float dX, dY; private static long dT;
    private static List<String> freePrev;

    private static float dp(Context c, float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()); }
    private static String err(String code, String msg) { try { return new JSONObject().put("ok", false).put("error", code).put("msg", msg).toString(); } catch (Exception e) { return "{}"; } }
    private static double r4(double v) { return Math.round(v * 10000) / 10000.0; }
    private static WindowManager wm(Context c) { return (WindowManager) c.getSystemService(Context.WINDOW_SERVICE); }

    /** démarre l'enregistrement : barre par-dessus, puis ouverture de l'appli CAN choisie (depuis son premier écran) */
    static String start(final Context c, final String p) {
        init(c);
        final CanWatchService svc = CanWatchService.self;
        if (svc == null) return err("access", "Autorise d'abord « Lecture CAN (accessibilité) »");
        if (CanWatchService.refreshUntil > 0) return err("busy", "Une lecture est en cours, réessaie dans quelques secondes");
        final Intent i = launchIntent(c, p, true);
        if (i == null) return err("launch", "Cette appli ne s'ouvre pas depuis le lanceur");
        recPkg = p; rootAct = i.getComponent() == null ? "" : i.getComponent().getClassName(); curAct = rootAct;
        try { recLabel = String.valueOf(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(p, 0))); } catch (Exception e) { recLabel = p; }
        recSeq.clear(); recItems.clear(); recCands.clear(); recActs.clear(); pendingJson = ""; rBusy = false; free = false; usedFree = false;
        taps = 0; effective = 0; fails = 0; freePrev = null; tapGen++;
        int[] wh = svc.screen(); recW = wh[0]; recH = wh[1];
        H.post(new Runnable() { public void run() {
            if (!showRecorder(svc)) { Hub.emit("toast", JSONObject.quote("Impossible d'afficher la barre de capture sur ce poste")); return; }
            recording = true;
            if (Build.VERSION.SDK_INT < 24) { free = true; usedFree = true; }   // pas de toucher simulé : le client touche l'appli lui-même
            Hub.log("APPLI CAN : apprentissage démarré sur " + recPkg + " (" + recLabel + "), écran " + recW + "x" + recH + (free ? ", Android < 7 : touchers suivis par leurs clics" : ""));
            try { c.startActivity(i); } catch (Exception e) { Hub.log("APPLI CAN : ouverture impossible : " + e); finish(true); return; }
            if (free) H.postDelayed(freePoll, 1200);
        }});
        return "{\"ok\":true}";
    }

    private static TextView button(Context c, String t, int bg, View.OnClickListener l) {
        TextView b = new TextView(c);
        b.setText(t); b.setTextColor(Color.WHITE); b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15); b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER); int ph = (int) dp(c, 14); b.setPadding(ph, 0, ph, 0); b.setSingleLine(true);
        GradientDrawable g = new GradientDrawable(); g.setColor(bg); g.setCornerRadius(dp(c, 14)); b.setBackground(g);
        b.setOnClickListener(l);
        return b;
    }

    private static boolean showRecorder(final CanWatchService svc) {
        hideRecorder();
        try {
            final boolean gestures = Build.VERSION.SDK_INT >= 24;
            barH = (int) dp(svc, 72);
            barV = new FrameLayout(svc);
            LinearLayout bar = new LinearLayout(svc);
            bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
            int pad = (int) dp(svc, 6); bar.setPadding((int) dp(svc, 14), pad, pad, pad);
            GradientDrawable bg = new GradientDrawable(); bg.setColor(0xF01C1C1E); bg.setCornerRadius(dp(svc, 20)); bg.setStroke((int) Math.max(1, dp(svc, 1)), 0x40FFFFFF);
            bar.setBackground(bg); bar.setClickable(true);
            TextView st = new TextView(svc);
            st.setTextColor(Color.WHITE); st.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14); st.setMaxLines(2);
            st.setText(gestures ? "Va sur la page des infos du véhicule (un toucher à la fois), puis « Capturer »"
                                : "Touche l'appli toi-même jusqu'à la page des infos du véhicule, puis « Capturer »");
            bar.addView(st, new LinearLayout.LayoutParams(0, -2, 1f));
            int bh = (int) dp(svc, 46), gap = (int) dp(svc, 6);
            LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(-2, bh); lb.leftMargin = gap;
            bar.addView(button(svc, "Capturer", 0xFF0A84FF, new View.OnClickListener() { public void onClick(View v) { capture(); } }), lb);
            lb = new LinearLayout.LayoutParams(-2, bh); lb.leftMargin = gap;
            bar.addView(button(svc, "Terminer", 0xFF30A14E, new View.OnClickListener() { public void onClick(View v) { finish(false); } }), lb);
            if (gestures) {
                lb = new LinearLayout.LayoutParams(-2, bh); lb.leftMargin = gap;
                freeBtn = button(svc, "Main libre", 0xFF48484A, new View.OnClickListener() { public void onClick(View v) { setFree(!free, null); } });
                bar.addView(freeBtn, lb);
            }
            lb = new LinearLayout.LayoutParams(-2, bh); lb.leftMargin = gap;
            bar.addView(button(svc, "⇅", 0xFF48484A, new View.OnClickListener() { public void onClick(View v) { rBottom = !rBottom; place(true); } }), lb);
            lb = new LinearLayout.LayoutParams(-2, bh); lb.leftMargin = gap;
            bar.addView(button(svc, "✕", 0xFF48484A, new View.OnClickListener() { public void onClick(View v) { finish(true); } }), lb);
            int m = (int) dp(svc, 8);
            FrameLayout.LayoutParams lpb = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL); lpb.setMargins(m, 0, m, 0);
            barV.addView(bar, lpb);
            rStatus = st;
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            barLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, barH, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    flags | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
            barLp.gravity = Gravity.TOP | Gravity.START; barLp.windowAnimations = 0;
            if (gestures) {
                catchV = new FrameLayout(svc);
                catchV.setOnTouchListener(new View.OnTouchListener() { @Override public boolean onTouch(View v, MotionEvent e) {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { dX = e.getRawX(); dY = e.getRawY(); dT = android.os.SystemClock.uptimeMillis(); }
                    else if (e.getActionMasked() == MotionEvent.ACTION_UP) onGesture(dX, dY, e.getRawX(), e.getRawY(), android.os.SystemClock.uptimeMillis() - dT);
                    return true;
                }});
                catchLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, Math.max(1, recH - barH), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        flags, PixelFormat.TRANSLUCENT);
                catchLp.gravity = Gravity.TOP | Gravity.START; catchLp.windowAnimations = 0;
            }
            place(false);
            wm(svc).addView(barV, barLp);
            if (gestures) addCatcher();
            return true;
        } catch (Throwable e) { Hub.log("APPLI CAN : barre de capture : " + e); hideRecorder(); return false; }
    }
    private static void place(boolean update) {
        if (barLp == null) return;
        barLp.y = rBottom ? recH - barH : 0;
        if (catchLp != null) { catchLp.y = rBottom ? 0 : barH; catchLp.height = Math.max(1, recH - barH); }
        if (!update) return;
        try {
            if (barV != null && barV.isAttachedToWindow()) wm(barV.getContext()).updateViewLayout(barV, barLp);
            if (catchV != null && catchOn) wm(catchV.getContext()).updateViewLayout(catchV, catchLp);
        } catch (Throwable ignored) {}
    }
    private static void addCatcher() {
        if (catchV == null || catchOn || free || !recording && barV == null) return;
        try { wm(catchV.getContext()).addView(catchV, catchLp); catchOn = true; } catch (Throwable e) { Hub.log("APPLI CAN : fenêtre de capture : " + e); }
    }
    /** retire la fenêtre transparente : le toucher simulé qui suit atteint l'appli à coup sûr (retrait immédiat, synchrone) */
    private static void removeCatcher() {
        if (catchV == null || !catchOn) return;
        try { wm(catchV.getContext()).removeViewImmediate(catchV); } catch (Throwable ignored) {}
        catchOn = false;
    }
    private static void hideRecorder() {
        removeCatcher();
        View v = barV; barV = null; catchV = null; barLp = null; catchLp = null; rStatus = null; freeBtn = null;
        if (v != null) try { wm(v.getContext()).removeViewImmediate(v); } catch (Throwable ignored) {}
        H.removeCallbacks(freePoll);
    }
    private static void status(String s) { if (rStatus != null) rStatus.setText(s); }

    /** main libre : le client touche l'appli lui-même ; on note seulement les changements de page */
    private static void setFree(boolean on, String why) {
        CanWatchService svc = CanWatchService.self;
        if (!recording || svc == null) return;
        free = on;
        H.removeCallbacks(freePoll);
        if (on) {
            usedFree = true; removeCatcher(); rBusy = false;
            freePrev = ScreenRead.anchors(svc.itemsOf(recPkg), 16);
            H.postDelayed(freePoll, 500);
            status(why != null ? why : "Main libre : touche l'appli toi-même jusqu'à la page des infos, puis « Capturer ». Le lanceur retrouvera le chemin.");
            Hub.log("APPLI CAN : main libre" + (why != null ? " (automatique)" : ""));
        } else {
            addCatcher();
            status("Touchers enregistrés : un toucher à la fois, puis « Capturer » sur la page des infos.");
            Hub.log("APPLI CAN : retour aux touchers enregistrés");
        }
        if (freeBtn != null) freeBtn.setText(on ? "Enregistrer" : "Main libre");
    }
    private static final Runnable freePoll = new Runnable() { public void run() {
        CanWatchService svc = CanWatchService.self;
        if (!recording || !free || svc == null) return;
        try {
            List<String> cur = ScreenRead.anchors(svc.itemsOf(recPkg), 16);
            if (!cur.isEmpty()) {
                if (freePrev != null && !freePrev.isEmpty() && ScreenRead.pageChanged(freePrev, cur)) {
                    List<String> to = new ArrayList<>(); for (String t : cur) if (!freePrev.contains(t) && to.size() < 12) to.add(t);
                    JSONObject s = new JSONObject().put("t", "nav").put("from", new JSONArray(freePrev)).put("to", new JSONArray(to));
                    recSeq.add(s);
                    Hub.log("APPLI CAN : changement de page noté (main libre) " + freePrev + " -> " + to);
                }
                freePrev = cur;
            }
        } catch (Throwable e) { Hub.log("APPLI CAN : main libre : " + e); }
        H.postDelayed(this, 450);
    }};

    /** l'appli enregistrée affiche un nouvel écran (activité) */
    static void onWindowState(CharSequence p, CharSequence cls) {
        if (!recording || p == null || cls == null || !recPkg.contentEquals(p)) return;
        String c = cls.toString();
        try { app.getPackageManager().getActivityInfo(new ComponentName(recPkg, c), 0); curAct = c; }   // vraie activité (pas une boîte de dialogue)
        catch (Exception ignored) {}
    }

    /** texte repère d'un toucher : celui qui est sous le doigt, sinon le plus proche (case « image + nom » d'un menu) */
    private static void anchor(JSONObject step, List<ScreenRead.Item> items, double x, double y) throws Exception {
        ScreenRead.Item best = null; double bd = Double.MAX_VALUE;
        for (ScreenRead.Item it : items) {
            String t = it.text == null ? "" : it.text.trim();
            if (t.length() < 2 || t.length() > 40 || t.matches(".*\\d.*") || ScreenRead.isHole(t)) continue;   // pas de repère qui change (heure, valeur)
            boolean in = x >= it.l && x <= it.r && y >= it.t && y <= it.b;
            double d = in ? 0 : Math.hypot((it.cx() - x) / recW, (it.cy() - y) / recH);
            if (d < bd) { bd = d; best = it; }
        }
        if (best == null || bd > 0.16) return;
        String n = ScreenRead.norm(best.text);
        int same = 0; for (ScreenRead.Item it : items) if (ScreenRead.norm(it.text).equals(n)) same++;
        if (n.isEmpty() || same > 2) return;
        step.put("txt", n); step.put("tx", r4(best.cx() / recW)); step.put("ty", r4(best.cy() / recH));
        step.put("ox", r4((x - best.cx()) / recW)); step.put("oy", r4((y - best.cy()) / recH));
    }

    /** toucher ou glissé du client sur la fenêtre transparente : noté, rejoué dans l'appli, puis vérifié */
    private static void onGesture(final float x0, final float y0, final float x1, final float y1, long ms) {
        final CanWatchService svc = CanWatchService.self;
        if (!recording || rBusy || free || svc == null || barV == null) return;
        rBusy = true;
        final int my = ++tapGen;
        final boolean swipe = Math.hypot(x1 - x0, y1 - y0) > dp(svc, 24);
        final int dur = (int) Math.max(60, Math.min(swipe ? 500 : 600, ms));
        final JSONObject s = new JSONObject();
        List<ScreenRead.Item> before = svc.itemsOf(recPkg);
        final String sigB = ScreenRead.sig(before);
        try {
            s.put("t", swipe ? "swipe" : "tap"); s.put("x", r4(x0 / recW)); s.put("y", r4(y0 / recH)); s.put("d", dur);
            if (swipe) { s.put("x2", r4(x1 / recW)); s.put("y2", r4(y1 / recH)); }
            else anchor(s, before, x0, y0);
            s.put("on", new JSONArray(ScreenRead.anchors(before, 12)));
            recSeq.add(s); taps++;
        } catch (Throwable e) { Hub.log("APPLI CAN : toucher non noté : " + e); }
        status("Toucher transmis à l'appli…");
        removeCatcher();
        H.postDelayed(new Runnable() { public void run() {
            if (my != tapGen || !recording) return;
            boolean ok = svc.gesture(x0, y0, swipe ? x1 : x0, swipe ? y1 : y0, dur, new Runnable() { public void run() {
                H.postDelayed(new Runnable() { public void run() { check(svc, my, s, sigB, x0, y0, swipe, 0, 0); } }, 150);
            }});
            if (!ok) check(svc, my, s, sigB, x0, y0, swipe, 0, 99);
        }}, 90);
        H.postDelayed(new Runnable() { public void run() { if (my == tapGen && rBusy) tapDone(s, false, "pas de réponse"); } }, 7000);   // filet de sécurité
    }
    /** la page a-t-elle changé ? stage 0 : après le toucher simulé ; stage 1 : après le clic d'accessibilité de secours */
    private static void check(final CanWatchService svc, final int my, final JSONObject s, final String sigB, final float x, final float y, final boolean swipe, final int stage, final int tries) {
        if (my != tapGen || !recording) return;
        List<ScreenRead.Item> now = svc.itemsOf(recPkg);
        boolean changed = !ScreenRead.sig(now).equals(sigB);
        if (changed) {
            try { if (stage == 1) s.put("click", true); } catch (Exception ignored) {}
            tapDone(s, true, stage == 1 ? "par clic d'accessibilité" : "toucher simulé"); return;
        }
        if (tries < 6) { H.postDelayed(new Runnable() { public void run() { check(svc, my, s, sigB, x, y, swipe, stage, tries + 1); } }, 200); return; }
        if (stage == 0 && !swipe && svc.clickAt(recPkg, (int) x, (int) y)) {
            Hub.log("APPLI CAN : toucher simulé sans effet, essai par clic d'accessibilité");
            H.postDelayed(new Runnable() { public void run() { check(svc, my, s, sigB, x, y, swipe, 1, 0); } }, 150);
            return;
        }
        tapDone(s, false, stage == 0 && tries == 99 ? "toucher simulé refusé" : "page inchangée");
    }
    private static void tapDone(JSONObject s, boolean ok, String how) {
        if (!rBusy) return;
        try { s.put("fx", ok); } catch (Exception ignored) {}
        Hub.log("APPLI CAN : toucher " + (ok ? "transmis, la page a changé (" + how + ")" : "sans effet (" + how + ")") + " " + s.optString("txt"));
        if (ok) { effective++; fails = 0; status("Toucher transmis ✓  Continue jusqu'à la page des infos, puis « Capturer »."); }
        else { fails++; status("Ce toucher n'a rien changé dans l'appli. Réessaie un peu à côté, ou touche « Main libre »."); }
        H.postDelayed(new Runnable() { public void run() {
            rBusy = false;
            if (!recording) return;
            if (!ok && fails >= 2 && effective == 0) {
                Hub.log("APPLI CAN : touchers simulés sans effet sur cette appli, passage en main libre");
                setFree(true, "Les touchers simulés ne passent pas dans cette appli : main libre activée. Touche-la toi-même jusqu'à la page des infos, puis « Capturer ».");
            } else addCatcher();
        }}, 120);
    }

    /** Android < 7 : pas de toucher simulé ; le client touche l'appli directement et on note ses clics */
    static void onClicked(AccessibilityEvent e) {
        if (!recording || Build.VERSION.SDK_INT >= 24 || e.getPackageName() == null || !recPkg.contentEquals(e.getPackageName())) return;
        try {
            AccessibilityNodeInfo n = e.getSource();
            android.graphics.Rect r = new android.graphics.Rect();
            if (n != null) n.getBoundsInScreen(r);
            String t = e.getText() == null || e.getText().isEmpty() ? "" : String.valueOf(e.getText().get(0));
            if (t.isEmpty() && e.getContentDescription() != null) t = e.getContentDescription().toString();
            JSONObject s = new JSONObject();
            s.put("t", "tap"); s.put("click", true); s.put("d", 80);
            s.put("x", r4(r.isEmpty() ? 0.5 : r.centerX() / (double) recW)); s.put("y", r4(r.isEmpty() ? 0.5 : r.centerY() / (double) recH));
            String nt = ScreenRead.norm(t);
            if (!nt.isEmpty() && nt.length() <= 40) { s.put("txt", nt); s.put("tx", s.getDouble("x")); s.put("ty", s.getDouble("y")); }
            if (freePrev != null) s.put("on", new JSONArray(freePrev));
            recSeq.add(s); taps++; effective++;
            Hub.log("APPLI CAN : clic noté " + s);
        } catch (Throwable ex) { Hub.log("APPLI CAN : clic non noté : " + ex); }
    }

    /** « Capturer » : relève la page affichée par l'appli CAN */
    private static void capture() {
        CanWatchService svc = CanWatchService.self;
        if (!recording || svc == null) return;
        try {
            List<ScreenRead.Item> items = svc.itemsOf(recPkg);
            List<ScreenRead.Cand> cs = ScreenRead.extract(items);
            int known = 0; StringBuilder names = new StringBuilder();
            for (ScreenRead.Cand c : cs) if (c.key != null) { known++; if (names.length() < 60) names.append(names.length() > 0 ? ", " : "").append(c.label.isEmpty() ? c.key : c.label); }
            StringBuilder dump = new StringBuilder("APPLI CAN : capture page " + (recItems.size() + 1) + " de " + recPkg + " (" + curAct + ") : " + items.size() + " textes, " + cs.size() + " valeurs, " + known + " reconnues");
            int n = 0; for (ScreenRead.Item it : items) { if (n++ >= 70) break; dump.append("\n    [").append(it.id).append("] « ").append(it.text).append(" » ").append(it.l).append(',').append(it.t).append('-').append(it.r).append(',').append(it.b).append(" p").append(it.p1); }
            Hub.log(dump.toString()); lastDump = dump.toString();
            if (free) freePrev = ScreenRead.anchors(items, 16);
            if (cs.isEmpty()) {
                status(items.isEmpty() ? "Aucun texte lisible sur cette page : l'appli dessine peut-être ses chiffres en image. Essaie une autre page."
                        : effective == 0 && taps > 0 && !free ? "Pas de valeur ici : l'appli est restée sur cette page. Touche « Main libre » et va toi-même sur la page des infos."
                        : "Pas de valeur sur cette page. Va sur la page des infos (carburant, batterie…) puis « Capturer ».");
                return;
            }
            recItems.add(items); recCands.add(cs); recActs.add(curAct);
            recSeq.add(new JSONObject().put("t", "read").put("page", recItems.size() - 1));
            status("Page " + recItems.size() + " capturée : " + cs.size() + " valeur" + (cs.size() > 1 ? "s" : "") + (known > 0 ? " (" + names + ")" : "")
                    + ". Autre page à capturer ? Sinon « Terminer ».");
        } catch (Throwable e) { Hub.log("APPLI CAN : capture : " + e); status("Capture impossible : " + e.getClass().getSimpleName()); }
    }

    /** fin de l'enregistrement : retour au lanceur, qui affiche les valeurs trouvées (événement « canlearn ») */
    static void finish(boolean cancel) {
        if (!recording && barV == null) return;
        recording = false; tapGen++;
        hideRecorder();
        try {
            if (cancel || recItems.isEmpty()) {
                pendingJson = cancel ? "" : new JSONObject().put("pkg", recPkg).put("label", recLabel).put("pages", new JSONArray())
                        .put("taps", taps).put("fx", effective).put("free", usedFree).toString();
                Hub.log("APPLI CAN : apprentissage " + (cancel ? "annulé" : "terminé sans page capturée") + " (" + taps + " toucher(s), " + effective + " transmis" + (usedFree ? ", main libre utilisée" : "") + ")");
            } else {
                JSONObject o = new JSONObject(); JSONArray pages = new JSONArray();
                for (int p = 0; p < recCands.size(); p++) {
                    JSONArray a = new JSONArray(); List<ScreenRead.Cand> cs = recCands.get(p);
                    for (int i = 0; i < cs.size() && i < 40; i++) {
                        ScreenRead.Cand c = cs.get(i); JSONObject x = new JSONObject();
                        x.put("i", i); x.put("label", c.label); x.put("value", c.raw); x.put("unit", c.unit); x.put("key", c.key == null ? JSONObject.NULL : c.key);
                        x.put("text", c.num == null && !ScreenRead.isHole(c.raw));
                        a.put(x);
                    }
                    pages.put(new JSONObject().put("cands", a));
                }
                o.put("pkg", recPkg); o.put("label", recLabel); o.put("pages", pages); o.put("taps", taps); o.put("fx", effective); o.put("free", usedFree);
                pendingJson = o.toString();
                Hub.log("APPLI CAN : apprentissage terminé, " + recCands.size() + " page(s), " + taps + " toucher(s) dont " + effective + " transmis" + (usedFree ? ", main libre utilisée" : ""));
            }
        } catch (Exception e) { Hub.log("APPLI CAN : fin : " + e); pendingJson = ""; }
        Context c = app != null ? app : Hub.app;
        if (c == null) return;
        if (!Hub.launcherVisible) {
            Intent h = new Intent(c, MainActivity.class);
            h.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            h.putExtra("ibz_return", true); CanWatchService.returning = true;
            try { c.startActivity(h); } catch (Exception ignored) {}
        }
        if (!pendingJson.isEmpty()) Hub.emit("canlearn", pendingJson);
    }
    /** résultat en attente pour l'interface (une seule fois) */
    static String takePending() { String p = pendingJson; pendingJson = ""; return p; }

    /** enregistre le profil : mapping = {"pages":[{"<n° de valeur>":"<donnée>", …}, …]} choisi par le client */
    static synchronized String save(String mapping) {
        try {
            if (recItems.isEmpty()) return "rien à enregistrer";
            JSONArray mp = new JSONObject(mapping).getJSONArray("pages");
            JSONArray seq = new JSONArray(); int nf = 0; String firstAct = null;
            for (JSONObject s : recSeq) {
                String t = s.optString("t");
                if (!"read".equals(t)) {
                    if (("tap".equals(t) || "swipe".equals(t)) && !s.optBoolean("fx", true)) continue;   // toucher sans effet : inutile
                    seq.put(s); continue;
                }
                int p = s.getInt("page");
                JSONObject m = p < mp.length() ? mp.optJSONObject(p) : null;
                if (m == null || m.length() == 0) continue;
                List<ScreenRead.Item> items = recItems.get(p); List<ScreenRead.Cand> cs = recCands.get(p);
                JSONArray fs = new JSONArray();
                for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) {
                    String k = it.next(); int i = Integer.parseInt(k); String key = m.optString(k);
                    if (i < 0 || i >= cs.size() || key.isEmpty()) continue;
                    ScreenRead.Field f = ScreenRead.fieldOf(items, cs.get(i), key, recW, recH);
                    JSONObject x = new JSONObject();
                    x.put("k", f.key); if (!f.id.isEmpty()) { x.put("id", f.id); x.put("ord", f.ord); x.put("same", f.same); }
                    if (!f.lab.isEmpty()) { x.put("lab", f.lab); x.put("rx", r4(f.rx)); x.put("ry", r4(f.ry)); }
                    x.put("px", r4(f.px)); x.put("py", r4(f.py));
                    if (f.inline) x.put("inl", true); if (f.txt) x.put("txt", true); if (!f.u.isEmpty()) x.put("u", f.u);
                    fs.put(x); nf++;
                }
                if (fs.length() > 0) { seq.put(new JSONObject().put("t", "read").put("f", fs)); if (firstAct == null) firstAct = p < recActs.size() ? recActs.get(p) : ""; }
            }
            if (nf == 0) return "aucune donnée choisie";
            JSONObject p = new JSONObject();
            p.put("v", 1); p.put("pkg", recPkg); p.put("label", recLabel);
            try { PackageInfo pi = app.getPackageManager().getPackageInfo(recPkg, 0); p.put("ver", pi.versionName); } catch (Exception ignored) {}
            p.put("dev", Build.MANUFACTURER + " " + Build.MODEL); p.put("w", recW); p.put("h", recH); p.put("at", System.currentTimeMillis());
            // la page des infos est un écran à part, ouvrable directement : plus besoin du chemin (il reste en secours)
            if (firstAct != null && !firstAct.isEmpty() && !firstAct.equals(rootAct) && exported(app, recPkg, firstAct)) p.put("act", firstAct);
            p.put("seq", seq);
            use(p, false);
            app.getSharedPreferences("ibz_canapp", Context.MODE_PRIVATE).edit().putString("profile", p.toString()).apply();
            try { FileOutputStream o = new FileOutputStream(new File(app.getExternalFilesDir(null), "canapp.json")); o.write(p.toString(1).getBytes("UTF-8")); o.close(); } catch (Exception ignored) {}
            Hub.log("APPLI CAN : profil enregistré pour " + recPkg + " : " + nf + " données, " + steps.size() + " étapes" + (p.has("act") ? ", écran des infos ouvert directement (" + firstAct + ")" : ""));
            return "ok";
        } catch (Exception e) { Hub.log("APPLI CAN : enregistrement : " + e); return String.valueOf(e.getMessage()); }
    }

    /** pour le journal envoyé par QR codes : le profil et la dernière capture (de quoi ajouter ce poste à l'appli) */
    static String summary() {
        StringBuilder b = new StringBuilder("=== Appli CAN (ordinateur de bord) ===\n");
        b.append(has() ? "Profil " + (builtin ? "fourni" : "appris") + " : " + profile : "Aucun profil").append('\n');
        if (!lastDump.isEmpty()) b.append(lastDump).append('\n');
        return b.append("=== fin ===\n").toString();
    }

    /* =============================== lecture avec le profil =============================== */
    private static int idx, misses; private static long lastActT; private static boolean restarted, gotValue;
    /* recherche du toucher d'un changement de page vu en main libre : entrées du menu essayées l'une après l'autre */
    private static List<ScreenRead.Item> navC; private static int navI, navPh; private static boolean navPending; private static String navSig = "";
    private static ScreenRead.Item navItem; private static int[] navPt;
    private static void navReset() { navC = null; navI = 0; navPh = 0; navPending = false; navSig = ""; navItem = null; navPt = null; }
    static void runStart() { idx = 0; misses = 0; lastActT = 0; restarted = false; gotValue = false; navReset(); }
    /** durée laissée à une lecture automatique (selon le chemin) */
    static long budget() {
        int taps = 0, navs = 0; for (Step s : steps) { if (s.nav) navs++; else if (!s.read) taps++; }
        return Math.min(42000, 8000 + taps * 2200L + navs * 16000L);
    }
    /** un toucher du chemin n'a ni texte repère ni page de départ connue (ancien profil, menu en images seules) :
        l'appli doit alors partir de son premier écran, sinon le toucher tomberait au hasard */
    static boolean blindTaps() { for (Step s : steps) if (!s.read && !s.nav && !s.swipe && s.txt.isEmpty() && s.on.isEmpty()) return true; return false; }

    private static int need(Step s) { return Math.max(1, (s.fields.size() + 1) / 2); }
    private static int readPage(List<ScreenRead.Item> items, Step s, int W, int Hh, JSONObject out) {
        int found = 0;
        for (ScreenRead.Field f : s.fields) {
            Object v = ScreenRead.read(items, f, W, Hh);
            if (v == null) continue;
            found++;
            if (v != ScreenRead.FOUND_EMPTY && out != null) try { out.put(f.key, v); } catch (Exception ignored) {}
        }
        return found;
    }
    private static int[] target(Step s, List<ScreenRead.Item> items, int W, int Hh) {
        if (s.swipe || s.txt.isEmpty()) return new int[]{ (int) (s.x * W), (int) (s.y * Hh) };
        ScreenRead.Item best = null; double bd = Double.MAX_VALUE;
        for (ScreenRead.Item it : items) {
            if (!ScreenRead.norm(it.text).equals(s.txt)) continue;
            double d = Math.hypot(it.cx() / W - s.tx, it.cy() / Hh - s.ty);
            if (d < bd) { bd = d; best = it; }
        }
        if (best == null) return null;                    // le repère n'est pas à l'écran : on ne touche pas au hasard
        return new int[]{ (int) (best.cx() + s.ox * W), (int) (best.cy() + s.oy * Hh) };
    }
    private static ScreenRead.Item findText(List<ScreenRead.Item> items, String norm) {
        for (ScreenRead.Item it : items) if (ScreenRead.norm(it.text).equals(norm)) return it;
        return null;
    }

    /** chemin retrouvé : le changement de page devient un toucher ordinaire du profil */
    private static void navResolved(Step s, int W, int Hh) {
        if (navItem == null || navPt == null) return;
        try {
            String n = ScreenRead.norm(navItem.text);
            s.nav = false; s.t = "tap"; s.txt = n; s.tx = navItem.cx() / W; s.ty = navItem.cy() / Hh;
            s.ox = (navPt[0] - navItem.cx()) / W; s.oy = (navPt[1] - navItem.cy()) / Hh; s.x = navPt[0] / (double) W; s.y = navPt[1] / (double) Hh;
            s.click = navPh == 2; s.on = s.from; s.fx = true; s.dur = 80;
            JSONObject o = s.src;
            if (o != null) {
                o.put("t", "tap"); o.put("txt", n); o.put("tx", r4(s.tx)); o.put("ty", r4(s.ty)); o.put("ox", r4(s.ox)); o.put("oy", r4(s.oy));
                o.put("x", r4(s.x)); o.put("y", r4(s.y)); o.put("d", 80); o.put("on", new JSONArray(s.from)); o.put("fx", true); if (s.click) o.put("click", true);
                persist();
            }
            Hub.log("APPLI CAN : chemin retrouvé : « " + n + " » " + (navPh == 0 ? "(toucher sur le nom)" : navPh == 1 ? "(toucher sur l'image au-dessus du nom)" : "(clic d'accessibilité)") + ", enregistré dans le profil");
        } catch (Exception e) { Hub.log("APPLI CAN : chemin retrouvé mais non enregistré : " + e); }
    }

    /** étape « changement de page » : 1 = faite, 0 = en cours (toucher envoyé), -1 = pas sur la bonne page, 2 = rien trouvé */
    private static int navStep(CanWatchService svc, Step s, List<ScreenRead.Item> items, int W, int Hh, long now) {
        if (ScreenRead.overlap(items, s.to) >= 0.5) { if (navPending) navResolved(s, W, Hh); navReset(); return 1; }
        if (navPending) {
            navPending = false;
            if (!ScreenRead.sig(items).equals(navSig) && ScreenRead.overlap(items, s.from) < 0.5) {   // une autre page s'est ouverte : retour
                Hub.log("APPLI CAN : « " + ScreenRead.norm(navItem.text) + " » ouvre une autre page, retour");
                svc.back(); navI++; navPh = 0; lastActT = now + 400; return 0;
            }
            if (++navPh > 2) { navPh = 0; navI++; }
        }
        if (ScreenRead.overlap(items, s.from) < 0.5) return -1;
        if (navC == null) {
            navC = ScreenRead.navCandidates(items, s.from, s.to); navI = 0; navPh = 0;
            StringBuilder b = new StringBuilder(); for (ScreenRead.Item it : navC) b.append(b.length() > 0 ? ", " : "").append(ScreenRead.norm(it.text));
            Hub.log("APPLI CAN : recherche du chemin vers " + s.to + " : essais [" + b + "]");
        }
        while (navI < navC.size()) {
            ScreenRead.Item c = findText(items, ScreenRead.norm(navC.get(navI).text));
            if (c == null) { navI++; navPh = 0; continue; }
            int[] pt;
            if (navPh == 0) pt = new int[]{ (int) c.cx(), (int) c.cy() };
            else if (navPh == 1) { int y = (int) (c.t - 1.6 * c.h()); if (y <= 0) { navPh = 2; continue; } pt = new int[]{ (int) c.cx(), y }; }
            else pt = new int[]{ (int) c.cx(), (int) c.cy() };
            boolean ok = navPh == 2 ? svc.clickAt(pkg, pt[0], pt[1]) : svc.gesture(pt[0], pt[1], pt[0], pt[1], 80, null);
            if (!ok) { if (++navPh > 2) { navPh = 0; navI++; } continue; }
            navItem = c; navPt = pt; navSig = ScreenRead.sig(items); navPending = true; lastActT = now + 350;
            Hub.log("APPLI CAN : essai « " + ScreenRead.norm(c.text) + " » " + (navPh == 0 ? "nom" : navPh == 1 ? "image" : "clic") + " (" + pt[0] + "," + pt[1] + ")");
            return 0;
        }
        Hub.log("APPLI CAN : aucune entrée de la page ne mène à " + s.to);
        return 2;
    }

    static final int GO_ON = 0, DONE = 1, FAILED = 2, RESTART = 3;
    /** avance la lecture automatique d'un cran avec l'écran actuel de l'appli CAN */
    static int drive(CanWatchService svc, List<ScreenRead.Item> items, int W, int Hh) {
        List<Step> st = steps;
        long now = android.os.SystemClock.uptimeMillis();
        if (now < lastActT + 650) return GO_ON;            // laisse la page se dessiner après un toucher
        while (idx < st.size()) {
            Step s = st.get(idx);
            if (s.read) {
                JSONObject vals = new JSONObject();
                if (readPage(items, s, W, Hh, vals) >= need(s)) {
                    if (vals.length() > 0) { gotValue = true; Hub.mergeCar(vals, label); }
                    idx++; misses = 0; continue;
                }
            } else {
                int nr = -1; for (int k = idx + 1; k < st.size(); k++) if (st.get(k).read) { nr = k; break; }
                if (nr >= 0 && readPage(items, st.get(nr), W, Hh, null) >= need(st.get(nr))) {   // déjà sur la page
                    if (s.nav && navPending) navResolved(s, W, Hh);
                    navReset(); idx = nr; continue;
                }
                if (s.nav) {
                    int r = navStep(svc, s, items, W, Hh, now);
                    if (r == 1) { idx++; misses = 0; continue; }
                    if (r == 0) return GO_ON;
                    if (r == 2) return FAILED;
                } else if (s.on.isEmpty() || ScreenRead.overlap(items, s.on) >= 0.5) {
                    int[] pt = target(s, items, W, Hh);
                    if (pt != null) {
                        boolean ok;
                        if (s.click) ok = Build.VERSION.SDK_INT < 24 && !s.txt.isEmpty() ? svc.clickText(pkg, s.txt) : svc.clickAt(pkg, pt[0], pt[1]);
                        else ok = svc.gesture(pt[0], pt[1], s.swipe ? (float) (s.x2 * W) : pt[0], s.swipe ? (float) (s.y2 * Hh) : pt[1], s.dur, null);
                        if (ok) { Hub.log("APPLI CAN : " + (s.click ? "clic" : s.swipe ? "glissé" : "toucher") + (s.txt.isEmpty() ? "" : " « " + s.txt + " »") + " (" + pt[0] + "," + pt[1] + ")"); idx++; misses = 0; lastActT = now; return GO_ON; }
                    }
                }
            }
            if (++misses < 4) return GO_ON;
            if (!restarted) { restarted = true; idx = 0; misses = 0; navReset(); lastActT = now + 900; Hub.log("APPLI CAN : écran inattendu, l'appli est relancée depuis son premier écran"); return RESTART; }
            Hub.log("APPLI CAN : " + (s.read ? "page des infos introuvable" : s.nav ? "page de départ du chemin introuvable" : "repère « " + s.txt + " » introuvable") + " : l'appli a peut-être changé, refais « Apprendre l'ordinateur de bord »");
            return FAILED;
        }
        if (!gotValue) Hub.log("APPLI CAN : page atteinte mais aucune valeur affichée (contact coupé ?)");
        return DONE;
    }

    /** l'appli CAN est à l'écran (ouverte par le client) : on lit ce qui est visible, en direct */
    static void passive(List<ScreenRead.Item> items, int W, int Hh) {
        for (Step s : steps) {
            if (!s.read) continue;
            JSONObject vals = new JSONObject();
            if (readPage(items, s, W, Hh, vals) >= need(s) && vals.length() > 0) Hub.mergeCar(vals, label);
        }
    }

    /** appli « voiture » sans profil : valeurs reconnues toutes seules par leurs libellés (au moins deux pour y croire) */
    static JSONObject auto(List<ScreenRead.Item> items) {
        JSONObject out = new JSONObject();
        try {
            for (ScreenRead.Cand c : ScreenRead.extract(items)) {
                if (c.key == null) continue;
                if (ScreenRead.TEXT_KEYS.contains(c.key)) { out.put(c.key, c.raw); continue; }
                if (c.num == null) continue;
                out.put(c.key, ScreenRead.convert(c.key, c.num, ScreenRead.unitClass(c.unit)));
            }
        } catch (Throwable ignored) {}
        return out;
    }
}
