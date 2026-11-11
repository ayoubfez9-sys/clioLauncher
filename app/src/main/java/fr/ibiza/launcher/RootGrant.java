package fr.ibiza.launcher;

import android.content.Context;

import java.io.DataOutputStream;
import java.util.concurrent.TimeUnit;

/**
 * v1.15.1 — si l'autoradio a un accès « root » (su), le lanceur s'accorde lui-même READ_LOGS
 * (sinon il faut ADB). Sans root : échec silencieux, rien n'est modifié.
 * Après l'octroi, Android n'applique la permission qu'au redémarrage du processus : le lanceur se relance seul.
 */
final class RootGrant {
    private RootGrant() {}
    static volatile String last = "";

    static void tryAsync(final Context c, final boolean manual) {
        if (CanBridge.logsAllowed()) { last = "déjà autorisé"; return; }
        final android.content.SharedPreferences sp = c.getSharedPreferences("ibz", Context.MODE_PRIVATE);
        long t = sp.getLong("rootTry", 0);
        if (!manual && System.currentTimeMillis() - t < 24 * 3600 * 1000L) return;   // une tentative auto par jour
        sp.edit().putLong("rootTry", System.currentTimeMillis()).apply();
        new Thread(new Runnable() { public void run() {
            String r = grant(c.getPackageName());
            last = r;
            Hub.log("Root : " + r);
            if (manual) Hub.emit("toast", org.json.JSONObject.quote(r));
            if (r.startsWith("OK")) {
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                android.os.Process.killProcess(android.os.Process.myPid());   // relancé aussitôt (c'est le lanceur par défaut)
            }
        }}, "ibiza-root").start();
    }

    private static String grant(String pkg) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("pm grant " + pkg + " android.permission.READ_LOGS\n");
            os.writeBytes("exit\n"); os.flush();
            boolean done;
            if (android.os.Build.VERSION.SDK_INT >= 26) done = p.waitFor(25, TimeUnit.SECONDS); else { p.waitFor(); done = true; }
            if (!done) { p.destroy(); return "pas de réponse de l'accès root (fenêtre refusée ?)"; }
            int code = p.exitValue();
            return code == 0 ? "OK : autorisation accordée, le lanceur redémarre" : "accès root refusé (code " + code + ")";
        } catch (java.io.IOException e) {
            return "pas d'accès root sur cet autoradio";
        } catch (Throwable e) {
            if (p != null) p.destroy();
            return "échec : " + e.getMessage();
        }
    }
}
