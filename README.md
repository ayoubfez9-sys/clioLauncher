# Car Launcher — lanceur Android pour autoradio (style CarPlay)

Lanceur Android (Android Studio, interface HTML/CSS/JS dans une WebView + Java) pensé pour les autoradios Android 2 DIN
chinois. Développé et testé sur un **Junsun V1 (MediaTek MT8163, firmware « hcn », écran 1024×600)** avec un boîtier CAN
**SimpleSoft profil VW**, dans une Seat Ibiza 6J 5 portes.

> 🎥 Démo : _ajoute ici le lien de ta vidéo TikTok_

## Fonctions

- **Accueil façon CarPlay** : barre latérale, horloge, musique, apps, thèmes clair/sombre/auto, fond d'écran, effet verre.
- **Voiture 3D** sur plateau tournant (48 vues pré-rendues, **rotation continue** en WebGL grâce au mouvement précalculé
  entre vues) : chaque ouvrant s'anime à l'ouverture (portes, hayon, capot), combinaisons animées, vue de face quand des
  portes sont ouvertes des deux côtés. *(nécessite un « pack voiture », voir plus bas)*
- **Trois dispositions de l'accueil** au choix (Réglages) : Voiture, Voiture XL, Conduite (vitesse et ordinateur de bord en
  grand). Bibliothèque d'apps sur 2 lignes (grandes icônes) ou 3 lignes. **Icônes perso** en pleine tuile, avec la liste des
  noms de fichiers à utiliser.
- **Ouvrants, clignotants, vitesse, radars en direct** (lus sur le CAN via les propriétés système du firmware hcn ; sur les
  postes où elles arrivent en retard, la fenêtre portes de CarCan est lue en avance).
- **Ordinateur de bord** : carburant, batterie, température extérieure, kilométrage, régime, lave-glace.
- **Entretien** : suivi des opérations (km et/ou mois), entretiens libres, diesel/essence, **export PDF** du carnet,
  vue « poste de conduite » animée.
- **Écran « Prêt à partir »** au démarrage : autonomie, température, batterie, alertes (verglas, batterie faible,
  réserve, lave-glace, entretien dû, ouvrant ouvert).
- **Alerte plein écran** si un ouvrant s'ouvre en roulant (> 5 km/h), même par-dessus CarPlay.
- Fenêtre « activité en direct » des ouvrants par-dessus les autres apps.
- **Pensé pour les autoradios à 2 Go** : vues décodées à la taille affichée, textes ajustés sans recalculs inutiles, journal
  écrit en arrière-plan. **Chien de garde de l'écran** : si l'interface ne répond plus (écran noir), elle est rechargée toute
  seule ; le journal de diagnostic est gardé dans un fichier et survit aux redémarrages.

## Compatibilité

| Élément | État |
|---|---|
| Interface, apps, musique, entretien, PDF | tout autoradio Android 6+ |
| Portes / clignotants / vitesse CAN | firmware Junsun « hcn » (propriétés `persist.vendor.cem.*`), vu sur MT8163 et RK3326 |
| Portes sur TopWay TS10 / TS18 | lues dans la fenêtre portes du poste (4 portes ; coffre et capot : en cours, voir `docs/CAN.md`) |
| Ordinateur de bord | **toute appli CAN qui affiche ses valeurs en texte** : « Apprendre l'ordinateur de bord » (2 min, sans adb, voir `docs/CAN.md` § 6). CarCan (`com.hcn.autocan`) + SimpleSoft **profil VW** : lu sans apprentissage |
| Voiture 3D | pack d'images propre à chaque voiture (non fourni) |

Autre autoradio, autre boîtier CAN ou autre voiture : **suis `ADAPTER.md`** (prompt prêt à donner à une IA avec ce dossier).

## Installer

1. Ouvre le dossier dans **Android Studio** (SDK 34), laisse Gradle synchroniser, puis *Build > Build APK(s)*.
2. Copie l'APK sur une clé USB et installe-le sur l'autoradio (autoriser les sources inconnues).
3. Touche Accueil > choisis le lanceur > *Toujours*.
4. Dans le lanceur : appui long sur l'horloge > Réglages > Installation : autorise les notifications (musique),
   l'**accessibilité** (lecture CAN/CarCan) et l'affichage par-dessus les apps.

Optionnel — ordinateur de bord **en direct**, sans ouvrir CarCan (nécessite ADB ou root, une seule fois) :
```
adb shell pm grant fr.ibiza.launcher android.permission.READ_LOGS
```

## Sécurité : ce que le lanceur touche (et ne touche pas)

- **Rien n'est modifié dans le système** : pas de root, pas de mise à jour de firmware, aucune appli d'origine modifiée.
  CarPlay / Android Auto, la radio, la caméra de recul et l'appli CAN continuent de fonctionner comme avant.
- Le lanceur **lit** seulement : propriétés système, notifications média, écran de l'appli CAN (accessibilité).
- Par-dessus les autres applis, il n'affiche que la petite fenêtre des ouvrants (désactivable) et l'alerte
  « ouvrant ouvert en roulant » (désactivable). La lecture de l'appli CAN ne se lance **jamais par-dessus CarPlay**.

## Revenir en arrière / désinstaller

1. Réglages du lanceur > Installation > **Revenir au lanceur d'origine / désinstaller** > « 1. Écran d'accueil » :
   choisis l'écran d'accueil d'origine (souvent « Launcher3 »). Ou : Paramètres Android > Applications >
   Applications par défaut > Application d'accueil.
2. Puis « 2. Désinstaller » (le bouton Désinstaller d'Android reste grisé tant que le lanceur est l'écran d'accueil par défaut).

Si le lanceur ne s'affiche plus : ouvre les Paramètres Android depuis le volet des notifications (glisser du haut de l'écran,
icône engrenage) > Applications > Applications par défaut > Application d'accueil, et choisis celui d'origine.

## Pack voiture (images 3D)

Les images de la voiture **ne sont pas incluses** (licence du modèle 3D d'origine). Sans pack, l'appli fonctionne et affiche
un message à la place de la voiture.

**Depuis la 1.23, un pack s'installe sans recompiler** : Réglages du lanceur > Voiture 3D > Pack voiture > Clé USB, puis
choisir le .zip (ou le copier dans `Android/data/fr.ibiza.launcher/files/packs` et toucher « Dossier du lanceur »).

Pour créer le tien : `ADAPTER.md` + `tools/render/` pour les images (`img/car`, `img/top`, `img/toph`, `img/cockpit`,
réglages dans `app/src/main/assets/www/js/config.js`), puis `tools/pack/make_pack.py` pour en faire un pack :
```
python3 tools/pack/make_pack.py --www app/src/main/assets/www --id ma-voiture --name "Ma voiture"
```

## Documentation

- `ADAPTER.md` — adapter à ton véhicule / autoradio (avec le prompt à copier-coller)
- `docs/CAN.md` — ce qu'on sait du CAN sur Junsun « hcn » + CarCan (propriétés, trames, astuces)
- `tools/render/` — pipeline Blender des rendus 3D
- `tools/test/` — tests du lecteur d'écran générique, sans Android (`ScreenReadTest.java`)
- `CHANGELOG.txt` — historique des versions

## Avertissement

Projet personnel, fourni **sans aucune garantie**. Ne manipule pas l'écran en roulant. L'appli utilise le service
d'accessibilité uniquement pour lire l'écran de l'appli CAN de l'autoradio ; aucune donnée ne quitte l'appareil.
Marques et logos cités appartiennent à leurs propriétaires ; projet non affilié.
