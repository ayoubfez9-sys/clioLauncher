package fr.ibiza.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

/**
 * v1.24.3 : autorisation « Lecture CAN (accessibilité) ».
 *
 * Relevé sur un poste alps 825X_Pro (Autochips, Android 9, firmware eng.root.20200426, écran 1920x720) : la page
 * Accessibilité a été retirée des Paramètres. ACTION_ACCESSIBILITY_SETTINGS ne mène nulle part et l'ancien repli
 * ouvrait l'accueil des Paramètres : impossible d'autoriser le lanceur, donc aucune porte lue.
 *
 *  1. Si le lanceur a la permission WRITE_SECURE_SETTINGS (accordée une seule fois par adb, sans root) :
 *       adb shell pm grant fr.ibiza.launcher android.permission.WRITE_SECURE_SETTINGS
 *     il s'autorise lui-même, et se réautorise au démarrage si le poste efface le réglage.
 *  2. Sinon, on cherche une vraie page Accessibilité (action standard ou activité connue des Paramètres).
 *  3. Sinon, « missing » : l'interface explique les solutions au lieu d'ouvrir l'accueil des Paramètres.
 */
final class AccessHelper {
    private AccessHelper() {}

    static String component(Context c) { return new ComponentName(c, CanWatchService.class).flattenToString(); }

    static boolean enabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(c.getPackageName());
    }

    static boolean canSelfEnable(Context c) {
        return c.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    /** active le service d'accessibilité du lanceur sans passer par les Paramètres ; vrai si c'est fait */
    static boolean selfEnable(Context c) {
        if (enabled(c)) return true;
        if (!canSelfEnable(c)) return false;
        try {
            String me = component(c);
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String v = cur == null || cur.trim().isEmpty() ? me : cur + ":" + me;
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, v);
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Hub.log("ACCESSIBILITÉ : activée par le lanceur (permission WRITE_SECURE_SETTINGS accordée par adb)");
            return true;
        } catch (Throwable e) {
            Hub.log("ACCESSIBILITÉ : activation directe impossible : " + e);
            return false;
        }
    }

    /** une page Accessibilité qui existe vraiment sur ce poste, ou null */
    static Intent settingsIntent(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent[] tries = {
            new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            new Intent().setClassName("com.android.settings", "com.android.settings.Settings$AccessibilitySettingsActivity"),
            new Intent().setClassName("com.android.settings", "com.android.settings.accessibility.AccessibilitySettingsActivity"),
        };
        for (Intent i : tries) {
            try {
                ResolveInfo r = pm.resolveActivity(i, 0);
                ActivityInfo a = r == null ? null : r.activityInfo;
                if (a == null || !a.exported || !a.enabled) continue;
                // certains postes renvoient l'action vers l'accueil des Paramètres : on ne veut qu'une page Accessibilité
                if (!a.name.toLowerCase(java.util.Locale.US).contains("accessib")) continue;
                return new Intent(i).setClassName(a.packageName, a.name);
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
