package fr.ibiza.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests du lecteur d'écran générique (ScreenRead), sans Android :
 *   javac -d /tmp/t app/src/main/java/fr/ibiza/launcher/ScreenRead.java tools/test/ScreenReadTest.java
 *   java -cp /tmp/t fr.ibiza.launcher.ScreenReadTest
 * Chaque page imite la mise en page d'une appli CAN réelle (textes + cadres, comme les donne l'accessibilité).
 */
public class ScreenReadTest {
    static int fails = 0, checks = 0;
    static final int W = 1024, H = 600;
    static List<ScreenRead.Item> page;
    static int parent;

    static void add(String id, String text, int x, int y, int w, int h) { page.add(new ScreenRead.Item(id, text, x, y, x + w, y + h, parent, parent / 100)); }
    static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ÉCHEC : " + what); } }
    static Map<String, ScreenRead.Cand> byKey(List<ScreenRead.Cand> cs) { Map<String, ScreenRead.Cand> m = new HashMap<>(); for (ScreenRead.Cand c : cs) if (c.key != null) m.put(c.key, c); return m; }
    static void expect(Map<String, ScreenRead.Cand> m, String key, String raw) { ScreenRead.Cand c = m.get(key); ok(c != null && c.raw.equals(raw), key + " attendu « " + raw + " », trouvé " + (c == null ? "rien" : "« " + c.raw + " » (libellé « " + c.label + " »)")); }
    static List<ScreenRead.Field> fields(List<ScreenRead.Item> p, List<ScreenRead.Cand> cs) { List<ScreenRead.Field> f = new ArrayList<>(); for (ScreenRead.Cand c : cs) if (c.key != null) f.add(ScreenRead.fieldOf(p, c, c.key, W, H)); return f; }
    static Object val(List<ScreenRead.Item> p, List<ScreenRead.Field> fs, String key) { for (ScreenRead.Field f : fs) if (f.key.equals(key)) return ScreenRead.read(p, f, W, H); return "champ absent"; }
    static void near(Object o, double want, String what) { ok(o instanceof Double && Math.abs((Double) o - want) < 0.06, what + " attendu " + want + ", lu " + o); }

    /** CarCan (hcn), grille 1 : nom à gauche, valeur à droite, une ligne par donnée */
    static List<ScreenRead.Item> carcanList(int dy, String volt, String fuel) {
        page = new ArrayList<>();
        String[][] rows = { {"Voltage de batterie", volt}, {"Huile résiduelle", fuel}, {"Kilométrage", "84213KM"}, {"Temp. extérieure", "11.5℃"}, {"Ceinture de sécurité", "Ne pas attacher"}, {"Nettoyant", "Normal"} };
        for (int i = 0; i < rows.length; i++) { parent = 100 + i; add("odbtextName", rows[i][0], 60, 90 + dy + i * 70, 360, 40); add("odbtextValue", rows[i][1], 640, 90 + dy + i * 70, 240, 40); }
        parent = 1; add("title", "Infos car", 40, 20, 200, 44);
        return page;
    }
    /** CarCan, grille 2 : valeur, unité à droite, nom en dessous ; cases dans leur conteneur ou à plat */
    static List<ScreenRead.Item> carcanGrid(boolean containers) {
        page = new ArrayList<>();
        String[][] cells = { {"831", "RPM", "La vitesse du moteur"}, {"0", "KM/H", "Rapidité"}, {"92", "℃", "Température d'eau"}, {"5.8", "L/100KM", "Consommation moyenne"}, {"520", "KM", "Autonomie"}, {"47", "KM/H", "Vitesse moyenne"} };
        for (int i = 0; i < cells.length; i++) {
            int x = 60 + (i % 3) * 320, y = 100 + (i / 3) * 160;
            parent = containers ? 200 + i : 7;
            add("textValue", cells[i][0], x, y, 120, 56); add("textUnit", cells[i][1], x + 126, y + 20, 90, 30); add("textName", cells[i][2], x - 20, y + 66, 280, 34);
        }
        return page;
    }
    /** liste « Libellé : valeur » en anglais, unités impériales (poste réglé en miles) */
    static List<ScreenRead.Item> englishInline() {
        page = new ArrayList<>(); parent = 3;
        String[] rows = { "Battery voltage: 12.6 V", "Outside temp: 68 °F", "Range: 250 mi", "Fuel level: 45 %", "Odometer: 52,340 mi", "Engine speed: 1,850 rpm", "Trip A: 12.4 mi" };
        for (int i = 0; i < rows.length; i++) add("tv_item", rows[i], 80, 80 + i * 60, 500, 44);
        return page;
    }
    /** tuiles à plat, empilées : nom AU-DESSUS de la valeur, mêmes écarts partout (le cas ambigu) */
    static List<ScreenRead.Item> stackedTiles() {
        page = new ArrayList<>(); parent = 4;
        String[][] t = { {"Tension", "12.4 V"}, {"Carburant", "38 L"}, {"Extérieur", "-3 °C"} };
        for (int i = 0; i < t.length; i++) { add("", t[i][0], 100, 60 + i * 120, 240, 36); add("", t[i][1], 100, 106 + i * 120, 240, 50); }
        return page;
    }
    /** menu de l'appli : mêmes identifiants génériques, aucune donnée */
    static List<ScreenRead.Item> menu() {
        page = new ArrayList<>(); parent = 5;
        String[] m = { "Infos car", "Climatisation", "Réglages", "Radar", "Version" };
        for (int i = 0; i < m.length; i++) add("textName", m[i], 60 + i * 190, 400, 170, 40);
        add("textValue", "3", 60, 300, 40, 40);
        return page;
    }

    public static void main(String[] a) {
        System.out.println("1. CarCan, liste nom / valeur");
        List<ScreenRead.Item> p = carcanList(0, "14.3V", "38L");
        List<ScreenRead.Cand> cs = ScreenRead.extract(p);
        Map<String, ScreenRead.Cand> m = byKey(cs);
        expect(m, "battery", "14.3"); expect(m, "fuel", "38"); expect(m, "odo", "84213"); expect(m, "outside", "11.5");
        expect(m, "belt", "Ne pas attacher"); expect(m, "washer", "Normal");
        List<ScreenRead.Field> fs = fields(p, cs);
        List<ScreenRead.Item> later = carcanList(24, "12.1V", "31L");                // page décalée de 24 px, valeurs changées
        near(val(later, fs, "battery"), 12.1, "batterie relue"); near(val(later, fs, "fuel"), 31, "carburant relu"); near(val(later, fs, "odo"), 84213, "kilométrage relu");
        ok("Ne pas attacher".equals(val(later, fs, "belt")), "ceinture relue : " + val(later, fs, "belt"));
        List<ScreenRead.Item> off = carcanList(0, "--", "0L");                        // moteur coupé
        ok(val(off, fs, "battery") == ScreenRead.FOUND_EMPTY, "batterie « -- » = case vide, pas une valeur");
        ok(val(off, fs, "fuel") == ScreenRead.FOUND_EMPTY, "carburant 0 L ignoré");

        System.out.println("2. CarCan, grille valeur / unité / nom dessous (cases séparées)");
        p = carcanGrid(true); cs = ScreenRead.extract(p); m = byKey(cs);
        expect(m, "rpm", "831"); expect(m, "speed", "0"); expect(m, "coolant", "92"); expect(m, "cons", "5.8"); expect(m, "range", "520"); expect(m, "avg", "47");
        fs = fields(p, cs);
        near(val(p, fs, "rpm"), 831, "régime relu"); near(val(p, fs, "cons"), 5.8, "conso relue");
        ok(val(menu(), fs, "rpm") == null, "sur le menu de l'appli, rien n'est lu (régime) : " + val(menu(), fs, "rpm"));

        System.out.println("3. même grille, à plat (sans conteneur par case)");
        p = carcanGrid(false); m = byKey(ScreenRead.extract(p));
        expect(m, "rpm", "831"); expect(m, "speed", "0"); expect(m, "coolant", "92"); expect(m, "cons", "5.8"); expect(m, "range", "520"); expect(m, "avg", "47");

        System.out.println("4. liste anglaise « Libellé : valeur », unités impériales");
        p = englishInline(); cs = ScreenRead.extract(p); m = byKey(cs);
        expect(m, "battery", "12.6"); expect(m, "outside", "68"); expect(m, "range", "250"); expect(m, "fuel_pct", "45"); expect(m, "odo", "52,340"); expect(m, "rpm", "1,850");
        fs = fields(p, cs);
        near(val(p, fs, "outside"), 20.0, "68 °F -> °C"); near(val(p, fs, "range"), 402.3, "250 mi -> km"); near(val(p, fs, "odo"), 84233.1, "52 340 mi -> km");
        near(val(p, fs, "rpm"), 1850, "1,850 rpm = 1850"); near(val(p, fs, "fuel_pct"), 45, "carburant en %");
        int trip = 0; for (ScreenRead.Cand c : cs) if (c.label.startsWith("Trip") && c.key != null) trip++;
        ok(trip == 0, "le compteur journalier n'est pas pris pour le kilométrage");

        System.out.println("5. tuiles empilées à plat, nom au-dessus");
        p = stackedTiles(); cs = ScreenRead.extract(p); m = byKey(cs);
        expect(m, "battery", "12.4"); expect(m, "fuel", "38"); expect(m, "outside", "-3");
        near(val(p, fields(p, cs), "outside"), -3, "température négative");

        System.out.println("6. textes isolés");
        ok(ScreenRead.splitValue("12:30") == null, "12:30 n'est pas une valeur");
        ok(ScreenRead.splitValue("2024-05-01") == null, "une date n'est pas une valeur");
        ok(ScreenRead.splitValue("84 213 km") != null && ScreenRead.parseNum("84 213", true) == 84213, "84 213 km");
        ok(ScreenRead.parseNum("1.234", true) == 1234 && ScreenRead.parseNum("1.234", false) == 1.234, "1.234 : milliers pour les km, décimal sinon");
        ok("speed".equals(ScreenRead.guessKey("车速", "kmh")) && "fuel".equals(ScreenRead.guessKey("剩余油量", "litre")) && "battery".equals(ScreenRead.guessKey("电瓶电压", "volt")), "libellés chinois");
        ok("rpm".equals(ScreenRead.guessKey("Обороты двигателя", "")) && "range".equals(ScreenRead.guessKey("Запас хода", "km")) && "avg".equals(ScreenRead.guessKey("Средняя скорость", "kmh")), "libellés russes");
        ok("coolant".equals(ScreenRead.guessKey("Kühlmitteltemperatur", "degC")) && "outside".equals(ScreenRead.guessKey("Außentemperatur", "degC")) && "odo".equals(ScreenRead.guessKey("Kilometerstand", "km")), "libellés allemands");
        ok("fuel".equals(ScreenRead.guessKey("Remaining oil", "litre")) && "oil".equals(ScreenRead.guessKey("Oil temperature", "degC")), "« oil » : carburant en litres, huile en degrés");
        ok(ScreenRead.guessKey("Radar 1", "") == null && ScreenRead.guessKey("Volume", "") == null, "radar / volume ignorés");
        ok("insp_days".equals(ScreenRead.guessKey("Inspection", "days")) && "oil_km".equals(ScreenRead.guessKey("Vidange", "km")), "révision en jours / vidange en km");

        System.out.println("7. chemin : menu réel de CarCan v3.88 (journal du Junsun, 1.27.1)");
        page = new ArrayList<>(); parent = 7;
        add("tv_can_menu", "CarInfo", 66, 230, 72, 30); parent = 10; add("carversion", "V3.88.140BYPS", 20, 543, 245, 47);
        List<ScreenRead.Item> menu = page;
        List<String> fromA = ScreenRead.anchors(menu, 16);
        ok(fromA.size() == 1 && fromA.get(0).equals("carinfo"), "repères du menu : seulement « carinfo » (la version change) : " + fromA);
        List<String> infoA = ScreenRead.anchors(carcanList(0, "14.3V", "38L"), 16);
        ok(ScreenRead.pageChanged(fromA, infoA), "menu -> page des infos = changement de page");
        List<String> to = new ArrayList<>(); for (String t : infoA) if (!fromA.contains(t)) to.add(t);
        List<ScreenRead.Item> nc = ScreenRead.navCandidates(menu, fromA, to);
        ok(nc.size() == 1 && nc.get(0).text.equals("CarInfo"), "entrée à essayer : CarInfo, trouvé " + nc.size());
        ok(ScreenRead.overlap(carcanList(30, "12.0V", "20L"), to) >= 0.5 && ScreenRead.overlap(menu, to) == 0, "page d'arrivée reconnue, menu non");
        ok(ScreenRead.sig(carcanList(0, "14.3V", "38L")).equals(ScreenRead.sig(carcanList(0, "12.1V", "31L"))), "valeurs qui bougent : même page");
        ok(!ScreenRead.sig(menu).equals(ScreenRead.sig(carcanList(0, "14.3V", "38L"))), "menu et page des infos : empreintes différentes");
        List<String> st1 = new ArrayList<>(infoA), st2 = new ArrayList<>(infoA); st2.remove("ne pas attacher"); st2.add("attachee");
        ok(!ScreenRead.pageChanged(st1, st2), "un texte d'état qui change (ceinture) n'est pas un changement de page");
        page = new ArrayList<>(); parent = 8;
        String[] m8 = { "Climatisation", "Réglages", "Infos car", "Radar", "Version", "Volant" };
        for (int i = 0; i < m8.length; i++) add("tv", m8[i], 60 + i * 160, 400, 140, 40);
        nc = ScreenRead.navCandidates(page, ScreenRead.anchors(page, 16), Collections.singletonList("voltage de batterie"));
        ok(nc.size() == 2 && nc.get(0).text.equals("Infos car") && nc.get(1).text.equals("Volant"), "menu à 6 entrées : Infos car d'abord, réglages / clim / radar / version écartés : " + nc.size());

        System.out.println(fails == 0 ? "OK : " + checks + " vérifications" : fails + " échec(s) sur " + checks);
        System.exit(fails == 0 ? 0 : 1);
    }
}
