package fr.ibiza.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

/**
 * v1.12 — alerte plein écran « ouvrant ouvert en roulant » (au-delà de 5 km/h), par-dessus toutes les apps (CarPlay compris).
 * Vitesse : CAN si le poste la publie, sinon GPS. Bips courts au début, puis alerte visuelle tant que ça dure.
 * « OK » la fait taire jusqu'à ce qu'un autre ouvrant s'ouvre, que tout soit fermé ou que la voiture s'arrête.
 * Fenêtre du service d'accessibilité (aucune autorisation en plus) ; à défaut, fenêtre « par-dessus les apps ».
 */
final class MovingAlert {
    static volatile boolean enabled = true;
    private static final String[] KEYS = {"fl", "fr", "rl", "rr", "trunk", "hood"};
    private static final String[] NAMES = {"Porte avant gauche", "Porte avant droite", "Porte arrière gauche", "Porte arrière droite", "Coffre", "Capot"};
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static FrameLayout view;
    private static TextView title, sub;
    private static final ImageView[] layers = new ImageView[6];
    private static boolean shown = false;
    private static String muted = null, sigShown = "";
    private static long firstSeen = 0;
    private static int beeps = 0;
    private static ToneGenerator tone;
    private static long testUntil = 0;

    private MovingAlert() {}

    static boolean shown() { return shown; }

    /** appelé toutes les 0,5 s (fil des propriétés CEM) */
    static void check(boolean[] d, int kmh) {
        if (System.currentTimeMillis() < testUntil) return;      // test en cours depuis les réglages
        boolean any = false; StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) if (d[i]) { any = true; sb.append(KEYS[i]).append(','); }
        final String sig = sb.toString();
        if (!enabled) { if (shown) post(null, 0, null); return; }
        if (!any) { muted = null; firstSeen = 0; if (shown) post(null, 0, null); return; }
        if (kmh <= 2) { muted = null; firstSeen = 0; if (shown) post(null, 0, null); return; }   // arrêté : ouvrir est normal
        if (kmh < 5) { if (shown) post(d.clone(), kmh, sig); return; }
        long now = System.currentTimeMillis();
        if (firstSeen == 0) firstSeen = now;
        if (now - firstSeen < 900) return;                        // ignore un rebond de capteur
        if (muted != null) { if (muted.equals(sig) || isSubset(sig, muted)) return; muted = null; }
        post(d.clone(), kmh, sig);
    }
    private static boolean isSubset(String a, String b) { for (String k : a.split(",")) if (k.length() > 0 && !b.contains(k + ",")) return false; return true; }

    /** test depuis les réglages : porte avant droite à 32 km/h pendant 8 s */
    static void test() {
        testUntil = System.currentTimeMillis() + 8000; muted = null;
        boolean[] d = new boolean[6]; d[1] = true;
        post(d, 32, "fr,");
        H.postDelayed(new Runnable() { public void run() { testUntil = 0; hide(); } }, 8000);
    }

    private static void post(final boolean[] d, final int kmh, final String sig) {
        H.post(new Runnable() { public void run() { if (d == null) hide(); else show(d, kmh, sig); } });
    }

    private static float dp(Context c, float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()); }
    /** v1.24.1 : vue du dessus du pack voiture actif (avant : toujours celle de l'appli) */
    private static Bitmap asset(Context c, String name) { return Packs.topBitmap(c, name); }
    private static int imgGen = -1;

    private static Context windowContext() {
        if (CanWatchService.self != null) return CanWatchService.self;
        return Hub.app;
    }

    private static void build(final Context c) {
        view = new FrameLayout(c);
        view.setBackgroundColor(0xE6140606);
        // liseré rouge qui pulse
        final View edge = new View(c);
        GradientDrawable eb = new GradientDrawable(); eb.setColor(Color.TRANSPARENT); eb.setStroke((int) dp(c, 6), 0xFFFF3B30);
        edge.setBackground(eb);
        view.addView(edge, new FrameLayout.LayoutParams(-1, -1));
        edge.animate().alpha(0.25f).setDuration(600).withEndAction(new Runnable() { public void run() {
            if (view == null || edge.getParent() == null) return;
            edge.animate().alpha(edge.getAlpha() < 0.5f ? 1f : 0.25f).setDuration(600).withEndAction(this).start();
        }}).start();

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) dp(c, 24); col.setPadding(pad, pad, pad, pad);

        TextView warn = new TextView(c);
        warn.setText("!"); warn.setTextColor(Color.WHITE); warn.setGravity(Gravity.CENTER);
        warn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 34); warn.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        GradientDrawable wb = new GradientDrawable(); wb.setShape(GradientDrawable.OVAL); wb.setColor(0xFFFF3B30);
        warn.setBackground(wb);
        int ws = (int) dp(c, 58);
        col.addView(warn, new LinearLayout.LayoutParams(ws, ws));

        title = new TextView(c);
        title.setTextColor(Color.WHITE); title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 32); title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams lt = new LinearLayout.LayoutParams(-2, -2); lt.topMargin = (int) dp(c, 12);
        col.addView(title, lt);

        sub = new TextView(c);
        sub.setTextColor(0xD9FFFFFF); sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19); sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(-2, -2); ls.topMargin = (int) dp(c, 4);
        col.addView(sub, ls);

        FrameLayout car = new FrameLayout(c);
        ImageView base = new ImageView(c); base.setImageBitmap(asset(c, "closed")); base.setScaleType(ImageView.ScaleType.FIT_CENTER);
        car.addView(base, new FrameLayout.LayoutParams(-1, -1));
        for (int i = 0; i < 6; i++) {
            layers[i] = new ImageView(c); layers[i].setImageBitmap(asset(c, KEYS[i])); layers[i].setScaleType(ImageView.ScaleType.FIT_CENTER); layers[i].setAlpha(0f);
            car.addView(layers[i], new FrameLayout.LayoutParams(-1, -1));
        }
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(-1, 0, 1f); lc.topMargin = (int) dp(c, 10); lc.bottomMargin = (int) dp(c, 10);
        col.addView(car, lc);

        TextView ok = new TextView(c);
        ok.setText("OK"); ok.setTextColor(Color.WHITE); ok.setGravity(Gravity.CENTER);
        ok.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20); ok.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        GradientDrawable ob = new GradientDrawable(); ob.setColor(0x33FFFFFF); ob.setCornerRadius(dp(c, 28)); ob.setStroke((int) Math.max(1, dp(c, 1)), 0x55FFFFFF);
        ok.setBackground(ob);
        col.addView(ok, new LinearLayout.LayoutParams((int) Math.min(dm.widthPixels * 0.4f, dp(c, 260)), (int) dp(c, 56)));
        ok.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            muted = sigShown; testUntil = 0; hide();
        }});
        view.addView(col, new FrameLayout.LayoutParams(-1, -1));
        view.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { /* absorbe les touchers */ } });
    }

    private static void show(boolean[] d, int kmh, String sig) {
        Context c = windowContext();
        if (c == null) return;
        try {
            int n = 0, last = -1; StringBuilder names = new StringBuilder();
            for (int i = 0; i < 6; i++) if (d[i]) { n++; last = i; if (names.length() > 0) names.append(", "); names.append(NAMES[i].toLowerCase(java.util.Locale.FRANCE)); }
            if (n == 0) { hide(); return; }
            if (view != null && !shown && imgGen != Packs.gen) view = null;   // v1.24.1 : voiture changée -> reconstruite
            if (view == null) { imgGen = Packs.gen; build(c); }
            title.setText(n == 1 ? NAMES[last] + (last >= 4 ? " ouvert" : " ouverte") : n + " ouvrants ouverts");
            sub.setText((n > 1 ? cap(names.toString()) + "\n" : "") + "Tu roules à " + kmh + " km/h : arrête-toi dès que possible.");
            for (int i = 0; i < 6; i++) if (layers[i] != null) layers[i].setAlpha(d[i] ? 1f : 0f);
            if (!sig.equals(sigShown)) { beeps = 0; sigShown = sig; }
            if (!shown) {
                boolean acc = c instanceof CanWatchService;
                if (!acc && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) { Hub.log("Alerte en roulant : pas d'autorisation d'affichage"); return; }
                int type = acc ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                        : Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, type,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).addView(view, lp);
                shown = true;
                view.setAlpha(0f); view.animate().alpha(1f).setDuration(180).start();
                DoorOverlay.hide();
                Hub.log("ALERTE : " + title.getText() + " à " + kmh + " km/h");
                H.removeCallbacks(BEEP); H.post(BEEP);
            }
        } catch (Exception e) { Hub.log("Alerte en roulant : " + e); }
    }
    private static String cap(String s) { return s.length() == 0 ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private static final Runnable BEEP = new Runnable() { public void run() {
        if (!shown || beeps >= 6) return;
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 350);
        } catch (Exception e) { Hub.log("bip : " + e); }
        beeps++;
        H.postDelayed(this, 1400);
    }};

    static void hide() {
        H.removeCallbacks(BEEP);
        if (!shown || view == null) return;
        shown = false;
        final View v = view;
        view = null;           // reconstruite à la prochaine alerte (le contexte de fenêtre peut avoir changé)
        v.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() { public void run() {
            try { ((WindowManager) v.getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(v); } catch (Exception ignored) {}
        }}).start();
    }
}
