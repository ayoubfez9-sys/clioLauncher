# Packs voiture (lanceur 1.23 ou plus)

Un pack change la voiture affichée sans recompiler ni réinstaller le lanceur.

## Contenu d'un pack (.zip)

| Fichier | Rôle |
|---|---|
| `pack.json` | `{"format":1, "id":"seat-ibiza-6j-blanc", "name":"Seat Ibiza 6J blanche", "version":"1", "config":{…}}` |
| `preview.jpg` | miniature affichée dans les réglages et dans l'espace client |
| `img/car/…`, `img/top/…`, `img/toph/…`, `img/cockpit/…` | mêmes noms de fichiers que dans l'appli (`ADAPTER.md`, partie 4) |

`config` reprend les clés de `config.js` liées aux images : `frames`, `doorViews`, `doorSteps`, `homeView`, `hiView`,
`comboAnim`, `topView`, `topBox`, `cockpit`, `flowMax`, et `car.name` / `car.model` (facultatifs). Le reste de `config.js`
(carburant…) ne change pas.
Depuis la 1.24.1, une option absente du pack (ou dont les images manquent) est désactivée au lieu de reprendre la
valeur de la voiture intégrée, et aucune image de voiture n'est plus prise dans l'appli quand un pack est actif.

## Fabriquer un pack

```
python3 tools/pack/make_pack.py --www app/src/main/assets/www --id golf-7-noir --name "Golf 7 noire"
```
Même voiture dans une autre couleur (même géométrie) : `--flows dossier_des_flow_XX.png --flow-max 88` réutilise les flux de
rotation fluide de la première couleur.

## Installer

Réglages du lanceur > Voiture 3D > Pack voiture > **Clé USB** (sélecteur de fichiers), ou copier le zip dans
`Android/data/fr.ibiza.launcher/files/packs` puis **Dossier du lanceur**. Un pack placé dans `app/src/main/assets/packs/<id>.zip`
est installé au premier lancement (sans être affiché).
