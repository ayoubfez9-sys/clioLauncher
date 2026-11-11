package fr.ibiza.launcher;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * v1.15 — ordinateur de bord en direct, sans ouvrir CarCan.
 * CarCan (com.hcn.autocan) écrit dans le journal système chaque morceau reçu du boîtier CAN :
 *   I CanService: rev: 2E41100278....
 * Trame (protocole « 2E » du boîtier) : 2E | cmd | len | données (len octets) | chk, avec chk = (cmd + len + données) ^ 0xFF.
 * Commande 0x41 (profil VW « SimpleSoft », décodage repris de VWDataDecorder.BaseCarInfoXP) :
 *   sous-type 1 : d[3] bit6 = lave-glace bas, bit7 = ceinture, bits0..3 = portes
 *   sous-type 2 : régime d[3..4], vitesse d[5..6] × 0,01 km/h, batterie d[7..8] × 0,01 V,
 *                 extérieur (signé) d[9..10] × 0,1 °C, kilométrage d[11..13], carburant d[14] L
 *   sous-type 3 : d[3] bit7 = alerte carburant, bit6 = alerte batterie
 * Nécessite la permission READ_LOGS (adb shell pm grant fr.ibiza.launcher android.permission.READ_LOGS).
 */
final class LogCan {
    private LogCan() {}
    static volatile long lastFrame = 0;          // dernière trame 0x41 décodée
    static volatile int frames = 0;
    private static boolean started = false;
    private static final JSONObject pending = new JSONObject();
    private static long lastMerge = 0;

    /** des valeurs fraîches arrivent du journal : inutile d'ouvrir CarCan */
    static boolean live() { return System.currentTimeMillis() - lastFrame < 90000; }

    static synchronized void start() {
        if (started || !CanBridge.logsAllowed()) return;
        started = true;
        Thread t = new Thread(new Runnable() { public void run() {
            while (true) {
                try {
                    // tout le tampon puis la suite en direct : au démarrage, on récupère aussitôt les dernières valeurs connues
                    Process pr = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "raw", "-s", "CanService:I"});
                    BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
                    Hub.log("CAN (journal) : lecture en direct active");
                    String line;
                    while ((line = r.readLine()) != null) {
                        int i = line.indexOf("rev:");
                        if (i < 0) continue;
                        feedHex(line.substring(i + 4).trim());
                        flush(false);
                    }
                    pr.destroy();
                } catch (Throwable e) { Hub.log("CAN (journal) : " + e); }
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
            }
        }}, "ibiza-logcan");
        t.setDaemon(true); t.start();
    }

    /* ---------- reconstitution des trames (un « rev » peut couper une trame en deux) ---------- */
    private static final byte[] buf = new byte[300];
    private static int st = 0, len = 0, pos = 0, sum = 0;
    private static void feedHex(String hex) {
        for (int k = 0; k + 1 < hex.length(); k += 2) {
            int b;
            try { b = Integer.parseInt(hex.substring(k, k + 2), 16); } catch (NumberFormatException e) { st = 0; continue; }
            feed(b);
        }
    }
    private static void feed(int b) {
        switch (st) {
            case 0: if (b == 0x2E) { st = 1; pos = 0; sum = 0; } break;
            case 1: buf[pos++] = (byte) b; sum += b; st = 2; break;                       // commande
            case 2: len = b; if (len == 0 || len > buf.length - 3) { st = 0; break; }       // longueur
                buf[pos++] = (byte) b; sum += b; st = 3; break;
            case 3: buf[pos++] = (byte) b; sum += b; if (--len == 0) st = 4; break;         // données
            case 4: st = 0;                                                                 // somme de contrôle
                if (((sum ^ 0xFF) & 0xFF) == b) frame(buf, pos);
                else if (b == 0x2E) { st = 1; pos = 0; sum = 0; }
                break;
        }
    }
    private static int u8(byte[] d, int i) { return d[i] & 0xFF; }
    private static int u16(byte[] d, int i) { return (u8(d, i) << 8) | u8(d, i + 1); }

    private static void frame(byte[] d, int n) {
        if (n < 3 || u8(d, 0) != 0x41) return;
        try {
            int sub = u8(d, 2);
            if (sub == 2 && n >= 15) {
                pending.put("rpm", u16(d, 3));
                pending.put("speed", Math.round(u16(d, 5) * 0.01 * 10) / 10.0);
                double v = u16(d, 7) * 0.01; if (v >= 6 && v <= 18) pending.put("battery", Math.round(v * 10) / 10.0);
                double t = ((short) u16(d, 9)) * 0.1; if (t >= -50 && t <= 77.5) pending.put("outside", Math.round(t * 10) / 10.0);
                int odo = (u8(d, 11) << 16) | (u8(d, 12) << 8) | u8(d, 13); if (odo > 0) pending.put("odo", odo);
                int fuel = u8(d, 14); if (fuel > 0) pending.put("fuel", fuel);
                lastFrame = System.currentTimeMillis();
                if (frames++ == 0) Hub.log("CAN (journal) : première trame ordinateur de bord décodée");
            } else if (sub == 1 && n >= 4) {
                pending.put("washer", ((u8(d, 3) >> 6) & 1) == 1 ? "Niveau bas" : "Normal");
            } else if (sub == 3 && n >= 4) {
                pending.put("fuelWarn", ((u8(d, 3) >> 7) & 1) == 1);
                pending.put("batteryWarn", ((u8(d, 3) >> 6) & 1) == 1);
            }
        } catch (Exception ignored) {}
    }

    /** envoie au lanceur au plus toutes les 2 s (le régime change sans arrêt) */
    private static void flush(boolean force) {
        long now = System.currentTimeMillis();
        if (pending.length() == 0 || (!force && now - lastMerge < 2000)) return;
        lastMerge = now;
        try {
            JSONObject p = new JSONObject(pending.toString());
            java.util.Iterator<String> it = p.keys(); java.util.List<String> ks = new java.util.ArrayList<>();
            while (it.hasNext()) ks.add(it.next());
            for (String k : ks) pending.remove(k);
            Hub.mergeCarQuiet(p, "CAN (journal)");
        } catch (Exception ignored) {}
    }
}
