package fr.ibiza.launcher;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.27 : lecture d'un écran « infos véhicule » de N'IMPORTE QUELLE appli CAN, sans rien connaître d'elle à l'avance.
 *
 * Chaque autoradio chinois a sa propre appli CAN (CarCan, CANBUS, Car Info, « Véhicule »…), avec ses propres
 * identifiants de vues, sa mise en page et ses traductions. Plutôt que d'écrire un lecteur par appli, on travaille sur
 * ce que l'accessibilité donne partout : des textes et leur position à l'écran.
 *
 *  1. extract()  : repère les valeurs (« 14.3V », « 831 », « -- »), leur unité (dans le même texte ou juste à côté)
 *                  et le libellé le plus proche (à gauche sur la même ligne, au-dessus, en dessous, ou dans le même
 *                  texte « Batterie : 14.3 V »), puis propose une donnée (carburant, batterie…) d'après le libellé et
 *                  l'unité, dans une dizaine de langues, traductions approximatives des applis chinoises comprises.
 *  2. fieldOf()  : fabrique un repère durable pour une valeur choisie : libellé + décalage, identifiant + rang, position.
 *  3. read()     : retrouve la valeur plus tard avec ce repère, même si la page a un peu bougé, et la convertit
 *                  (mph, miles, °F, gallons) vers les unités du lanceur.
 *
 * Classe sans aucune dépendance Android : elle se teste sur un ordinateur (tools/test/ScreenReadTest.java).
 */
final class ScreenRead {
    private ScreenRead() {}

    /** un texte visible à l'écran */
    static final class Item {
        String id = "", text = "";
        int l, t, r, b;          // cadre à l'écran (pixels)
        int p1 = -1, p2 = -1;    // numéro du conteneur parent / grand-parent (même case d'une grille = même parent)
        int ord, same;           // rang parmi les textes de même identifiant, et combien ils sont (remplis par prep)
        Item() {}
        Item(String id, String text, int l, int t, int r, int b, int p1, int p2) { this.id = id == null ? "" : id; this.text = text; this.l = l; this.t = t; this.r = r; this.b = b; this.p1 = p1; this.p2 = p2; }
        double cx() { return (l + r) / 2.0; }
        double cy() { return (t + b) / 2.0; }
        int w() { return Math.max(1, r - l); }
        int h() { return Math.max(1, b - t); }
    }

    /** une valeur trouvée sur l'écran, avec le libellé et la donnée proposés */
    static final class Cand {
        int vi, li = -1;            // index du texte valeur / du texte libellé (-1 : aucun)
        boolean inline;             // libellé et valeur dans le même texte
        String raw = "", label = "", unit = "", key;
        Double num;                 // null : valeur absente au moment de la capture (« -- »)
        double cost;
    }

    /** repère durable d'une donnée dans une page */
    static final class Field {
        String key = "", id = "", lab = "", u = "";
        int ord, same;
        double rx, ry, px, py;      // décalage libellé -> valeur et position de la valeur, en fraction de l'écran
        boolean inline, txt;
    }

    static final Set<String> TEXT_KEYS = new HashSet<>(java.util.Arrays.asList("handbrake", "belt", "washer"));
    static final Set<String> KM_KEYS = new HashSet<>(java.util.Arrays.asList("odo", "range", "insp_km", "oil_km", "rpm"));

    /* =============================== texte =============================== */
    static String norm(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.toLowerCase(Locale.ROOT).replaceAll("[_\\-/|·•()\\[\\]{}\"'’`.,;!?*]+", " ").replaceAll("[:：=]+\\s*$", "").replaceAll("\\s+", " ").trim();
    }

    private static final String NUM = "[-+\\u2212]?\\d{1,3}(?:[ \\u00a0\\u202f'][0-9]{3})+(?:[.,]\\d+)?|[-+\\u2212]?\\d{1,3}(?:,\\d{3})+\\.\\d+|[-+\\u2212]?\\d{1,3}(?:\\.\\d{3})+,\\d+|[-+\\u2212]?\\d+(?:[.,]\\d+)?";
    private static final Pattern VALUE = Pattern.compile("^\\s*[<>≈~]?\\s*(" + NUM + ")\\s*(.{0,12}?)\\s*$");
    private static final Pattern HOLE = Pattern.compile("^\\s*(-{2,5}|[—–]{1,3}|_{2,5}|n/?a|\\?{1,3}|\\*{1,4}|\\.{2,4})\\s*(\\S{1,8})?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE = Pattern.compile("^(.{2,40}?)\\s*[:：=]\\s*(.{1,24})$");
    private static final Pattern INLINE_SP = Pattern.compile("^(\\p{L}[\\p{L}\\p{M} .'’/()\\-]{2,40}?)\\s+((?:" + NUM + ")\\s*\\S{0,8})$");
    private static final Pattern LETTER = Pattern.compile("\\p{L}");

    /** {nombre, unité} si le texte est une valeur (« 14.3V », « 831 », « 84 213 km »), sinon null */
    static String[] splitValue(String s) {
        if (s == null) return null;
        Matcher m = VALUE.matcher(s);
        if (!m.matches()) return null;
        String rest = m.group(2) == null ? "" : m.group(2).trim();
        if (rest.length() > 0) {
            if (unitClass(rest).isEmpty() && (rest.matches(".*\\d.*") || rest.length() > 6 || !LETTER.matcher(rest).find() && !rest.matches("[°%º]"))) return null;
        }
        return new String[]{ m.group(1), rest };
    }
    static boolean isHole(String s) { return s != null && HOLE.matcher(s).matches(); }

    /** nombre affiché -> valeur ; thousands : « 1.234 » et « 1,234 » sont des milliers (km, tr/min), pas des décimales */
    static Double parseNum(String s, boolean thousands) {
        if (s == null) return null;
        String t = s.trim().replace('−', '-').replaceAll("[ \\u00a0\\u202f']", "");
        if (thousands && t.matches("-?\\d{1,3}([.,]\\d{3})+")) t = t.replaceAll("[.,]", "");
        else if (t.matches("-?\\d{1,3}(,\\d{3})+\\.\\d+")) t = t.replace(",", "");
        else if (t.matches("-?\\d{1,3}(\\.\\d{3})+,\\d+")) t = t.replace(".", "").replace(',', '.');
        else t = t.replace(',', '.');
        try { return Double.parseDouble(t); } catch (Exception e) { return null; }
    }

    /* =============================== unités =============================== */
    private static final Object[][] UNITS = {
        {"kmh", "km/?h|kph|км/ч|公里/小时|公里/时|千米/时"}, {"mph", "mph|mi/h"},
        {"rpm", "rpm|r/min|tr/min|tr/mn|t/min|u/min|1/min|об/мин|转/分|转"},
        {"cons", "l/100 ?km|l/100|л/100 ?км|升/百公里"}, {"kml", "km/l"}, {"mpg", "mpg"},
        {"volt", "v|volts?|в|伏"}, {"degC", "°c|℃|°|º|ºc|c|度"}, {"degF", "°f|℉|ºf"},
        {"km", "km|kms|км|公里|千米"}, {"mi", "mi|miles?"},
        {"litre", "l|ltr?|litres?|liters?|л|升"}, {"gal", "gal|gallons?"}, {"pct", "%|％"},
        {"days", "j|jours?|days?|d|tage?|dias?|giorni|дн|дней|天"}, {"press", "bar|kpa|psi"}, {"time", "h|min|s"}
    };
    private static final Pattern[] UP = new Pattern[UNITS.length];
    static { for (int i = 0; i < UNITS.length; i++) UP[i] = Pattern.compile("^(?:" + UNITS[i][1] + ")$"); }
    /** famille d'unité (« kmh », « volt »…), "" si le texte n'est pas une unité connue */
    static String unitClass(String u) {
        if (u == null) return "";
        String s = u.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "").replaceAll("\\.$", "");
        if (s.isEmpty() || s.length() > 10) return "";
        for (int i = 0; i < UNITS.length; i++) if (UP[i].matcher(s).matches()) return (String) UNITS[i][0];
        return "";
    }

    /** unités acceptables pour une donnée ("" = pas d'unité affichée) */
    private static boolean unitOk(String key, String uc) {
        if (uc.isEmpty() || key == null) return true;
        switch (key) {
            case "outside": case "oil": case "coolant": return uc.equals("degC") || uc.equals("degF");
            case "battery": return uc.equals("volt");
            case "rpm": return uc.equals("rpm");
            case "speed": case "avg": return uc.equals("kmh") || uc.equals("mph");
            case "odo": case "range": case "insp_km": case "oil_km": return uc.equals("km") || uc.equals("mi");
            case "insp_days": case "oil_days": return uc.equals("days");
            case "fuel": return uc.equals("litre") || uc.equals("gal");
            case "fuel_pct": return uc.equals("pct");
            case "cons": return uc.equals("cons") || uc.equals("kml");
            default: return true;
        }
    }
    /** conversion vers les unités du lanceur (km, km/h, °C, L, L/100) */
    static double convert(String key, double v, String uc) {
        if (uc == null) return v;
        switch (uc) {
            case "mph": case "mi": return Math.round(v * 1.609344 * 10) / 10.0;
            case "degF": return Math.round((v - 32) / 1.8 * 10) / 10.0;
            case "gal": return Math.round(v * 3.785 * 10) / 10.0;
            case "kml": return v > 0 ? Math.round(100 / v * 10) / 10.0 : v;
            default: return v;
        }
    }
    /** garde-fous : une valeur absurde (mauvaise case lue, valeur de remplissage du boîtier) n'est pas transmise */
    static boolean plausible(String key, double d) {
        switch (key) {
            case "outside": return d >= -45 && d <= 65;
            case "oil": case "coolant": return d >= -40 && d <= 160;
            case "battery": return d >= 6 && d <= 32;
            case "rpm": return d >= 0 && d <= 12000;
            case "speed": case "avg": return d >= 0 && d <= 400;
            case "odo": return d >= 1 && d <= 3000000;
            case "range": return d >= 0 && d <= 3000;
            case "insp_km": case "oil_km": return d >= -100000 && d <= 100000;
            case "insp_days": case "oil_days": return d >= -3000 && d <= 3000;
            case "fuel": return d > 0 && d <= 250;                 // 0 L : valeur de remplissage envoyée entre deux vraies valeurs
            case "fuel_pct": return d >= 0 && d <= 100;
            case "cons": return d >= 0 && d <= 60;
            default: return true;
        }
    }

    /* =============================== libellé -> donnée =============================== */
    /* Libellés normalisés (minuscules, sans accents). L'ordre compte : le plus précis d'abord. Les traductions bancales
       des applis chinoises sont voulues (« huile résiduelle » = carburant, « rapidité » = vitesse…). */
    private static final Pattern SKIP = Pattern.compile("\\btrip\\b|trajet|depuis (le )?(depart|plein)|since|小计|instant|momentan|actuelle|current cons|мгновен|瞬时|radar|pression|pressure|druck|tpms|pneu|tire|tyre|volume|balance|fader");
    private static final Object[][] KEYS = {
        {"oil_km", "vidange|oil (change|service)|olwechsel|olservice|cambio (de )?aceite|cambio olio|troca de oleo|замен\\S* масла|wymian\\S* oleju|yag degisim|换油"},
        {"insp_km", "revision|inspection|inspektion|entretien|maintenance|\\bservice\\b|wartung|mantenimiento|manutenzione|tagliando|revisao|przeglad|bak[ıi]m|保养|техобслуж"},
        {"avg", "vitesse moy|(average|avg|mean) speed|speed (avg|average)|durchschnittsgeschw|velocidad media|velocita media|velocidade media|средн\\S* скорост|srednia predk|ortalama h[ıi]z|平均(车速|速度|时速)"},
        {"cons", "consommation|consumption|consumo|verbrauch|fuel (cons|econ)|расход|zuzycie|tuketim|油耗|l 100"},
        {"range", "autonomie|\\brange\\b|reichweite|autonomia|distance to empty|\\bdte\\b|remaining (distance|mileage|km)|distance restante|km restants?|kilometres restants?|запас хода|zasieg|menzil|续航|可行驶|可续航"},
        {"odo", "kilometrage|odomet|\\bodo\\b|total (mileage|distance|km)|mileage|kilometerstand|gesamt(strecke|kilometer|km)|kilometraje|chilometr|quilometragem|пробег|przebieg|toplam (km|mesafe|yol)|总里程|里程|distance totale|compteur"},
        {"rpm", "regime|vitesse (du |de )?moteur|engine speed|rotation|\\brpm\\b|tr min|drehzahl|revoluciones|\\bgiri\\b|rotac|оборот|obroty|devir|转速|tachomet|compte tours"},
        {"speed", "vitesse|speed|rapidite|geschwindigkeit|velocidad|velocita|velocidade|скорость|predkosc|\\bh[ıi]z\\b|车速|时速"},
        {"battery", "batterie|battery|voltage|tension|\\bvolt|spannung|bateria|batteria|voltaje|напряжен|аккумулятор|akumulator|napiecie|\\baku\\b|电压|电瓶|蓄电池"},
        {"outside", "exterieur|\\bext\\b|outside|outdoor|ambient|ambiante|aussen|außen|exterior|estern|наружн|за бортом|снаружи|zewn|d[ıi]s (s[ıi]cakl[ıi]k|hava)|室外|车外|外温|out ?temp"},
        {"fuel", "carburant|essence|gasoil|gazole|reservoir|\\bfuel\\b|kraftstoff|\\btank|combustible|carburante|combustivel|топлив|\\bбак|paliw|yak[ıi]t|燃油|油量|huile residuelle|(remaining|residual|rest|surplus) oil|oil (mass|quantity|volume|level|remaining|left|amount)"},
        {"coolant", "refroidissement|coolant|temp\\S* (d ?)?eau|eau moteur|water ?temp|\\beau\\b|kuhlmittel|kuhlwasser|refrigerante|охлажд|ch[lł]odz|sogutma|水温|冷却|engine temp|temp\\S* (du )?moteur|motortemp"},
        {"oil", "huile|\\boil\\b|\\bol\\b|oltemp|aceite|\\bolio\\b|\\boleo\\b|масл|\\bolej|\\byag\\b|机油|油温"},
        {"handbrake", "frein a main|frein de (parking|stationnement)|hand ?brake|parking brake|handbremse|feststellbremse|freno de mano|freno a mano|ручн\\S* тормоз|стояночн|手刹|驻车"},
        {"belt", "ceinture|seat ?belt|safety belt|\\bgurt|cinturon|cintura|ремен|pas\\S* bezp|安全带"},
        {"washer", "lave glace|nettoyant|washer|wischwasser|waschwasser|lavavetri|limpiaparabrisas|омыва|清洗液|玻璃水"},
    };
    private static final Pattern[] KP = new Pattern[KEYS.length];
    static { for (int i = 0; i < KEYS.length; i++) KP[i] = Pattern.compile((String) KEYS[i][1]); }

    /** donnée proposée pour un libellé (déjà affiché tel quel par l'appli) et une famille d'unité ; null si rien de sûr */
    static String guessKey(String label, String uc) {
        String n = norm(label);
        if (uc == null) uc = "";
        if (!n.isEmpty() && !SKIP.matcher(n).find()) {
            for (int i = 0; i < KEYS.length; i++) {
                if (!KP[i].matcher(n).find()) continue;
                String k = (String) KEYS[i][0];
                if ((k.equals("insp_km") || k.equals("oil_km")) && (uc.equals("days") || n.matches(".*\\b(jours?|days?|tage|dias|дн\\S*|天)\\b.*"))) k = k.replace("_km", "_days");
                if (k.equals("fuel") && uc.equals("pct")) k = "fuel_pct";
                if (k.equals("fuel") && (uc.equals("km") || uc.equals("mi"))) k = "range";             // « fuel range »
                if (k.equals("oil") && (uc.equals("litre") || uc.equals("pct") || uc.equals("gal"))) k = uc.equals("pct") ? "fuel_pct" : "fuel";   // « oil » = carburant dans beaucoup de traductions
                if (k.equals("battery") && uc.equals("pct")) continue;
                if (k.equals("speed") && uc.equals("rpm")) k = "rpm";
                if (!unitOk(k, uc)) continue;
                return k;
            }
            return null;
        }
        if (!n.isEmpty()) return null;
        switch (uc) {              // pas de libellé (pictogramme) : seules les unités sans ambiguïté
            case "volt": return "battery";
            case "rpm": return "rpm";
            case "cons": return "cons";
            default: return null;
        }
    }

    /* =============================== extraction =============================== */
    /** rang de chaque texte parmi ceux de même identifiant */
    static void prep(List<Item> items) {
        Map<String, Integer> n = new HashMap<>();
        for (Item it : items) { if (it.id == null) it.id = ""; if (it.id.isEmpty()) { it.ord = 0; continue; } Integer c = n.get(it.id); it.ord = c == null ? 0 : c; n.put(it.id, it.ord + 1); }
        for (Item it : items) it.same = it.id.isEmpty() ? 0 : n.get(it.id);
    }

    private static final Pattern LABEL_ID = Pattern.compile("name|title|label|key|desc|tip|caption", Pattern.CASE_INSENSITIVE);
    private static final int VAL = 1, HOL = 2, UNI = 3, LAB = 4, INL = 5;

    private static int kind(Item it, String[] inl) {
        String s = it.text == null ? "" : it.text.trim();
        if (s.isEmpty() || s.length() > 60) return 0;
        if (splitValue(s) != null) return VAL;
        if (isHole(s)) return HOL;
        if (!unitClass(s).isEmpty()) return UNI;
        Matcher m = INLINE.matcher(s);
        if (m.matches() && LETTER.matcher(m.group(1)).find() && (splitValue(m.group(2)) != null || isHole(m.group(2)))) { inl[0] = m.group(1).trim(); inl[1] = m.group(2).trim(); return INL; }
        m = INLINE_SP.matcher(s);
        if (m.matches()) {
            String[] sv = splitValue(m.group(2));
            if (sv != null && (!unitClass(sv[1]).isEmpty() || guessKey(m.group(1), "") != null)) { inl[0] = m.group(1).trim(); inl[1] = m.group(2).trim(); return INL; }
        }
        if (s.length() >= 2 && s.length() <= 48 && LETTER.matcher(s).find()) return LAB;
        return 0;
    }

    /** coût d'une paire valeur / libellé (plus petit = plus probable) ; dir[0] : 0 à gauche, 1 à droite, 2 au-dessus, 3 en dessous */
    private static double pairCost(Item v, Item l, int[] dir) {
        double H = Math.max(16, Math.min(v.h(), 90));
        double ov = Math.min(v.b, l.b) - Math.max(v.t, l.t);
        double oh = Math.min(v.r, l.r) - Math.max(v.l, l.l);
        double cost;
        if (ov >= 0.5 * Math.min(v.h(), l.h())) {                         // même ligne
            if (l.r <= v.l + 6) { cost = (v.l - l.r) / H * 0.25; dir[0] = 0; }
            else if (l.l >= v.r - 6) { cost = (l.l - v.r) / H * 0.25 + 1.5; dir[0] = 1; }
            else return Double.MAX_VALUE;
            if (cost > 9) return Double.MAX_VALUE;
        } else if (oh >= 0.3 * Math.min(v.w(), l.w()) || Math.abs(v.cx() - l.cx()) < Math.max(v.w(), l.w()) * 0.5) {   // même colonne
            if (l.b <= v.t + 6) { cost = (v.t - l.b) / H * 1.2 + 0.3; dir[0] = 2; }
            else if (l.t >= v.b - 6) { cost = (l.t - v.b) / H * 1.2 + 0.5; dir[0] = 3; }
            else return Double.MAX_VALUE;
            if (cost > 4.5) return Double.MAX_VALUE;
        } else return Double.MAX_VALUE;
        if (v.p1 >= 0 && v.p1 == l.p1) cost *= 0.4;
        else if (v.p2 >= 0 && (v.p2 == l.p2 || v.p2 == l.p1 || v.p1 == l.p2)) cost *= 0.7;
        if (LABEL_ID.matcher(l.id).find()) cost -= 0.3;
        return cost;
    }

    /** valeurs de la page, avec libellé, unité et donnée proposée (les mieux reconnues d'abord) */
    static List<Cand> extract(List<Item> items) {
        prep(items);
        int n = items.size();
        int[] kind = new int[n];
        String[][] inl = new String[n][];
        List<Cand> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String[] x = new String[2];
            kind[i] = kind(items.get(i), x);
            if (kind[i] == INL) inl[i] = x;
        }
        // 1. valeurs
        for (int i = 0; i < n; i++) {
            Item it = items.get(i);
            if (kind[i] == VAL) { Cand c = new Cand(); c.vi = i; String[] sv = splitValue(it.text); c.raw = sv[0]; c.unit = sv[1]; out.add(c); }
            else if (kind[i] == HOL) { Cand c = new Cand(); c.vi = i; c.raw = it.text.trim(); Matcher m = HOLE.matcher(it.text); if (m.matches() && m.group(2) != null && !unitClass(m.group(2)).isEmpty()) c.unit = m.group(2); out.add(c); }
            else if (kind[i] == INL) {
                Cand c = new Cand(); c.vi = i; c.li = i; c.inline = true; c.label = inl[i][0];
                String[] sv = splitValue(inl[i][1]);
                if (sv != null) { c.raw = sv[0]; c.unit = sv[1]; } else c.raw = inl[i][1];
                out.add(c);
            }
        }
        // 2. unité posée à côté de la valeur (même ligne, juste à droite ; sinon juste en dessous)
        boolean[] usedUnit = new boolean[n];
        for (Cand c : out) {
            if (!c.unit.isEmpty() || c.inline) continue;
            Item v = items.get(c.vi); int best = -1; double bd = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (kind[j] != UNI || usedUnit[j]) continue;
                Item u = items.get(j);
                double ov = Math.min(v.b, u.b) - Math.max(v.t, u.t), d;
                if (ov >= 0.3 * Math.min(v.h(), u.h()) && u.l >= v.r - 8 && u.l - v.r < 2.5 * v.h()) d = u.l - v.r;
                else if (u.t >= v.b - 6 && u.t - v.b < 0.8 * v.h() && Math.abs(u.cx() - v.cx()) < v.w() * 0.6) d = (u.t - v.b) + v.h();
                else continue;
                if (v.p1 >= 0 && v.p1 == u.p1) d *= 0.5;
                if (d < bd) { bd = d; best = j; }
            }
            if (best >= 0) { usedUnit[best] = true; c.unit = items.get(best).text.trim(); }
        }
        // 3. libellé : meilleures paires d'abord
        List<double[]> pairs = new ArrayList<>();
        int[] dir = new int[1]; int onlyAbove = 0, onlyBelow = 0;
        for (int ci = 0; ci < out.size(); ci++) {
            Cand c = out.get(ci); if (c.inline) continue;
            boolean above = false, below = false;
            for (int j = 0; j < n; j++) {
                if (kind[j] != LAB) continue;
                double k = pairCost(items.get(c.vi), items.get(j), dir);
                if (k == Double.MAX_VALUE) continue;
                pairs.add(new double[]{ k, ci, j, dir[0] });
                if (dir[0] == 2) above = true; else if (dir[0] == 3) below = true;
            }
            if (above && !below) onlyAbove++; else if (below && !above) onlyBelow++;
        }
        // grille « valeur puis nom dessous » (ou l'inverse) : la rangée du bord dit dans quel sens lire toute la page,
        // sinon le nom d'une case serait pris pour celui de la case d'en dessous
        if (onlyAbove != onlyBelow) { int bad = onlyBelow > onlyAbove ? 2 : 3; for (double[] p : pairs) if ((int) p[3] == bad) p[0] += 2; }
        Collections.sort(pairs, (a, b) -> Double.compare(a[0], b[0]));
        boolean[] usedLab = new boolean[n];
        for (double[] p : pairs) {
            Cand c = out.get((int) p[1]); int j = (int) p[2];
            if (c.li >= 0 || usedLab[j]) continue;
            c.li = j; c.label = items.get(j).text.trim(); c.cost = p[0]; usedLab[j] = true;
        }
        // 4. donnée proposée, une seule valeur par donnée
        Set<String> taken = new HashSet<>();
        List<Cand> order = new ArrayList<>(out);
        Collections.sort(order, (a, b) -> Double.compare(a.cost, b.cost));
        for (Cand c : order) {
            String uc = unitClass(c.unit);
            String k = guessKey(c.label, uc);
            boolean hole = isHole(c.raw);
            if (!hole) c.num = parseNum(c.raw, k != null && KM_KEYS.contains(k));
            if (k != null && !TEXT_KEYS.contains(k) && c.num != null && !plausible(k, convert(k, c.num, uc))) k = null;
            if (k != null && taken.add(k)) c.key = k;
        }
        // 5. textes d'état (ceinture, frein à main, lave-glace) : libellé + texte voisin sans chiffre
        for (int j = 0; j < n; j++) {
            if (kind[j] != LAB || usedLab[j]) continue;
            String k = guessKey(items.get(j).text, "");
            if (k == null || !TEXT_KEYS.contains(k) || taken.contains(k)) continue;
            int best = -1; double bc = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (i == j || kind[i] != LAB || usedLab[i]) continue;
                if (guessKey(items.get(i).text, "") != null) continue;
                double k2 = pairCost(items.get(i), items.get(j), dir);
                if (k2 < bc) { bc = k2; best = i; }
            }
            if (best < 0) continue;
            Cand c = new Cand(); c.vi = best; c.li = j; c.label = items.get(j).text.trim(); c.raw = items.get(best).text.trim(); c.key = k; c.cost = bc;
            usedLab[j] = true; usedLab[best] = true; taken.add(k); out.add(c);
        }
        Collections.sort(out, (a, b) -> {
            int ka = a.key != null ? 0 : a.label.isEmpty() ? 2 : 1, kb = b.key != null ? 0 : b.label.isEmpty() ? 2 : 1;
            return ka != kb ? ka - kb : Double.compare(items.get(a.vi).cy() * 10000 + items.get(a.vi).cx(), items.get(b.vi).cy() * 10000 + items.get(b.vi).cx());
        });
        return out;
    }

    /* =============================== repère durable =============================== */
    static Field fieldOf(List<Item> items, Cand c, String key, int W, int H) {
        Field f = new Field();
        Item v = items.get(c.vi);
        f.key = key; f.id = v.id; f.ord = v.ord; f.same = v.same;
        f.px = v.cx() / W; f.py = v.cy() / H;
        f.inline = c.inline; f.txt = TEXT_KEYS.contains(key);
        f.u = unitClass(c.unit);
        if (c.inline) f.lab = norm(c.label);
        else if (c.li >= 0) { Item l = items.get(c.li); f.lab = norm(l.text); f.rx = (v.cx() - l.cx()) / W; f.ry = (v.cy() - l.cy()) / H; }
        return f;
    }

    private static double dist(double ax, double ay, double bx, double by) { return Math.sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by)); }

    /** texte qui porte la valeur de ce repère dans l'écran actuel, ou null */
    static Item locate(List<Item> items, Field f, int W, int H) {
        // 1. par le libellé (résiste aux changements d'identifiants et aux petits déplacements)
        if (!f.lab.isEmpty()) {
            Item lab = null; double bd = Double.MAX_VALUE;
            for (Item it : items) {
                String nt = norm(it.text);
                boolean hit = f.inline ? nt.startsWith(f.lab) : nt.equals(f.lab);
                if (!hit) continue;
                double d = dist(it.cx() / W, it.cy() / H, f.px - f.rx, f.py - f.ry);
                if (d < bd) { bd = d; lab = it; }
            }
            if (lab != null) {
                if (f.inline) return lab;
                double tx = lab.cx() / W + f.rx, ty = lab.cy() / H + f.ry;
                Item best = null; bd = 0.09;
                for (Item it : items) {
                    if (it == lab) continue;
                    boolean ok = f.txt ? (it.text != null && it.text.trim().length() > 0 && it.text.length() <= 40) : (splitValue(it.text) != null || isHole(it.text));
                    if (!ok) continue;
                    double d = dist(it.cx() / W, it.cy() / H, tx, ty);
                    if (!f.id.isEmpty() && f.id.equals(it.id)) d *= 0.5;
                    if (d < bd) { bd = d; best = it; }
                }
                if (best != null) return best;
            }
        }
        // 2. par l'identifiant (propre à cette donnée : marche aussi si l'appli change de langue) ou identifiant + rang (sans libellé)
        if (!f.id.isEmpty()) {
            List<Item> sameId = new ArrayList<>();
            for (Item it : items) if (f.id.equals(it.id)) sameId.add(it);
            // identifiant répété (grille) et libellé connu mais absent : ce n'est pas la bonne page, on ne devine pas
            if (!f.lab.isEmpty() && f.same > 1) return null;
            if (sameId.size() == f.same && f.ord < sameId.size()) return sameId.get(f.ord);
            Item best = null; double bd = 0.06;
            for (Item it : sameId) { double d = dist(it.cx() / W, it.cy() / H, f.px, f.py); if (d < bd) { bd = d; best = it; } }
            return best;
        }
        // 3. par la position seule (ni libellé ni identifiant : pictogramme + nombre)
        if (f.lab.isEmpty() && f.id.isEmpty()) {
            Item best = null; double bd = 0.04;
            for (Item it : items) {
                if (splitValue(it.text) == null && !isHole(it.text)) continue;
                double d = dist(it.cx() / W, it.cy() / H, f.px, f.py);
                if (d < bd) { bd = d; best = it; }
            }
            return best;
        }
        return null;
    }

    /* =============================== pages et chemin (1.27.1) =============================== */
    /** empreinte d'une page : identifiants et textes, chiffres ignorés (une valeur qui bouge ne change pas de page) */
    static String sig(List<Item> items) {
        StringBuilder b = new StringBuilder();
        for (Item it : items) b.append(it.id).append('=').append(it.text == null ? "" : it.text.replaceAll("\\d+", "#")).append('|');
        return b.toString();
    }
    /** textes stables d'une page (sans chiffre, 2 à 40 caractères), normalisés, dans l'ordre de lecture */
    static List<String> anchors(List<Item> items, int max) {
        List<Item> l = new ArrayList<>(items);
        Collections.sort(l, (a, b) -> Double.compare(a.cy() * 10000 + a.cx(), b.cy() * 10000 + b.cx()));
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Item it : l) {
            if (out.size() >= max) break;
            String t = norm(it.text);
            if (t.length() < 2 || t.length() > 40 || t.matches(".*\\d.*") || isHole(it.text)) continue;
            out.add(t);
        }
        return new ArrayList<>(out);
    }
    /** part des textes attendus présents à l'écran (1 si on n'attend rien) */
    static double overlap(List<Item> items, List<String> want) {
        if (want == null || want.isEmpty()) return 1;
        Set<String> on = new HashSet<>();
        for (Item it : items) on.add(norm(it.text));
        int n = 0; for (String w : want) if (on.contains(w)) n++;
        return n / (double) want.size();
    }
    /** vrai si on est passé à une autre page (et pas seulement un texte d'état qui change) */
    static boolean pageChanged(List<String> before, List<String> after) {
        Set<String> a = new HashSet<>(before), b = new HashSet<>(after);
        int gone = 0, added = 0;
        for (String x : a) if (!b.contains(x)) gone++;
        for (String x : b) if (!a.contains(x)) added++;
        int big = Math.max(1, Math.max(a.size(), b.size()));
        return added >= 1 && (gone + added >= 3 || (gone + added) >= 0.4 * big);
    }
    private static final Pattern NAV_GOOD = Pattern.compile("info|\\bcar\\b|car ?info|vehic|voiture|\\bobd\\b|\\bodb\\b|trip|ordinateur|\\bbord\\b|donnee|\\bdata\\b|\\betat\\b|status|computer|drive|meter|dashboard|tableau|fahrzeug|vehiculo|veicolo|автомоб|машин|信息|车|行车|原车");
    private static final Pattern NAV_BAD = Pattern.compile("setting|reglage|parametre|config|version|radar|clim|\\bair\\b|\\bac\\b|volume|\\bson\\b|sound|\\beq\\b|egaliseur|equali|langue|language|update|mise a jour|upgrade|reset|factory|usine|about|a propos|camera|bluetooth|wifi|retour|\\bback\\b|exit|quitter|\\bhome\\b|accueil|\\bok\\b|annuler|cancel|设置|版本|空调|返回");
    /** entrées à essayer pour passer de la page « from » à la page « to » (menu), les plus probables d'abord, au plus 4 */
    static List<Item> navCandidates(List<Item> items, List<String> from, List<String> to) {
        Set<String> fromSet = new HashSet<>(from == null ? Collections.<String>emptyList() : from);
        List<Object[]> sc = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item it : items) {
            String t = norm(it.text);
            if (t.length() < 2 || t.length() > 40 || t.matches(".*\\d.*") || isHole(it.text) || !seen.add(t)) continue;
            if (!fromSet.isEmpty() && !fromSet.contains(t)) continue;
            if (NAV_BAD.matcher(t).find()) continue;
            double s = 0;
            if (NAV_GOOD.matcher(t).find()) s += 3;
            if (to != null) for (String x : to) if (x.equals(t) || x.contains(t) || t.contains(x)) { s += 4; break; }   // titre de la page d'arrivée
            sc.add(new Object[]{ s, it });
        }
        Collections.sort(sc, (a, b) -> {
            int c = Double.compare((Double) b[0], (Double) a[0]); if (c != 0) return c;
            Item x = (Item) a[1], y = (Item) b[1]; return Double.compare(x.cy() * 10000 + x.cx(), y.cy() * 10000 + y.cx());
        });
        List<Item> out = new ArrayList<>();
        for (Object[] o : sc) { if (out.size() >= 4) break; out.add((Item) o[1]); }
        return out;
    }

    /** résultat de lecture : FOUND_EMPTY = la case est là mais sans valeur (« -- », moteur coupé) */
    static final Object FOUND_EMPTY = new Object();

    /** valeur du repère : Double (unités du lanceur), String (texte d'état), FOUND_EMPTY, ou null (repère introuvable) */
    static Object read(List<Item> items, Field f, int W, int H) {
        Item it = locate(items, f, W, H);
        if (it == null) return null;
        String s = it.text == null ? "" : it.text.trim();
        if (f.inline) { Matcher m = INLINE.matcher(s); if (m.matches()) s = m.group(2).trim(); else { m = INLINE_SP.matcher(s); if (m.matches()) s = m.group(2).trim(); } }
        if (f.txt) return s.isEmpty() ? FOUND_EMPTY : s;
        if (isHole(s)) return FOUND_EMPTY;
        String[] sv = splitValue(s);
        if (sv == null) return null;
        String uc = unitClass(sv[1]);
        if (uc.isEmpty()) {      // unité dans un texte voisin ? (l'utilisateur a pu passer l'appli en miles)
            for (Item u : items) {
                if (u == it || unitClass(u.text).isEmpty()) continue;
                double ov = Math.min(it.b, u.b) - Math.max(it.t, u.t);
                if (ov >= 0.3 * Math.min(it.h(), u.h()) && u.l >= it.r - 8 && u.l - it.r < 2.5 * it.h()) { uc = unitClass(u.text); break; }
            }
        }
        if (uc.isEmpty() || !unitOk(f.key, uc)) uc = f.u;
        Double d = parseNum(sv[0], KM_KEYS.contains(f.key));
        if (d == null) return null;
        double v = convert(f.key, d, uc);
        return plausible(f.key, v) ? (Object) v : FOUND_EMPTY;
    }
}
