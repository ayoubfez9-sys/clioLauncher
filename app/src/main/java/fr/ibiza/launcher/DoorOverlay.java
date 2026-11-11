package fr.ibiza.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;

/** Fenêtre « activité en direct » affichée par-dessus les autres apps quand un ouvrant est ouvert.
 *  v1.5 : vue du haut horizontale (avant à gauche), plus grande, texte plus gros. */
public final class DoorOverlay {
    static boolean enabled = true;
    private static FrameLayout view;
    private static TextView title;
    private static final ImageView[] layers = new ImageView[6];
    private static ImageView base;
    private static int imgGen = -1;          // v1.24.1 : génération de pack des images affichées
    private static final String[] KEYS = {"fl", "fr", "rl", "rr", "trunk", "hood"};
    private static final String[] NAMES = {"Porte avant gauche", "Porte avant droite", "Porte arrière gauche", "Porte arrière droite", "Coffre", "Capot"};
    private static boolean shown = false;
    private static String dismissed = null;
    private static String lastSig = "";
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable HIDE = new Runnable() { public void run() { hide(); } };

    private DoorOverlay() {}

    static void update(String json) {
        Context c = Hub.app;
        if (c == null) return;
        try {
            JSONObject v = new JSONObject(json), d = v.optJSONObject("doors");
            boolean[] open = new boolean[6]; int n = 0, last = -1;
            if (d != null) for (int i = 0; i < 6; i++) if (d.optBoolean(KEYS[i])) { open[i] = true; n++; last = i; }
            boolean any = d != null && d.optBoolean("any");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) if (open[i]) sb.append(KEYS[i]).append(',');
            if (any) sb.append("any,");
            lastSig = sb.toString();
            if (n == 0 && !any) dismissed = null;
            else if (dismissed != null) {
                boolean fresh = false;
                for (String k : lastSig.split(",")) if (k.length() > 0 && !dismissed.contains(k + ",")) fresh = true;
                if (!fresh) return; else dismissed = null;
            }
            if (Hub.launcherVisible || !enabled || CanWatchService.refreshUntil > 0 || Curtain.shown() || MovingAlert.shown()) { hide(); return; }   // pas pendant la lecture masquée de CarCan
            if (n == 0 && !any) { if (shown) { title.setText("Tout est fermé"); setLayers(open); H.removeCallbacks(HIDE); H.postDelayed(HIDE, 1200); } return; }
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) return;
            ensure(c);
            setLayers(open);
            title.setText(n == 0 ? "Un ouvrant est ouvert" : n == 1 ? NAMES[last] + (last >= 4 ? " ouvert" : " ouverte") : n + " ouvrants ouverts");
            H.removeCallbacks(HIDE);
            show(c);
        } catch (Exception e) { Hub.log("overlay : " + e); }
    }

    private static void setLayers(boolean[] open) {
        for (int i = 0; i < 6; i++) if (layers[i] != null) layers[i].animate().alpha(open[i] ? 1f : 0f).setDuration(280).start();
    }

    private static float dp(Context c, float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()); }

    /** v1.24.1 : vue du dessus du pack voiture actif (avant : toujours celle de l'appli, même après un changement de pack) */
    private static Bitmap asset(Context c, String name) { return Packs.topBitmap(c, name); }

    private static void loadImages(Context c) {
        imgGen = Packs.gen;
        if (base != null) base.setImageBitmap(asset(c, "closed"));
        for (int i = 0; i < 6; i++) if (layers[i] != null) layers[i].setImageBitmap(asset(c, KEYS[i]));
    }

    private static void ensure(final Context c) {
        if (view != null) { if (imgGen != Packs.gen) loadImages(c); return; }
        view = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF2232327, 0xF2141416});
        bg.setCornerRadius(dp(c, 30)); bg.setStroke((int) Math.max(1, dp(c, 1)), 0x30FFFFFF);
        view.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) view.setElevation(dp(c, 18));
        int pad = (int) dp(c, 14);
        view.setPadding(pad, pad, pad, pad);

        FrameLayout car = new FrameLayout(c);
        FrameLayout.LayoutParams lpCar = new FrameLayout.LayoutParams(-1, -1);
        lpCar.topMargin = (int) dp(c, 50);
        base = new ImageView(c); base.setScaleType(ImageView.ScaleType.FIT_CENTER);
        car.addView(base, new FrameLayout.LayoutParams(-1, -1));
        for (int i = 0; i < 6; i++) {
            layers[i] = new ImageView(c); layers[i].setScaleType(ImageView.ScaleType.FIT_CENTER); layers[i].setAlpha(0f);
            car.addView(layers[i], new FrameLayout.LayoutParams(-1, -1));
        }
        loadImages(c);
        view.addView(car, lpCar);

        title = new TextView(c);
        title.setTextColor(Color.WHITE); title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setCompoundDrawablePadding((int) dp(c, 10));
        GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(0xFFFF9230);
        dot.setSize((int) dp(c, 12), (int) dp(c, 12));
        title.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null);
        FrameLayout.LayoutParams lpT = new FrameLayout.LayoutParams(-2, (int) dp(c, 42), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        view.addView(title, lpT);

        TextView close = new TextView(c);
        close.setText("\u2715"); close.setTextColor(0xCCFFFFFF); close.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        close.setGravity(Gravity.CENTER);
        GradientDrawable cb = new GradientDrawable(); cb.setShape(GradientDrawable.OVAL); cb.setColor(0x26FFFFFF);
        close.setBackground(cb);
        int cs = (int) dp(c, 42);
        view.addView(close, new FrameLayout.LayoutParams(cs, cs, Gravity.TOP | Gravity.END));
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dismissed = lastSig; hide(); } });

        view.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            hide();
            Intent i = new Intent(c, MainActivity.class); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        }});
    }

    private static void show(Context c) {
        if (shown) return;
        if (view.isAttachedToWindow()) { // masquage en cours : on repart de l'état affiché
            view.animate().cancel(); shown = true;
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(300).start();
            return;
        }
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        // taille : la plus grande possible sans masquer tout l'écran (image 528 x 425, soit 1,24:1)
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float pad = dp(c, 14), head = dp(c, 50);
        float wMax = dm.widthPixels * 0.62f, hMax = dm.heightPixels * 0.78f;
        float carW = Math.min(wMax - 2 * pad, (hMax - head - pad) * 1.242f);
        int W = (int) (carW + 2 * pad), Hh = (int) (carW / 1.242f + head + pad);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(W, Hh, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; lp.y = (int) dp(c, 12);
        try {
            wm.addView(view, lp); shown = true;
            view.setAlpha(0f); view.setScaleX(.86f); view.setScaleY(.86f); view.setTranslationY(-dp(c, 40));
            view.setPivotY(0);
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(420)
                    .setInterpolator(new PathInterpolator(0.2f, 0.9f, 0.25f, 1.05f)).start();
        } catch (Exception e) { Hub.log("overlay add : " + e); }
    }

    static void hide() {
        H.removeCallbacks(HIDE);
        if (!shown || view == null) return;
        final View v = view;
        v.animate().alpha(0f).scaleX(.9f).scaleY(.9f).translationY(-v.getHeight() * .15f).setDuration(220).withEndAction(new Runnable() { public void run() {
            if (shown) return;
            try { ((WindowManager) v.getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(v); } catch (Exception ignored) {}
        }}).start();
        shown = false;
    }
}
