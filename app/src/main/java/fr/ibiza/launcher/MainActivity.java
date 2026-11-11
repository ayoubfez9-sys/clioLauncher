package fr.ibiza.launcher;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

public class MainActivity extends Activity implements Hub.Listener {
    static final String HOST = "appassets.androidplatform.net";
    static final String START = "https://" + HOST + "/www/index.html";

    private WebView web;
    private final Handler handler = new Handler();

    /* v1.22 : chien de garde de l'écran, version économe. Plus aucune minuterie dans la page : c'est Android qui interroge
       l'interface (une image demandée, qui doit répondre « vivant ») 10 s après chaque retour sur le lanceur (moment où l'écran
       noir apparaît : démarrage, retour de CarPlay), puis toutes les 5 minutes tant que le lanceur reste affiché.
       Pas de réponse en 12 s : l'interface est rechargée ; toujours rien : l'écran du lanceur est recréé.
       Au plus 3 tentatives en 10 minutes, pour ne jamais tourner en boucle. */
    private volatile long lastAlive = 0;
    private volatile boolean aliveLogged = false;
    private long createdAt = 0, pingAt = 0;
    private int wdStage = 0; private boolean wdGaveUp = false, webDead = false, pageLoaded = false;
    private int jsErrors = 0; private long jsErrWin = 0;
    private static final long PING_FIRST = 10000, PING_EVERY = 5 * 60 * 1000, PONG_WAIT = 12000, AFTER_RELOAD = 20000;
    private static final java.util.ArrayList<Long> RECOVERIES = new java.util.ArrayList<>();
    private final Runnable ping = new Runnable() { public void run() {
        if (!Hub.launcherVisible || isFinishing() || web == null || webDead) return;
        if (!pageLoaded || Curtain.shown()) { handler.postDelayed(this, 10000); return; }   // page en cours de chargement : plus tard
        pingAt = SystemClock.uptimeMillis();
        try { web.evaluateJavascript("requestAnimationFrame(function(){try{Native.alive()}catch(e){}})", null); } catch (Throwable ignored) {}
        handler.removeCallbacks(pong); handler.postDelayed(pong, PONG_WAIT);
    }};
    private final Runnable pong = new Runnable() { public void run() {
        if (!Hub.launcherVisible || isFinishing() || web == null || webDead) return;
        if (lastAlive >= pingAt) { wdStage = 0; handler.postDelayed(ping, PING_EVERY); return; }   // l'écran répond
        long now = SystemClock.uptimeMillis();
        while (!RECOVERIES.isEmpty() && now - RECOVERIES.get(0) > 600000) RECOVERIES.remove(0);
        if (RECOVERIES.size() >= 3) {
            if (!wdGaveUp) { wdGaveUp = true; Hub.log("Écran : toujours figé après 3 tentatives en 10 min, le chien de garde s'arrête"); }
            return;
        }
        RECOVERIES.add(now);
        if (wdStage == 0) {
            wdStage = 1; Hub.log("Écran : l'interface ne répond plus (" + PONG_WAIT / 1000 + " s), rechargement");
            aliveLogged = false; pageLoaded = false; web.reload();
            handler.postDelayed(ping, AFTER_RELOAD);
        } else {
            wdStage = 0; Hub.log("Écran : toujours figé après rechargement, recréation de l'écran du lanceur");
            recreate();
        }
    }};
    private final LruCache<String, byte[]> icons = new LruCache<>(120);
    private boolean immersive = true;
    private android.webkit.ValueCallback<Uri[]> fileCb;
    private boolean gpsOn = false;
    private static boolean gpsAsked = false;
    private final android.location.LocationListener gps = new android.location.LocationListener() {
        @Override public void onLocationChanged(android.location.Location l) {
            float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
            CanBridge.gpsKmh = Math.round(kmh); CanBridge.gpsAt = System.currentTimeMillis();
            if (!CanBridge.canSpeed) js("speed", "{\"kmh\":" + Math.round(kmh) + "}");
        }
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };
    void startGps() {
        if (gpsOn) return;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // v1.27 : demandé une seule fois par démarrage (avant : à chaque retour sur le lanceur, donc en boucle après un refus)
            if (!gpsAsked) { gpsAsked = true; requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 7); }
            return;
        }
        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000, 0, gps);
            gpsOn = true;
        } catch (Exception e) { Hub.log("GPS : " + e); }
    }
    void stopGps() {
        if (!gpsOn) return;
        try { ((android.location.LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(gps); } catch (Exception ignored) {}
        gpsOn = false;
    }
    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (rc == 7 && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) startGps();
        if (rc == 9 && pendingPdf != null) { String j = pendingPdf; pendingPdf = null; startPdf(j); }
    }

    /* v1.11 : PDF du carnet d'entretien — Android 9 et avant : on demande une fois l'accès au stockage pour
       l'enregistrer dans Téléchargements (sinon il va dans le dossier de l'appli) */
    private String pendingPdf = null;
    void startPdf(String json) {
        android.content.SharedPreferences sp = getSharedPreferences("ibz", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29 && pendingPdf == null && !sp.getBoolean("pdfAsked", false)
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingPdf = json; sp.edit().putBoolean("pdfAsked", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 9);
            return;
        }
        final String j = json;
        new Thread(new Runnable() { public void run() {
            final JSONObject res = new JSONObject();
            try {
                JSONObject r = new JSONObject(j);
                android.graphics.pdf.PdfDocument doc = MaintPdf.render(r);
                try {
                    String where = MaintPdf.save(MainActivity.this, doc, r.optString("file"));
                    res.put("ok", true); res.put("where", where);
                    Hub.log("PDF entretien : " + where);
                } finally { doc.close(); }
            } catch (Exception e) {
                Hub.log("PDF : " + e);
                try { res.put("ok", false); res.put("err", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
            }
            handler.post(new Runnable() { public void run() { js("pdf", res.toString()); } });
        }}).start();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PACK) {
            final Uri u = (res == RESULT_OK && data != null) ? data.getData() : null;
            if (u == null) { js("pack", "{\"ok\":false,\"cancel\":true}"); return; }
            new Thread(new Runnable() { public void run() {
                JSONObject r;
                try { r = Packs.install(MainActivity.this, getContentResolver().openInputStream(u)); }
                catch (Exception e) { r = new JSONObject(); try { r.put("ok", false); r.put("error", "fichier illisible"); } catch (Exception ignored) {} }
                afterPack(r);
            } }, "ibiza-pack").start();
            return;
        }
        if (req == 42 && fileCb != null) {
            Uri u = (res == RESULT_OK && data != null) ? data.getData() : null;
            fileCb.onReceiveValue(u == null ? null : new Uri[]{u});
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }
    private java.io.File customIcon(String pkg) {
        java.io.File d = getExternalFilesDir("icons");
        if (d == null) return null;
        java.io.File f = new java.io.File(d, pkg + ".png");
        return f.exists() ? f : null;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Hub.app = getApplicationContext();
        createdAt = SystemClock.uptimeMillis();
        Hub.log("Lanceur : création de l'écran");
        Hub.observeSettings(this);
        Learn.init(this);                 // v1.25 : carte apprise
        CanApp.init(this);                // v1.27 : profil de l'appli CAN
        CanBridge.start(this);
        new Thread(new Runnable() { public void run() { Packs.installBundled(MainActivity.this); } }, "ibiza-pack").start();   // v1.23
        Ts10Probe.logPlatform();          // v1.22.1 : TopWay TS10 / TS18 repéré dans le journal
        AccessHelper.selfEnable(this);    // v1.24.3 : se réautorise si la permission a été accordée par adb (sans effet sinon)
        new Thread(new Runnable() { public void run() { Ts10Probe.startLive(); } }, "ibiza-ts10-open").start();   // v1.24.2 : portes en direct
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        immersive = !"false".equals(getSharedPreferences("ibz", MODE_PRIVATE).getString("immersive", "true"));
        DoorOverlay.enabled = !"false".equals(getSharedPreferences("ibz", MODE_PRIVATE).getString("live", "true"));
        MovingAlert.enabled = !"false".equals(getSharedPreferences("ibz", MODE_PRIVATE).getString("moveAlert", "true"));

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 23) s.setOffscreenPreRaster(true);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebChromeClient(new WebChromeClient() {
            // v1.19 : erreurs JavaScript dans le journal (20 par minute au plus)
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                if (m != null && m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    long now = SystemClock.uptimeMillis(); if (now - jsErrWin > 60000) { jsErrWin = now; jsErrors = 0; }
                    String src = m.sourceId() == null ? "" : m.sourceId().replaceAll(".*/", "");
                    if (++jsErrors <= 20) Hub.log("JS : " + m.message() + " (" + src + ":" + m.lineNumber() + ")");
                }
                return false;
            }
            @Override public boolean onShowFileChooser(WebView v, android.webkit.ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("image/*");
                    startActivityForResult(Intent.createChooser(i, "Choisir une image"), 42);
                } catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { pageLoaded = true; Hub.log("Écran : page chargée"); }
            /* v1.19 : le moteur web (processus de rendu) a planté ou a été tué faute de mémoire. Sans ce traitement, l'écran
               restait noir. La WebView est inutilisable : on la retire et on recrée l'écran du lanceur. */
            @Override public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                boolean crash = Build.VERSION.SDK_INT >= 26 && d != null && d.didCrash();
                Hub.log("Écran : moteur web arrêté (" + (crash ? "plantage" : "mémoire insuffisante") + "), recréation de l'écran du lanceur");
                webDead = true;
                try { if (v.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) v.getParent()).removeView(v); } catch (Throwable ignored) {}
                try { v.destroy(); } catch (Throwable ignored) {}
                if (v == web) web = null;
                handler.post(new Runnable() { public void run() { if (!isFinishing()) recreate(); } });
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                return serve(u.getPath());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !HOST.equals(r.getUrl().getHost());
            }
        });
        setContentView(web);
        web.loadUrl(START);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED); f.addAction(Intent.ACTION_PACKAGE_REMOVED); f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED); else registerReceiver(pkgReceiver, f);
        Hub.add(this);
        Hub.log("Lanceur démarré · Android SDK " + Build.VERSION.SDK_INT + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · " + Build.HARDWARE);
    }

    /* ---------------- fichiers web + icônes d'apps ---------------- */
    private WebResourceResponse serve(String path) {
        try {
            // v1.23 : pack voiture installé (images + réglages), prioritaire sur les images de l'appli
            if (path.startsWith("/www/img/")) {
                String rel = path.substring(5);
                java.io.File pf = Packs.file(this, rel);
                if (pf != null) return noStore(new WebResourceResponse(mime(path), null, new java.io.FileInputStream(pf)));
                // v1.24.1 : pack actif -> les images de voiture viennent du pack seulement (plus de mélange avec la voiture de l'appli)
                if (Packs.carFile(rel) && Packs.activeDir(this) != null) return notFound();
            }
            if (path.equals("/www/js/config.js")) {
                // v1.24.1 : jamais gardé en cache (il change avec le pack voiture), avec ou sans pack
                return noStore(new WebResourceResponse("application/javascript", "utf-8",
                        new ByteArrayInputStream((readAsset("www/js/config.js") + Packs.configPatch(this)).getBytes("UTF-8"))));
            }
            if (path.equals("/www/index.html")) return noStore(new WebResourceResponse("text/html", "utf-8", getAssets().open("www/index.html")));
            if (path.equals("/builtin/car.webp")) {          // miniature de la voiture intégrée, même quand un pack est affiché
                return noStore(new WebResourceResponse("image/webp", null, getAssets().open(Packs.builtinView(this))));
            }
            if (path.startsWith("/packs/") && path.endsWith("/preview.jpg")) {
                java.io.File pv = Packs.preview(this, path.substring(7, path.length() - 12));
                if (pv != null) return noStore(new WebResourceResponse("image/jpeg", null, new java.io.FileInputStream(pv)));
            }
            if (path.startsWith("/www/")) {
                InputStream in = getAssets().open(path.substring(1));
                return new WebResourceResponse(mime(path), mime(path).startsWith("text") || path.endsWith(".js") ? "utf-8" : null, in);
            }
            if (path.startsWith("/icons/")) {
                String[] p = path.substring(7).split("/");
                String pkg = Uri.decode(p[0]), cls = Uri.decode(p[1].replace(".png", ""));
                if (cls.contains("?")) cls = cls.substring(0, cls.indexOf('?'));
                byte[] png = icons.get(pkg + "/" + cls);
                if (png == null) { png = iconPng(pkg, cls); if (png != null) icons.put(pkg + "/" + cls, png); }
                if (png != null) return new WebResourceResponse("image/png", null, new ByteArrayInputStream(png));
            }
        } catch (Exception e) { Hub.log("serve " + path + " : " + e); }
        return notFound();
    }
    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, new ByteArrayInputStream(new byte[0]));
    }
    private static WebResourceResponse noStore(WebResourceResponse r) {
        java.util.Map<String, String> h = new java.util.HashMap<>(); h.put("Cache-Control", "no-store"); r.setResponseHeaders(h); return r;
    }
    private String readAsset(String p) throws java.io.IOException {
        InputStream in = getAssets().open(p);
        try { ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int r; while ((r = in.read(b)) > 0) bo.write(b, 0, r); return bo.toString("UTF-8"); }
        finally { in.close(); }
    }

    /* ---------------- v1.23 : packs voiture ---------------- */
    static final int REQ_PACK = 43;
    void pickPack() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{ "application/zip", "application/x-zip-compressed", "application/octet-stream" });
            startActivityForResult(i, REQ_PACK);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
                startActivityForResult(Intent.createChooser(i, "Choisir le pack (.zip)"), REQ_PACK);
            } catch (Exception e2) {
                js("pack", "{\"ok\":false,\"nopicker\":true,\"error\":\"pas de sélecteur de fichiers sur ce poste : copie le zip dans le dossier des packs puis « Importer depuis le dossier »\"}");
            }
        }
    }
    void afterPack(final JSONObject r) {
        handler.post(new Runnable() { public void run() {
            js("pack", r.toString());
            if (r.optBoolean("ok")) handler.postDelayed(new Runnable() { public void run() { reloadForPack(); } }, 1200);
        } });
    }
    void reloadPage() { if (web != null && !webDead) web.reload(); }
    /** v1.24.1 : changement de voiture. Les fichiers gardent le même nom d'un pack à l'autre (img/top/closed.webp,
     *  js/config.js…) : le cache du WebView (mémoire comprise) est vidé avant de recharger, sinon la vue du dessus et le nom
     *  de la voiture pouvaient rester ceux du pack précédent. */
    void reloadForPack() {
        if (web == null || webDead) return;
        try { web.clearCache(true); } catch (Throwable ignored) {}
        web.reload();
    }

    private static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg")) return "image/jpeg";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
    private byte[] iconPng(String pkg, String cls) {
        try {
            java.io.File cf = customIcon(pkg);
            if (cf != null) {
                java.io.FileInputStream in = new java.io.FileInputStream(cf);
                ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                in.close(); return o.toByteArray();
            }
            PackageManager pm = getPackageManager();
            Drawable d;
            try { d = pm.getActivityIcon(new ComponentName(pkg, cls)); } catch (Exception e) { d = pm.getApplicationIcon(pkg); }
            int sz = 144;
            Bitmap bmp;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                bmp = Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), sz, sz, true);
            } else {
                bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp); d.setBounds(0, 0, sz, sz); d.draw(c);
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
            return o.toByteArray();
        } catch (Exception e) { return null; }
    }

    /* ---------------- pont JavaScript ---------------- */
    class Bridge {
        /** v1.19 : signe de vie de l'interface (voir le chien de garde) */
        @JavascriptInterface public void alive() {
            lastAlive = SystemClock.uptimeMillis();
            if (!aliveLogged) { aliveLogged = true; Hub.log("Écran : interface affichée (" + (lastAlive - createdAt) + " ms après la création)"); }
        }
        @JavascriptInterface public String getApps() {
            JSONArray a = new JSONArray();
            try {
                Intent i = new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = getPackageManager().queryIntentActivities(i, 0);
                for (ResolveInfo r : list) {
                    ActivityInfo ai = r.activityInfo;
                    if (getPackageName().equals(ai.packageName)) continue;
                    JSONObject o = new JSONObject();
                    o.put("pkg", ai.packageName); o.put("cls", ai.name);
                    o.put("label", String.valueOf(r.loadLabel(getPackageManager())));
                    if (customIcon(ai.packageName) != null) o.put("custom", true);
                    a.put(o);
                }
            } catch (Exception e) { Hub.log("getApps " + e); }
            return a.toString();
        }
        @JavascriptInterface public void launch(String pkg, String cls) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_LAUNCHER);
                i.setComponent(new ComponentName(pkg, cls));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(i);
            } catch (Exception e) {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
                else Hub.log("launch KO " + pkg + " " + e);
            }
        }
        @JavascriptInterface public String getState() {
            try {
                JSONObject o = new JSONObject();
                o.put("media", new JSONObject(Hub.mediaJson));
                o.put("vehicle", new JSONObject(Hub.vehicleJson));
                JSONObject p = new JSONObject();
                p.put("notif", listenerEnabled());
                p.put("access", accessEnabled());
                p.put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(MainActivity.this));
                p.put("home", isDefaultHome());
                o.put("perms", p);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void media(String cmd) {
            if (MediaListener.command(cmd)) return;
            int key;
            switch (cmd) {
                case "next": key = KeyEvent.KEYCODE_MEDIA_NEXT; break;
                case "prev": key = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
                case "play": key = KeyEvent.KEYCODE_MEDIA_PLAY; break;
                default: key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            }
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            long t = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0));
        }
        /* v1.24.3 : « Lecture CAN (accessibilité) » : "on" déjà autorisé, "enabled" autorisé directement (permission
           accordée par adb), "opened" page Accessibilité ouverte, "missing" le poste n'a pas de page Accessibilité */
        @JavascriptInterface public String openAccess() {
            if (accessEnabled()) return "on";
            if (AccessHelper.selfEnable(MainActivity.this)) return "enabled";
            Intent i = AccessHelper.settingsIntent(MainActivity.this);
            if (i != null) {
                try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "opened"; } catch (Exception ignored) {}
            }
            Hub.log("ACCESSIBILITÉ : aucune page Accessibilité sur ce poste (Paramètres modifiés par le fabricant)");
            return "missing";
        }
        /* v1.27 : Android 13 et plus bloque l'accessibilité (et l'accès aux notifications) des applis installées depuis un
           fichier APK : « Paramètre restreint ». Vrai quand il faut expliquer au client comment lever ce blocage. */
        @JavascriptInterface public boolean accessHint() {
            return Build.VERSION.SDK_INT >= 33 && !accessEnabled() && !AccessHelper.canSelfEnable(MainActivity.this) && AccessHelper.settingsIntent(MainActivity.this) != null;
        }
        @JavascriptInterface public String accessCommand() { return "adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS"; }
        @JavascriptInterface public void openSystem(String which) {
            Intent i;
            switch (which) {
                case "home": i = new Intent(Settings.ACTION_HOME_SETTINGS); break;
                // désinstallation du lanceur (Android ne le permet qu'une fois un autre écran d'accueil choisi par défaut)
                case "uninstall": i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getPackageName())); break;
                case "appinfo": i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())); break;
                case "notif": i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"); break;
                case "access": i = AccessHelper.settingsIntent(MainActivity.this); if (i == null) return; break;
                case "overlay": i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())); break;
                default: i = new Intent(Settings.ACTION_SETTINGS);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (Exception e) {
                if ("home".equals(which)) { // repli : sélecteur d'écran d'accueil
                    Intent h = new Intent(Intent.ACTION_MAIN); h.addCategory(Intent.CATEGORY_HOME);
                    startActivity(Intent.createChooser(h, "Écran d'accueil").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } else {
                    try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
                }
            }
        }
        @JavascriptInterface public void setPref(String k, String v) {
            getSharedPreferences("ibz", MODE_PRIVATE).edit().putString(k, v).apply();
            if ("immersive".equals(k)) { immersive = "true".equals(v); handler.post(new Runnable() { public void run() { applyImmersive(); } }); }
            if ("live".equals(k)) DoorOverlay.enabled = !"false".equals(v);
            if ("moveAlert".equals(k)) MovingAlert.enabled = !"false".equals(v);
        }
        @JavascriptInterface public void simulate(String json) { CanBridge.simUntil = System.currentTimeMillis() + 15000; Hub.log("SIMULATION " + json); Hub.emit("vehicle", json); }
        @JavascriptInterface public void canRefresh() { CanWatchService.requestRefresh(MainActivity.this); }
        @JavascriptInterface public void testMoveAlert() { MovingAlert.test(); }
        /* v1.11 : carnet d'entretien en PDF */
        @JavascriptInterface public void exportPdf(final String json) { handler.post(new Runnable() { public void run() { startPdf(json); } }); }
        @JavascriptInterface public void openPdf() { handler.post(new Runnable() { public void run() {
            if (!MaintPdf.open(MainActivity.this)) js("toast", "Aucune appli pour lire les PDF : utilise « Partager » ou ouvre le fichier depuis le gestionnaire de fichiers");
        } }); }
        @JavascriptInterface public void sharePdf() { handler.post(new Runnable() { public void run() {
            if (!MaintPdf.share(MainActivity.this)) js("toast", "Partage impossible sur ce poste");
        } }); }
        @JavascriptInterface public boolean canRefreshPossible() { return CanWatchService.refreshPossible(MainActivity.this); }
        /* v1.27 : « Apprendre l'ordinateur de bord » (appli CAN de n'importe quel poste, voir CanApp) */
        @JavascriptInterface public String canAppInfo() { return CanApp.info(); }
        @JavascriptInterface public String canAppList() { return CanApp.listApps(MainActivity.this); }
        @JavascriptInterface public String canAppStart(String pkg) { return CanApp.start(MainActivity.this, pkg); }
        @JavascriptInterface public String canAppPending() { return CanApp.takePending(); }
        @JavascriptInterface public String canAppSave(String mapping) { return CanApp.save(mapping); }
        @JavascriptInterface public void canAppClear() { CanApp.clear(); }
        @JavascriptInterface public String canScan() { return CanBridge.discover(true); }
        @JavascriptInterface public String canExport() { return CanBridge.exportApks(); }
        @JavascriptInterface public boolean canLogs() { return CanBridge.logsAllowed(); }
        // v1.23 : packs voiture
        @JavascriptInterface public String packList() { return Packs.list(MainActivity.this); }
        @JavascriptInterface public void packUse(String id) {
            Packs.setActive(MainActivity.this, id == null ? "" : id);
            Hub.log("PACK : voiture affichée = " + (id == null || id.isEmpty() ? "celle de l'appli" : id));
            handler.post(new Runnable() { public void run() { reloadForPack(); } });
        }
        @JavascriptInterface public boolean packDelete(String id) {
            boolean ok = Packs.delete(MainActivity.this, id);
            if (ok) handler.post(new Runnable() { public void run() { reloadForPack(); } });
            return ok;
        }
        // v1.24.1 : la page vérifie au chargement qu'elle affiche bien le pack actif ; sinon rechargement sans cache
        @JavascriptInterface public String packShown() { return Packs.shownId(MainActivity.this); }
        @JavascriptInterface public void packReload() { handler.post(new Runnable() { public void run() { reloadForPack(); } }); }
        @JavascriptInterface public void packImport() { handler.post(new Runnable() { public void run() { pickPack(); } }); }
        @JavascriptInterface public void packImportFolder() {
            new Thread(new Runnable() { public void run() { afterPack(Packs.importFolder(MainActivity.this)); } }, "ibiza-pack").start();
        }
        @JavascriptInterface public boolean isTs10() { return Ts10Probe.present(); }
        /* v1.25 : apprentissage du véhicule (sondage guidé, sans adb) et export par QR codes */
        @JavascriptInterface public String learnStart() { Learn.init(MainActivity.this); return Learn.start(); }
        @JavascriptInterface public void learnStep(String ev, boolean on) { Learn.step(ev, on); }
        @JavascriptInterface public String learnStatus() { return Learn.status(); }
        @JavascriptInterface public String learnFinish() { return Learn.finish(); }
        @JavascriptInterface public String learnSave() { return Learn.save(); }
        @JavascriptInterface public void learnClear() { Learn.clear(); }
        @JavascriptInterface public boolean learnHasMap() { return Learn.hasMap(); }
        /** v1.26 : le poste donne-t-il déjà les portes tout seul ? (assistant de démarrage : « Vérifier » au lieu d'« Apprendre ») */
        @JavascriptInterface public String doorInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("learned", Learn.hasMap());
                o.put("known", Learn.hasMap() || CanBridge.cemDoors || Ts10Probe.present());
                o.put("src", Learn.hasMap() ? "carte apprise" : CanBridge.cemDoors ? "propriétés du poste" : Ts10Probe.present() ? "CAN TopWay" : "");
                o.put("steer", Hub.steer == null ? JSONObject.NULL : (Object) Hub.steer);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public String qrExport(String kind) { return QrExport.build(kind); }
        @JavascriptInterface public String ts10Probe() { return Ts10Probe.running() ? "Sondage déjà en cours" : Ts10Probe.start(180); }
        @JavascriptInterface public boolean canLive() { return LogCan.live(); }
        @JavascriptInterface public void tryRoot() { RootGrant.tryAsync(MainActivity.this, true); }
        @JavascriptInterface public int canFrames() { return LogCan.frames; }
        @JavascriptInterface public String getCar() { return Hub.carJson == null ? "{}" : Hub.carJson; }
        @JavascriptInterface public void launchPkg(String pkg) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED); startActivity(i); }
            else Hub.log("launchPkg KO " + pkg);
        }
        @JavascriptInterface public String getDiag() { return Hub.diag(); }
        // v1.24 : rapport de compatibilité (QR code) : JSON compressé à mettre dans l'adresse du QR code
        @JavascriptInterface public String report(String js) { return Report.build(MainActivity.this, js); }
        @JavascriptInterface public String exportDiag() { return Hub.export(MainActivity.this); }
        @JavascriptInterface public void clearDiag() { Hub.clear(); }
        /** v1.20 : liste des applis du poste avec le nom de fichier de leur icône perso (icons/<paquet>.png) */
        @JavascriptInterface public String exportAppList() {
            try {
                java.io.File d = getExternalFilesDir("icons");
                if (d == null) return "";
                if (!d.exists()) d.mkdirs();
                Intent i = new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = getPackageManager().queryIntentActivities(i, 0);
                java.util.TreeMap<String, String> rows = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for (ResolveInfo r : list) {
                    String pkg = r.activityInfo.packageName;
                    if (getPackageName().equals(pkg)) continue;
                    String label = String.valueOf(r.loadLabel(getPackageManager()));
                    rows.put(label + "\u0000" + pkg, label + "  ->  " + pkg + ".png" + (customIcon(pkg) != null ? "   (icône perso déjà présente)" : ""));
                }
                StringBuilder sb = new StringBuilder("Icônes perso du lanceur\n=======================\n"
                        + "Mets chaque image (PNG carré, 192 x 192 px ou plus) dans ce dossier, avec le nom indiqué,\n"
                        + "puis Réglages du lanceur > Recharger les icônes perso.\n\n");
                for (String s : rows.values()) sb.append(s).append('\n');
                java.io.File f = new java.io.File(d, "_liste_des_applis.txt");
                java.io.FileOutputStream o = new java.io.FileOutputStream(f); o.write(sb.toString().getBytes("UTF-8")); o.close();
                Hub.log("Icônes perso : liste de " + rows.size() + " applis écrite dans " + f.getAbsolutePath());
                return f.getAbsolutePath();
            } catch (Exception e) { Hub.log("Liste des applis : " + e); return ""; }
        }
        @JavascriptInterface public String iconDir() { java.io.File d = getExternalFilesDir("icons"); return d == null ? "" : d.getAbsolutePath(); }
        @JavascriptInterface public void reloadIcons() { icons.evictAll(); Hub.emit("apps", "{}"); }
    }

    boolean listenerEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }
    boolean accessEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName());
    }
    boolean isDefaultHome() {
        Intent h = new Intent(Intent.ACTION_MAIN); h.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = getPackageManager().resolveActivity(h, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
    }

    /* ---------------- événements vers le JS ---------------- */
    @Override public void onEvent(String type, String json) { js(type, json); }
    void js(String type, String json) {
        if (web == null || webDead) return;
        String q = JSONObject.quote(json == null ? "{}" : json);
        web.evaluateJavascript("window.onNative&&window.onNative('" + type + "'," + q + ")", null);
    }
    private final Runnable statusTick = new Runnable() { public void run() {
        try {
            JSONObject o = new JSONObject();
            boolean bt = false;
            try { BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter(); bt = a != null && a.isEnabled(); } catch (Throwable ignored) {}
            boolean wifi = false;
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                NetworkInfo n = cm.getActiveNetworkInfo();
                wifi = n != null && n.isConnected() && n.getType() == ConnectivityManager.TYPE_WIFI;
            } catch (Throwable ignored) {}
            o.put("bt", bt); o.put("wifi", wifi);
            js("status", o.toString());
            MediaListener.tickPosition();
        } catch (Exception ignored) {}
        handler.postDelayed(this, 1000);
    }};

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { icons.evictAll(); js("apps", "{}"); }
    };

    void applyImmersive() {
        View d = getWindow().getDecorView();
        if (immersive) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        } else d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }
    void sendNight() {
        int m = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (m == android.content.res.Configuration.UI_MODE_NIGHT_UNDEFINED) return;
        js("night", "{\"night\":" + (m == android.content.res.Configuration.UI_MODE_NIGHT_YES) + "}");
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        Hub.log("Configuration : uiMode=" + c.uiMode);
        sendNight();
    }
    @Override public void onWindowFocusChanged(boolean has) { super.onWindowFocusChanged(has); if (has) applyImmersive(); }

    @Override protected void onResume() {
        super.onResume();
        Hub.launcherVisible = true; DoorOverlay.hide();
        Hub.log("Lanceur au premier plan");
        applyImmersive();
        if (web != null && !webDead) web.onResume();
        handler.removeCallbacks(ping); handler.removeCallbacks(pong); handler.postDelayed(ping, PING_FIRST);   // v1.22
        if (CanApp.recording) CanApp.finish(false);   // v1.27 : touche Accueil pendant l'apprentissage de l'appli CAN = « Terminer »
        boolean silent = CanWatchService.returning; CanWatchService.returning = false;
        js("resume", silent ? "{\"silent\":true}" : "{}");
        js("media", Hub.mediaJson); js("vehicle", Hub.vehicleJson);
        sendNight();
        if (!accessEnabled()) AccessHelper.selfEnable(this);   // v1.24.3 : au cas où le poste efface le réglage
        if (Hub.lightsJson != null) js("lights", Hub.lightsJson);
        if (Hub.carJson != null) js("car", Hub.carJson);
        if (Hub.steer != null) js("steer", "{\"deg\":" + Hub.steer + "}");
        handler.removeCallbacks(statusTick); handler.post(statusTick);
        startGps();
    }
    @Override protected void onPause() {
        super.onPause();
        Hub.launcherVisible = false;
        handler.removeCallbacks(ping); handler.removeCallbacks(pong);
        Hub.log("Lanceur en arrière-plan");
        js("pause", "{}");
        handler.removeCallbacks(statusTick);
        stopGps();
        DoorOverlay.update(Hub.vehicleJson);
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); if (!i.getBooleanExtra("ibz_return", false)) js("home", "{}"); }
    View contentView() { return web; }
    @Override public void onBackPressed() { js("home", "{}"); } // un lanceur ne se ferme pas
    @Override protected void onDestroy() {
        Hub.remove(this);
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) {}
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
