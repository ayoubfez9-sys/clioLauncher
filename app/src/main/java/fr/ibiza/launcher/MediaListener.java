package fr.ibiza.launcher;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** Lit la lecture en cours (titre, artiste, pochette) de n'importe quelle app via les sessions média. */
public class MediaListener extends NotificationListenerService {
    private static MediaListener self;
    private static MediaController current;
    private MediaSessionManager msm;
    private String lastArtKey = "";
    private String lastArt = "";

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = new MediaSessionManager.OnActiveSessionsChangedListener() {
        @Override public void onActiveSessionsChanged(List<MediaController> list) { pick(list); }
    };
    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { push(); }
        @Override public void onMetadataChanged(MediaMetadata m) { push(); }
        @Override public void onSessionDestroyed() { current = null; pick(null); }
    };

    /* ---- Sources de repli quand l'app (radio d'origine, lecteur USB…) ne publie pas de session média ----
       1) sa notification (titre / texte), 2) les diffusions « metachanged » des lecteurs Android,
       3) l'écran de la radio lu par l'accessibilité (fréquence, nom RDS). */
    static JSONObject fallback = null;     // {title, artist, source, pkg, playing?, kind}
    static long fallbackAt = 0, sessionPlayAt = 0;
    static Boolean guessPlaying = null;    // état supposé quand l'app ne dit rien (bouton lecture/pause)

    /* v1.26.1 : bouton lecture / pause inversé (relevé sur le Junsun, session com.android.bluetooth). L'état venait soit
       de la session (parfois en retard ou figé), soit d'une supposition faite au premier appui (« rien ne joue ») : si la
       musique jouait déjà, tout restait inversé ensuite. On vérifie maintenant avec le son réellement joué par Android
       (AudioManager.isMusicActive) : s'il contredit l'état annoncé pendant plus de 1,5 s (son présent) ou 3 s (silence),
       c'est le son qui gagne. Seulement une fois qu'on a vu du son passer par Android (sur certains postes, la radio ou
       le Bluetooth sortent en direct vers l'ampli, sans passer par Android) ; jamais pour la radio. */
    static volatile boolean audioSeen = false;
    private static long activeSince = 0, idleSince = 0;
    private static String lastFix = "";
    static Boolean audioActive() {
        try {
            android.content.Context c = self != null ? self : Hub.app;
            android.media.AudioManager am = c == null ? null : (android.media.AudioManager) c.getSystemService(AUDIO_SERVICE);
            if (am == null) return null;
            boolean a = am.isMusicActive();
            if (a && !audioSeen) { audioSeen = true; Hub.log("MÉDIA : le son passe par Android : lecture / pause vérifiés sur le son"); }
            return a;
        } catch (Throwable t) { return null; }
    }
    static boolean corrected(boolean claimed, String src) {
        Boolean a = audioActive();
        if (a == null || !audioSeen) return claimed;
        long now = SystemClock.elapsedRealtime();
        if (a) { idleSince = 0; if (activeSince == 0) activeSince = now; } else { activeSince = 0; if (idleSince == 0) idleSince = now; }
        boolean out = claimed;
        if (claimed != a && (a ? now - activeSince > 1500 : now - idleSince > 3000)) out = a;
        String fix = out != claimed ? src + ":" + claimed : "";
        if (!fix.equals(lastFix)) { lastFix = fix; if (!fix.isEmpty()) Hub.log("MÉDIA : " + src + " annonce " + (claimed ? "lecture" : "pause") + ", le son dit " + (a ? "lecture" : "pause") + " -> " + (out ? "lecture" : "pause")); }
        if (out != claimed && guessPlaying != null) guessPlaying = out;
        return out;
    }

    static void setFallback(JSONObject o) {
        fallback = o; fallbackAt = System.currentTimeMillis();
        if (self != null) self.push(); else emitFallbackOnly();
    }
    private static void emitFallbackOnly() {
        try { JSONObject o = new JSONObject(fallback.toString()); if (guessPlaying != null && !o.has("playing")) o.put("playing", guessPlaying); Hub.emit("media", o.toString()); } catch (Exception ignored) {}
    }
    private static final java.util.regex.Pattern AUDIOISH = java.util.regex.Pattern.compile("radio|fm|tuner|music|musique|player|audio|media|bt|bluetooth|spotify|deezer|usb", java.util.regex.Pattern.CASE_INSENSITIVE);

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            if (pkg.equals(getPackageName())) return;
            Notification n = sbn.getNotification();
            Bundle x = n.extras;
            boolean mediaStyle = x != null && (x.containsKey(Notification.EXTRA_MEDIA_SESSION) || "transport".equals(n.category));
            if (!mediaStyle && !AUDIOISH.matcher(pkg).find()) return;
            CharSequence t = x == null ? null : x.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence a = x == null ? null : x.getCharSequence(Notification.EXTRA_TEXT);
            if (a == null && x != null) a = x.getCharSequence(Notification.EXTRA_SUB_TEXT);
            if (t == null || t.length() == 0) {
                // radio / lecteur USB du poste : notification « maison », le texte est dans sa mise en page
                java.util.List<String> rt = remoteTexts(n);
                String sig = pkg + rt;
                if (!sig.equals(lastNotifSig)) { lastNotifSig = sig; Hub.log("NOTIF " + pkg + " (vue) " + rt); }
                if (rt.isEmpty()) return;
                if (pkg.contains("radio") || pkg.contains("fm")) {
                    String freq = null, ps = null, info = null;
                    for (String s1 : rt) {
                        String pf = CarText.prettyFreq(s1);
                        if (freq == null && !pf.equals(s1)) freq = s1;
                        else if (ps == null && s1.length() <= 24 && !s1.matches("(?i)(radio|fm\\d?|am\\d?|st|loc|dx|ta|tp|af|pty.*|[\\d.,: ]+|mhz|khz)")) ps = s1;
                        else if (info == null && s1.length() > 3 && !s1.matches("[\\d.,: ]+")) info = s1;
                    }
                    CanBridge.radio(freq, ps, info, "notification");
                    return;
                }
                t = rt.get(0); a = rt.size() > 1 ? rt.get(1) : "";
            } else Hub.log("NOTIF " + pkg + " · " + t + " · " + a);
            JSONObject o = new JSONObject();
            o.put("title", t.toString()); o.put("artist", a == null ? "" : a.toString()); o.put("pkg", pkg); o.put("kind", "notif");
            try { PackageManager pm = getPackageManager(); o.put("source", pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()); } catch (Exception ignored) {}
            setFallback(o);
        } catch (Exception e) { Hub.log("notif : " + e); }
    }
    private String lastNotifSig = "";
    /** Textes d'une notification à mise en page perso (RemoteViews), dans l'ordre d'affichage. */
    private java.util.List<String> remoteTexts(Notification n) {
        java.util.List<String> out = new java.util.ArrayList<>();
        android.widget.RemoteViews rv = n.bigContentView != null ? n.bigContentView : n.contentView;
        if (rv == null) return out;
        try { walk(rv.apply(this, new android.widget.FrameLayout(this)), out); } catch (Throwable e) { Hub.log("notif vue : " + e); }
        return out;
    }
    private static void walk(android.view.View v, java.util.List<String> out) {
        if (v == null || v.getVisibility() != android.view.View.VISIBLE) return;
        if (v instanceof android.widget.TextView) { CharSequence c = ((android.widget.TextView) v).getText(); if (c != null && c.toString().trim().length() > 0) out.add(c.toString().trim()); }
        if (v instanceof android.view.ViewGroup) { android.view.ViewGroup g = (android.view.ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), out); }
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        try { if (fallback != null && "notif".equals(fallback.optString("kind")) && sbn.getPackageName().equals(fallback.optString("pkg"))) { fallback = null; push(); } } catch (Exception ignored) {}
    }

    @Override public void onListenerConnected() {
        self = this;
        Hub.app = getApplicationContext();
        try {
            msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(this, MediaListener.class);
            msm.addOnActiveSessionsChangedListener(sessions, me);
            pick(msm.getActiveSessions(me));
        } catch (Exception e) { Hub.log("média : " + e); }
    }
    @Override public void onListenerDisconnected() {
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) {}
        self = null;
    }

    private void pick(List<MediaController> list) {
        try {
            if (list == null && msm != null) list = msm.getActiveSessions(new ComponentName(this, MediaListener.class));
            MediaController best = null;
            if (list != null) for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (best == null) best = c;
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
            }
            if (current != null) try { current.unregisterCallback(cb); } catch (Exception ignored) {}
            current = best;
            if (current != null) { current.registerCallback(cb); Hub.log("média : session " + current.getPackageName()); }
            push();
        } catch (Exception e) { Hub.log("média pick : " + e); }
    }

    static void tickPosition() { if (self != null && current != null) self.push(); }

    void push() {
        try {
            JSONObject o = new JSONObject();
            MediaMetadata cm = current == null ? null : current.getMetadata();
            boolean sessionHasInfo = cm != null && str(cm, MediaMetadata.METADATA_KEY_TITLE).length() > 0;
            PlaybackState cs = current == null ? null : current.getPlaybackState();
            boolean sessionPlaying = cs != null && cs.getState() == PlaybackState.STATE_PLAYING;
            if (sessionPlaying) sessionPlayAt = System.currentTimeMillis();
            // la radio (ou une appli sans session) a pris la main après la dernière lecture de la session : on l'affiche
            boolean preferFallback = fallback != null && (!sessionHasInfo || (!sessionPlaying && fallbackAt > sessionPlayAt));
            if (preferFallback) {
                // la session (s'il y en a une) est vide : on affiche la radio / la notification
                o = new JSONObject(fallback.toString());
                if (current != null) { PlaybackState st = current.getPlaybackState(); if (st != null && st.getState() != PlaybackState.STATE_NONE) o.put("playing", st.getState() == PlaybackState.STATE_PLAYING); }
                if (!o.has("playing") && guessPlaying != null) o.put("playing", guessPlaying);
                if (o.has("playing") && !"radio".equals(o.optString("kind"))) o.put("playing", corrected(o.optBoolean("playing"), "repli"));
                Hub.emit("media", o.toString());
                return;
            }
            if (current != null) {
                MediaMetadata m = current.getMetadata();
                PlaybackState st = current.getPlaybackState();
                boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
                if (st != null && st.getState() != PlaybackState.STATE_NONE) playing = corrected(playing, current.getPackageName());
                o.put("playing", playing);
                o.put("pkg", current.getPackageName());
                try {
                    PackageManager pm = getPackageManager();
                    o.put("source", pm.getApplicationLabel(pm.getApplicationInfo(current.getPackageName(), 0)).toString());
                } catch (Exception ignored) {}
                if (m != null) {
                    o.put("title", str(m, MediaMetadata.METADATA_KEY_TITLE));
                    o.put("artist", str(m, MediaMetadata.METADATA_KEY_ARTIST));
                    long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    o.put("dur", dur / 1000.0);
                    Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    String key = o.optString("title") + "|" + o.optString("artist");
                    if (art != null && !key.equals(lastArtKey)) {
                        Bitmap s = Bitmap.createScaledBitmap(art, 160, 160 * art.getHeight() / Math.max(1, art.getWidth()), true);
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        s.compress(Bitmap.CompressFormat.JPEG, 82, b);
                        lastArt = "data:image/jpeg;base64," + Base64.encodeToString(b.toByteArray(), Base64.NO_WRAP);
                        lastArtKey = key;
                    } else if (art == null) { lastArt = ""; lastArtKey = key; }
                    if (lastArt.length() > 0) o.put("art", lastArt);
                }
                if (st != null) {
                    long pos = st.getPosition();
                    if (playing) pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                    o.put("pos", Math.max(0, pos / 1000.0));
                }
            }
            if (guessPlaying != null) {
                PlaybackState gs = current == null ? null : current.getPlaybackState();
                if (gs == null || gs.getState() == PlaybackState.STATE_NONE) o.put("playing", guessPlaying);
            }
            Hub.emit("media", o.toString());
        } catch (Exception e) { Hub.log("média push : " + e); }
    }
    private static String str(MediaMetadata m, String k) { CharSequence c = m.getText(k); return c == null ? "" : c.toString(); }

    /** Commande directe sur la session active. Renvoie false si aucune session (repli : touches média). */
    static boolean command(String cmd) {
        MediaController c = current;
        PlaybackState st = c == null ? null : c.getPlaybackState();
        // Pas de session, ou session qui ne donne pas son état (radio d'origine…) : on mémorise un état
        // supposé et on laisse MainActivity envoyer la touche média LECTURE/PAUSE (qui bascule vraiment).
        if (c == null || st == null || st.getState() == PlaybackState.STATE_NONE) {
            Boolean real = audioSeen && (fallback == null || !"radio".equals(fallback.optString("kind"))) ? audioActive() : null;   // v1.26.1
            boolean was = real != null ? real : guessPlaying != null ? guessPlaying : (fallback != null);
            if ("toggle".equals(cmd)) guessPlaying = !was; else if ("play".equals(cmd)) guessPlaying = true; else if ("pause".equals(cmd)) guessPlaying = false;
            if (self != null) self.push(); else if (fallback != null) emitFallbackOnly();
            else Hub.emit("media", "{\"playing\":" + guessPlaying + "}");
            if (self != null) {   // v1.26.1 : revérifié sur le son une fois la touche média prise en compte
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                Runnable r = new Runnable() { public void run() { if (self != null) self.push(); } };
                h.postDelayed(r, 1700); h.postDelayed(r, 3400);
            }
            return false;
        }
        MediaController.TransportControls t = c.getTransportControls();
        boolean playing = st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING;
        playing = corrected(playing, c.getPackageName());      // v1.26.1 : session en retard -> on se fie au son
        Hub.log("MÉDIA : bouton " + cmd + " (" + c.getPackageName() + " : " + (playing ? "lecture" : "pause") + ")");
        switch (cmd) {
            case "next": t.skipToNext(); break;
            case "prev": t.skipToPrevious(); break;
            case "play": t.play(); break;
            case "pause": t.pause(); break;
            default: if (playing) t.pause(); else t.play();
        }
        return true;
    }
}
