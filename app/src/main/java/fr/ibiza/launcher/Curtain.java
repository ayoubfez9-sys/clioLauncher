package fr.ibiza.launcher;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * v1.10 — « rideau » pour la lecture de l'ordinateur de bord :
 * une image figée du lanceur est posée par-dessus tout l'écran pendant que CarCan s'ouvre dessous,
 * que le service d'accessibilité touche « CarInfo » et lit les valeurs, puis on revient au lanceur.
 * Fenêtre de type TYPE_ACCESSIBILITY_OVERLAY : par conception, elle ne masque pas les fenêtres situées
 * dessous pour l'accessibilité (l'écran de CarCan reste lisible), et elle ne demande aucune autorisation en plus.
 * Elle absorbe les touchers pour qu'un appui ne tombe pas dans CarCan.
 */
final class Curtain {
    private static View view;
    private static WindowManager.LayoutParams lp;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable SAFETY = new Runnable() { public void run() { Hub.log("Rideau : délai de sécurité"); hide(); } };

    private Curtain() {}

    private static float dp(Context c, float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()); }

    public interface Snap { void done(Bitmap b); }

    /** capture de l'écran du lanceur (PixelCopy : fiable avec la WebView accélérée ; sinon dessin logiciel) */
    static void snapshot(final Activity a, final View content, final Snap cb) {
        try {
            final Window w = a.getWindow();
            View d = w.getDecorView();
            int W = d.getWidth(), Hh = d.getHeight();
            if (W <= 0 || Hh <= 0) { cb.done(null); return; }
            final Bitmap b = Bitmap.createBitmap(W, Hh, Bitmap.Config.ARGB_8888);
            if (Build.VERSION.SDK_INT >= 26) {
                PixelCopy.request(w, b, new PixelCopy.OnPixelCopyFinishedListener() {
                    @Override public void onPixelCopyFinished(int r) {
                        if (r == PixelCopy.SUCCESS) cb.done(b);
                        else { Hub.log("Rideau : PixelCopy " + r); cb.done(soft(content, b)); }
                    }
                }, H);
            } else cb.done(soft(content, b));
        } catch (Throwable e) { Hub.log("Rideau : capture " + e); cb.done(null); }
    }
    private static Bitmap soft(View v, Bitmap b) {
        try { Canvas c = new Canvas(b); c.drawColor(0xFF0A0B0E); v.draw(c); return b; } catch (Throwable e) { return null; }
    }

    /** v1.19 : capture vide ou noire = le lanceur n'était pas encore affiché. On ne pose pas un rideau noir (écran noir
        sans réponse au toucher, CarPlay invisible dessous) : la lecture est reportée. */
    static boolean isBlack(Bitmap b) {
        if (b == null) return true;
        try {
            int w = b.getWidth(), h = b.getHeight(), lit = 0, n = 0;
            for (int y = 0; y < 12; y++) for (int x = 0; x < 20; x++) {
                int c = b.getPixel((int) ((x + 0.5f) * w / 20), (int) ((y + 0.5f) * h / 12)); n++;
                if (Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c))) > 40) lit++;
            }
            return lit * 100 < n * 3;
        } catch (Throwable e) { return true; }
    }

    static boolean show(AccessibilityService svc, Bitmap snap) {
        if (svc == null) return false;
        if (isBlack(snap)) { Hub.log("Rideau : capture du lanceur vide ou noire, rideau non posé"); return false; }
        hideNow();
        try {
            WindowManager wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
            FrameLayout f = new FrameLayout(svc);
            f.setBackgroundColor(0xFF0A0B0E);
            ImageView iv = new ImageView(svc);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            if (snap != null) iv.setImageBitmap(snap);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));

            // pastille discrète, qui n'apparaît que si la lecture dure (pas de clignotement si c'est rapide)
            LinearLayout pill = new LinearLayout(svc);
            pill.setOrientation(LinearLayout.HORIZONTAL); pill.setGravity(Gravity.CENTER_VERTICAL);
            int ph = (int) dp(svc, 14), pv = (int) dp(svc, 9);
            pill.setPadding(ph, pv, (int) dp(svc, 18), pv);
            GradientDrawable bg = new GradientDrawable(); bg.setColor(0xE6202024); bg.setCornerRadius(dp(svc, 22)); bg.setStroke((int) Math.max(1, dp(svc, 1)), 0x30FFFFFF);
            pill.setBackground(bg);
            ProgressBar pb = new ProgressBar(svc, null, android.R.attr.progressBarStyleSmall);
            pb.setIndeterminate(true);
            int ps = (int) dp(svc, 20);
            LinearLayout.LayoutParams lpb = new LinearLayout.LayoutParams(ps, ps); lpb.rightMargin = (int) dp(svc, 10);
            pill.addView(pb, lpb);
            TextView t = new TextView(svc);
            t.setText("Mise à jour des données du véhicule…"); t.setTextColor(Color.WHITE); t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            pill.addView(t);
            FrameLayout.LayoutParams lpp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lpp.bottomMargin = (int) dp(svc, 22);
            pill.setAlpha(0f);
            f.addView(pill, lpp);
            pill.animate().alpha(1f).setStartDelay(700).setDuration(250).start();

            f.setOnTouchListener(new View.OnTouchListener() { @Override public boolean onTouch(View v, MotionEvent e) { return true; } });

            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.windowAnimations = 0;
            if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            wm.addView(f, lp);
            view = f;
            H.removeCallbacks(SAFETY); H.postDelayed(SAFETY, 9000);
            return true;
        } catch (Throwable e) { Hub.log("Rideau : " + e); view = null; return false; }
    }

    static boolean shown() { return view != null; }
    /** v1.18 : prolonge le délai de sécurité (nouvel essai de lecture CarCan). */
    static void keep(long ms) { if (view == null) return; H.removeCallbacks(SAFETY); H.postDelayed(SAFETY, ms); }

    /** laisse passer un toucher simulé vers l'appli située dessous (le rideau reste affiché) */
    static void passThrough(boolean on) {
        if (view == null || lp == null) return;
        try {
            if (on) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            ((WindowManager) view.getContext().getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(view, lp);
        } catch (Throwable ignored) {}
    }

    /** retrait en fondu, une fois le lanceur revenu au premier plan */
    static void hide() {
        H.removeCallbacks(SAFETY);
        final View v = view;
        if (v == null) return;
        view = null;
        v.animate().alpha(0f).setStartDelay(0).setDuration(160).withEndAction(new Runnable() { public void run() { remove(v); } }).start();
        H.postDelayed(new Runnable() { public void run() { remove(v); } }, 450);   // v1.19 : retrait garanti, même si l'animation ne se joue pas
    }
    static void hideNow() { H.removeCallbacks(SAFETY); if (view != null) { remove(view); view = null; } }
    private static void remove(View v) {
        try { ((WindowManager) v.getContext().getSystemService(Context.WINDOW_SERVICE)).removeViewImmediate(v); } catch (Throwable ignored) {}
    }
}
