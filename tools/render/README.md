# Outils de rendu 3D (Blender, en Python)

Scripts qui ont produit les images 3D de la Seat Ibiza 5 portes du lanceur. Ils servent de **référence** : chaque modèle 3D est
différent (noms des pièces, découpage des portes, charnières), donc il faut les adapter à ta voiture — c'est le rôle de
l'IA avec le prompt de `ADAPTER.md`.

Prérequis : Python 3.11+, `pip install bpy` (Blender en module Python, Cycles sur CPU), Pillow, ffmpeg.

| Fichier | Rôle |
|---|---|
| `readfbx6.py` / `read3ds.py` | lecture d'un FBX 6 (que Blender refuse) ou d'un .3DS |
| `islands.py` | sépare les pièces d'un maillage en « îlots » (portes, capot, hayon…) |
| `build.py` | matériaux de base, charnières, caméra, plateau |
| `build2.py` | **pipeline principal** : chargement, découpage des ouvrants, matériaux studio (peinture métallisée, jantes, verre), lumières, caméra |
| `batch3.py` | rendu complet : 48 vues tournantes, ouvrants en 6 étapes, combinaisons, vues de face, plateaux |
| `batch5.py` / `batch6.py` | animations combinées (un ouvrant bouge, les autres restent ouverts) |
| `render_top.py` | **vue du dessus** : `img/top` (avant en haut) et `img/toph` (avant à gauche), voiture fermée + un calque par ouvrant ouvert avec halo orange ; affiche la valeur `topBox` à mettre dans `config.js` |
| `cockpit.py` / `ckrender.py` / `mkvideo.py` | vidéo « caméra qui entre dans l'habitacle » de la page Entretien |
| `flows.py` (+ `interp.py`) | **rotation continue** (v1.22) : flux optique entre vues voisines -> `img/car/flow_XX.png` ; affiche la valeur `flowMax` à mettre dans `config.js` (OpenCV : `pip install opencv-python-headless`) |
| `recolor.py` | exemple : repeindre un pack existant sans refaire les rendus (vert lime -> blanc) |

Arborescence attendue : `model/car.fbx` (ou `.3ds`), `model/textures/…`, sorties dans `out/`.

Conversion en webp pour l'appli : voir `ADAPTER.md` (étape 5).

⚠️ Utilise un modèle 3D dont la **licence autorise la diffusion** des rendus (et un usage commercial si tu vends quelque chose).
