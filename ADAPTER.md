# Adapter le lanceur à ta voiture et à ton autoradio

Le plus simple : donne **ce dossier complet** à une IA capable de lire et modifier des fichiers et de lancer du code
(par exemple Claude), avec le prompt ci-dessous rempli. Elle s'occupe des réglages, du code et des rendus 3D, et te demande
les captures ou fichiers qui lui manquent.

> **Avant tout** (1.27) : si seul l'ordinateur de bord manque (carburant, batterie, kilométrage…), essaie d'abord
> Réglages > Données CAN du poste > *Apprendre l'ordinateur de bord* : le lanceur apprend l'appli CAN de ton autoradio en
> 2 minutes, sans ordinateur ni adb. Idem pour les portes : *Apprendre ma voiture*. Ce guide sert quand ça ne suffit pas.

## 1. Ce qu'il faut préparer

- **Infos sur ton autoradio** : marque/modèle, puce (Réglages > À propos), version d'Android, captures de l'écran
  « À propos ».
- **Infos sur ta voiture** : marque, modèle, année, nombre de portes, couleur (nom commercial + code peinture si possible),
  diesel/essence.
- **Ton boîtier CAN et son appli** : nom de l'appli CAN de l'autoradio (ex. CarCan), profil choisi (ex. « VW SimpleSoft »).
- **Diagnostic du lanceur** : installe le lanceur tel quel, puis Réglages > Diagnostic CAN > *Exporter le journal*
  (portes ouvertes puis fermées, moteur allumé) et *Copier les APK CAN*.
- **Captures** de l'appli CAN (page avec les infos du véhicule) et de la fenêtre portes/radars d'origine.
- **(Pour la 3D)** un modèle 3D de ta voiture (.fbx, .obj, .3ds, .blend) **dont la licence autorise la diffusion des rendus**,
  avec ses textures. Sans modèle : le lanceur marche sans la voiture 3D.

## 2. Le prompt à copier-coller

```
Tu vas adapter le projet Android « Car Launcher » (dossier joint) à ma voiture et à mon autoradio.
Lis d'abord README.md, ADAPTER.md, docs/CAN.md, CHANGELOG.txt et tools/render/README.md.

Mon autoradio : [marque, modèle, puce, Android, captures « À propos »]
Ma voiture : [marque, modèle, année, portes, couleur + code peinture, diesel/essence]
Boîtier / appli CAN : [nom de l'appli, profil choisi]
Fichiers joints : [journal exporté, APK CAN copiés, captures, modèle 3D]

Ce que je veux :
1. Réglages du véhicule dans app/src/main/assets/www/js/config.js (car.name, car.model, car.fuel) et nom de l'appli
   dans app/src/main/res/values/strings.xml.
2. Lecture CAN : analyse mon journal et les APK CAN (méthode décrite dans docs/CAN.md) pour lire portes, clignotants,
   vitesse et ordinateur de bord sur MON autoradio ; adapte CanBridge.java, CarText.java, LogCan.java et
   CanWatchService.java si le format diffère. Explique-moi ce qui est lu en direct et ce qui ne l'est pas.
3. Pack 3D (si modèle fourni) : adapte tools/render/build2.py à mon modèle (pièces, découpage des portes, charnières,
   couleur de peinture), envoie-moi des images test et attends ma validation avant le rendu complet ; puis convertis
   en webp dans img/car ; génère les flux de rotation continue avec tools/render/flows.py (img/car/flow_XX.png, valeur
   flowMax) ; génère la vue du dessus avec tools/render/render_top.py (img/top, img/toph, valeur topBox) ;
   img/cockpit si je veux la vue poste de conduite ; puis règle config.js : carPack, topView, topBox, cockpit,
   hiView, comboAnim, doorViews, homeView, flowMax ; enfin fabrique le pack avec tools/pack/make_pack.py (zip à installer
   depuis les réglages du lanceur, sans recompiler).
4. Vérifie que le JavaScript et le Java n'ont pas d'erreur, teste l'interface dans un navigateur, puis donne-moi
   le projet complet en zip et la liste de ce qui a changé.
Pose-moi une question à la fois si une info manque.
```

## 3. Réglages (`app/src/main/assets/www/js/config.js`)

| Clé | Rôle |
|---|---|
| `car.name` / `car.model` / `car.fuel` | nom affiché, modèle (PDF, entretien), `diesel` ou `essence` |
| `carPack` | `true` quand les images 3D sont dans `img/car` |
| `frames` | nombre de vues tournantes (`hero_00.webp`…) |
| `doorViews` | numéro de vue utilisé pour chaque ouvrant (`fl`, `fr`, `rl`, `rr`, `trunk`, `hood`) |
| `doorSteps` | étapes d'animation par ouvrant (`door_fl_1..6.webp`…) |
| `homeView` | vue par défaut (ex. 8 = 3/4 avant droit) |
| `hiView` | vue de face quand des portes sont ouvertes des deux côtés (`door_hi_*.webp`) |
| `comboAnim` | animations combinées (`door_m<qui bouge>_s<ouverts>_<étape>.webp`) |
| `topView` | vue du dessus (`img/top/*.webp`, `img/toph/*.webp`, générées par `tools/render/render_top.py`) |
| `topBox` | cadre de la carrosserie dans la vue du dessus (repère 680×750), donné par `render_top.py`, sert à placer les radars |
| `cockpit` | page Entretien façon poste de conduite (`img/cockpit/intro.mp4`, `dash.webp`…) |
| `flowMax` | rotation continue (WebGL) : valeur donnée par `tools/render/flows.py` ; absent = fondu enchaîné entre deux vues |

## 4. Noms des images du pack (`img/car`)

- `hero_00.webp` … `hero_47.webp` : tour complet (vue n → angle 30° − 7,5° × n), 720×402, fond transparent
- `floor.webp`, `floor_hi.webp` : plateau (vue normale / vue de face)
- `door_<ouvrant>_<1..6>.webp` : ouverture étape par étape, à l'angle `doorViews[ouvrant]`
- `door_flrl_6.webp`, `door_frrr_6.webp` : les deux portes d'un côté ouvertes
- `door_<porte(s)>_trunk.webp`, `door_trunkL.webp`, `door_trunkR.webp` : porte(s) + hayon
- `door_m<qui bouge>_s<ouverts>_<1..5>.webp` : animations combinées
- `door_hi_<gauche>_<droite>[_t].webp` : vue de face, portes des deux côtés (+ `_t` hayon ouvert)
- `flow_00.png` … `flow_47.png` (facultatif) : mouvement entre la vue n et la vue n+1, pour une rotation continue

Vue du dessus (`img/top` 544×600 avant en haut, `img/toph` 528×425 avant à gauche) : `closed.webp` + un calque par
ouvrant (`fl`, `fr`, `rl`, `rr`, `trunk`, `hood`) contenant seulement la pièce ouverte → `tools/render/render_top.py`.

## 5. Conversion en webp

```
python3 -c "import glob;from PIL import Image
for f in glob.glob('out/frames/*.png'): Image.open(f).save(f.replace('out/frames','app/src/main/assets/www/img/car')[:-4]+'.webp','WEBP',quality=86,method=6)"
```

## 6. Signature de l'APK

Crée ta propre clé (Android Studio > *Build > Generate Signed Bundle / APK*), puis `keystore.properties` à la racine
(non versionné) : `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. **Garde cette clé** : sans elle, impossible de
mettre à jour l'appli installée.
