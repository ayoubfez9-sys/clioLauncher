package fr.ibiza.launcher;

import org.json.JSONObject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprétation des libellés (écrans de l'appli CAN, noms de champs de diffusions, propriétés système).
 * Clés produites (événement « car » vers l'interface) :
 *   outside, oil, coolant (°C) · battery (V) · insp_km, insp_days, oil_km, oil_days (prochaine révision / vidange)
 *   range (km) · cons (L/100) · avg (km/h) · odo (km) · fuel (L) · rpm · speed (km/h)
 */
final class CarText {
    private CarText() {}

    /* ---------- portes : index 0..5 = fl fr rl rr trunk hood ---------- */
    private static final Pattern P_FL = Pattern.compile("(front_?left|left_?front|\\bfl\\b|_fl\\b|_lf\\b|\\blf_|\\bfl_|zuoqian|qianzuo|door_?1\\b|door_?lf|door_?fl|lfdoor|fldoor|左前)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_FR = Pattern.compile("(front_?right|right_?front|\\bfr\\b|_fr\\b|_rf\\b|\\brf_|\\bfr_|youqian|qianyou|door_?2\\b|door_?rf|door_?fr|rfdoor|frdoor|右前)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_RL = Pattern.compile("(rear_?left|left_?rear|back_?left|left_?back|_rl\\b|_lr\\b|\\brl_|\\blr_|zuohou|houzuo|door_?3\\b|door_?lr|door_?rl|lrdoor|rldoor|左后)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_RR = Pattern.compile("(rear_?right|right_?rear|back_?right|right_?back|_rr\\b|\\brr_|youhou|houyou|door_?4\\b|door_?rr|rrdoor|右后)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_TRUNK = Pattern.compile("(trunk|tail_?gate|\\bboot\\b|back_?door|houbei|houmen|hou_?gai|后备|尾门)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_HOOD = Pattern.compile("(hood|bonnet|engine_?cover|front_?cover|qiangai|yinqing|引擎盖|前盖)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOORWORD = Pattern.compile("door|men|trunk|hood|bonnet|tail|boot|门|盖", Pattern.CASE_INSENSITIVE);

    static int doorIndex(String key) {
        if (key == null || !DOORWORD.matcher(key).find()) return -1;
        if (P_TRUNK.matcher(key).find()) return 4;
        if (P_HOOD.matcher(key).find()) return 5;
        if (P_FL.matcher(key).find()) return 0;
        if (P_FR.matcher(key).find()) return 1;
        if (P_RL.matcher(key).find()) return 2;
        if (P_RR.matcher(key).find()) return 3;
        return -1;
    }

    /* ---------- libellés -> clé de donnée ---------- */
    private static final Object[][] LABELS = {
            {"oil_km", "(vidange|oil ?change|oil ?service|ölwechsel|service huile|entretien huile)", "km"},
            {"oil_days", "(vidange|oil ?change|oil ?service|ölwechsel|service huile|entretien huile)", "days"},
            {"insp_km", "(r[ée]vision|inspection|entretien|maintenance|service|保养)", "km"},
            {"insp_days", "(r[ée]vision|inspection|entretien|maintenance|service|保养)", "days"},
            {"outside", "(ext[ée]rieur|outside|outdoor|out_?temp|ambient|ambiante|室外|车外)", "deg"},
            {"oil", "(huile|\\boil\\b|oil_?temp|机油)", "deg"},
            {"coolant", "(refroidissement|coolant|liquide|\\beau\\b|water|水温)", "deg"},
            {"battery", "(batterie|battery|tension|voltage|volt|电压)", "volt"},
            {"range", "(autonomie|\\brange\\b|endurance|续航)", "km"},
            {"cons", "(consommation|consumption|fuel_?cons|l/100|油耗)", "cons"},
            {"avg", "(vitesse moyenne|average speed|avg_?speed|平均)", "kmh"},
            {"odo", "(kilom[ée]trage|odom|mileage|total km|compteur|里程)", "km"},
            {"fuel", "(carburant|r[ée]servoir|fuel_?level|\\bfuel\\b|油量)", "litre"},
            {"rpm", "(r[ée]gime|\\brpm\\b|tr/min|转速)", "num"},
            {"speed", "(^speed$|vitesse$|车速)", "kmh"},
    };
    private static final Pattern[] LP = new Pattern[LABELS.length];
    static { for (int i = 0; i < LABELS.length; i++) LP[i] = Pattern.compile((String) LABELS[i][1], Pattern.CASE_INSENSITIVE); }

    /** nom de champ (diffusion / propriété) -> clé, sans tenir compte de l'unité */
    static String keyFor(String k) {
        if (k == null) return null;
        String s = k.replace('_', ' ');
        for (int i = 0; i < LABELS.length; i++) {
            if (!LP[i].matcher(s).find() && !LP[i].matcher(k).find()) continue;
            String key = (String) LABELS[i][0];
            if (key.endsWith("_days") && !k.toLowerCase().matches(".*(day|jour|time).*")) continue;
            if (key.endsWith("_km") && k.toLowerCase().matches(".*(day|jour).*")) continue;
            if ((key.equals("oil") || key.equals("coolant")) && !k.toLowerCase().matches(".*(temp|tmp|t$).*")) continue;
            return key;
        }
        return null;
    }

    private static final Pattern NUM = Pattern.compile("(-?\\d{1,3}(?:[ .\\u00a0\\u202f]\\d{3})+|-?\\d+(?:[.,]\\d+)?)");
    private static Double num(String s) {
        try { return Double.parseDouble(s.replaceAll("[ .\\u00a0\\u202f](?=\\d{3}(\\D|$))", "").replace(',', '.')); } catch (Exception e) { return null; }
    }
    private static Double valueFor(String unit, String v) {
        String u;
        switch (unit) {
            case "deg": u = "\\s*(°|℃|º|deg|C\\b)"; break;
            case "volt": u = "\\s*(V\\b|volt)"; break;
            case "km": u = "\\s*(km\\b|kilom)"; break;
            case "days": u = "\\s*(j\\b|jours?|days?|d\\b|天)"; break;
            case "cons": u = "\\s*(l/100|L/100)"; break;
            case "kmh": u = "\\s*(km/h|kmh)"; break;
            case "litre": u = "\\s*(l\\b|L\\b|litres?)"; break;      // v1.27 : « % » retiré (45 % de carburant devenait 45 L)
            default: u = "";
        }
        Matcher m = Pattern.compile(NUM.pattern() + u, Pattern.CASE_INSENSITIVE).matcher(v);
        if (m.find()) return num(m.group(1));
        return null;
    }

    /** Textes d'un écran (dans l'ordre d'affichage) -> données. Un libellé est suivi de sa valeur dans le même texte ou les 2 suivants. */
    static JSONObject parseScreen(List<String> texts) {
        JSONObject out = new JSONObject();
        for (int i = 0; i < texts.size(); i++) {
            String L = texts.get(i);
            for (int k = 0; k < LABELS.length; k++) {
                if (!LP[k].matcher(L).find()) continue;
                String key = (String) LABELS[k][0];
                if (out.has(key)) continue;
                StringBuilder v = new StringBuilder(L);
                for (int j = i + 1; j < Math.min(texts.size(), i + 3); j++) {
                    String t = texts.get(j);
                    boolean otherLabel = false;
                    for (Pattern p : LP) if (p.matcher(t).find() && !t.matches(".*\\d.*")) { otherLabel = true; break; }
                    if (otherLabel) break;
                    v.append(' ').append(t);
                }
                Double d = valueFor((String) LABELS[k][2], v.toString());
                if (d == null) continue;
                // garde-fous de vraisemblance
                if (key.equals("outside") && (d < -45 || d > 65)) continue;
                if ((key.equals("oil") || key.equals("coolant")) && (d < -40 || d > 160)) continue;
                if (key.equals("battery") && (d < 6 || d > 18)) continue;
                try { out.put(key, d); } catch (Exception ignored) {}
            }
        }
        return out;
    }

    /* ---------- radio ---------- */
    private static final Pattern FREQ = Pattern.compile("(?<![\\d.,])(\\d{2,3}[.,]\\d{1,2}|\\d{3,5})\\s*(MHz|kHz|KHz)?(?![\\d])");
    static String prettyFreq(String f) {
        Matcher m = FREQ.matcher(f);
        if (!m.find()) return f;
        String n = m.group(1).replace(',', '.'), unit = m.group(2) == null ? "" : m.group(2).toLowerCase();
        boolean dec = n.contains(".");
        try {
            double d = Double.parseDouble(n);
            if (!dec && d >= 7600 && d <= 10800) d = d / 100.0;    // 10150 -> 101.5
            else if (!dec && unit.length() == 0) return f;          // un entier seul (volume, heure…) n'est pas une fréquence
            if (d >= 76 && d <= 108 && !unit.equals("khz")) return "FM " + String.format(java.util.Locale.FRANCE, "%.1f", d) + " MHz";
            if (!dec && d >= 522 && d <= 1710 && unit.equals("khz")) return "AM " + (int) d + " kHz";
        } catch (Exception ignored) {}
        return f;
    }
    /** Écran de la radio : ids + textes. Renvoie {freq, ps, rt} ou null. */
    static String[] parseRadio(List<String[]> idText) {
        String freq = null, ps = null, rt = null;
        for (String[] it : idText) {
            String id = it[0] == null ? "" : it[0].toLowerCase(), t = it[1];
            if (t == null || t.length() == 0) continue;
            if (freq == null && (id.contains("freq") || id.contains("fre_") || id.matches(".*(fm|am)_?(num|text|value).*"))) { String p = prettyFreq(t); if (p.startsWith("FM") || p.startsWith("AM")) freq = p; }
            else if (ps == null && id.matches(".*(\\bps\\b|_ps|ps_|psname|station|rds_?name|channel_?name|program).*") && t.length() <= 24) ps = t;
            else if (rt == null && id.matches(".*(rt|radiotext|rds_?text|rds_?info).*") && t.length() > 3) rt = t;
        }
        if (freq == null) for (String[] it : idText) { String t = it[1]; if (t == null || t.length() > 14) continue;
            if (it[0] != null && it[0].toLowerCase().matches(".*(preset|list|item|memo|favo|channel_?\\d).*")) continue; /* mémoires, pas la station en cours */
            String p = prettyFreq(t); if (!p.equals(t)) { freq = p; break; } }
        if (freq == null && ps == null) return null;
        return new String[]{freq, ps, rt};
    }

    /* ---------- écran « CarCan » (com.hcn.autocan, Junsun MT8163) ----------
       Grille 1 : odbtextName puis odbtextValue (« Voltage de batterie », « 14.3V »).
       Grille 2 : textValue, textUnit puis textName (« 831 », « RPM », « La vitesse du moteur »). */
    static JSONObject parseHcn(List<String[]> pairs) {
        JSONObject out = new JSONObject();
        String name = null, val = null, unit = "";
        for (String[] it : pairs) {
            String id = it[0] == null ? "" : it[0], t = it[1] == null ? "" : it[1].trim();
            if (id.equals("odbtextName")) name = t;
            else if (id.equals("odbtextValue")) { if (name != null) hcnPut(out, name, t); name = null; }
            else if (id.equals("textValue")) { val = t; unit = ""; }
            else if (id.equals("textUnit")) unit = t;
            else if (id.equals("textName")) { if (val != null) hcnPut(out, t, val + " " + unit); val = null; unit = ""; }
            else if (id.equals("warningtext") && t.length() > 0) { try { out.put("warning", t); } catch (Exception ignored) {} }
        }
        return out;
    }
    private static void hcnPut(JSONObject out, String name, String value) {
        String n = name.toLowerCase(java.util.Locale.FRANCE);
        Matcher m = NUM.matcher(value); Double d = m.find() ? num(m.group(1)) : null;
        String key = null; boolean text = false;
        if (n.matches(".*(huile r[ée]siduelle|carburant|essence|fuel|r[ée]servoir).*")) key = "fuel";
        else if (n.matches(".*(batterie|voltage|tension).*")) key = "battery";
        else if (n.matches(".*(vitesse du moteur|r[ée]gime|rpm|tr/min).*")) key = "rpm";
        else if (n.matches(".*(rapidit[ée]|vitesse).*") && !n.contains("moyenne")) key = "speed";
        else if (n.matches(".*(kilom[ée]trage|odom).*")) key = "odo";
        else if (n.matches(".*(temp\\. ?ext|ext[ée]rieur).*")) key = "outside";
        else if (n.matches(".*(huile|oil).*") && n.matches(".*(temp).*")) key = "oil";
        else if (n.matches(".*(refroid|eau|coolant).*")) key = "coolant";
        else if (n.matches(".*(autonomie|range).*")) key = "range";
        else if (n.matches(".*(consommation).*")) key = "cons";
        else if (n.matches(".*(moyenne).*")) key = "avg";
        else if (n.matches(".*(frein).*")) { key = "handbrake"; text = true; }
        else if (n.matches(".*(ceinture).*")) { key = "belt"; text = true; }
        else if (n.matches(".*(nettoyant|lave|washer).*")) { key = "washer"; text = true; }
        if (key == null) return;
        try {
            if (text) { out.put(key, value); return; }
            if (d == null) return;
            if (key.equals("fuel") && d == 0) return;                 // le boîtier envoie parfois 0 L entre deux vraies valeurs
            if (key.equals("battery") && (d < 6 || d > 18)) return;
            if (key.equals("outside") && (d < -45 || d > 65)) return;
            out.put(key, d);
        } catch (Exception ignored) {}
    }
}
