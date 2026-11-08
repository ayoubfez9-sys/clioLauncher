# Outils du modèle 3D

`bake.py` découpe la carrosserie du modèle d'origine (`clio_4.glb`, Sketchfab, claveranass, CC BY 4.0) en pièces
séparées en suivant la géométrie :
- logos avant/arrière, baguettes de bas de caisse, essuie-glaces, supports de plaque = « îlots » de triangles ;
- bandeau entre les phares = triangles dont les 3 sommets sont entre les vrais phares ;
- encadrements de vitres = triangles dont les 3 sommets sont à moins de 6 cm des vraies vitres latérales.

Il écrit de nouvelles matières `trim_black`, `trim_grey`, `logo_chrome` (définies dans `GlbModel.kt`).

    python3 -I tools/bake.py clio_4.glb app/src/main/assets/models/clio4.glb
