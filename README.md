# Clio Launcher : étape 1 (interface)

Écran d'accueil façon Tesla pour l'autoradio Junsun de la Clio 4
(RK3326, Android 15, 1280×720, boîtier CAN SimpleSoft « CLIO_XP01 »).

---

## 1. Obtenir l'APK

Tu as deux possibilités. La A est la plus simple : rien à installer sur ton PC.

### A. Avec GitHub (gratuit, dans le navigateur)
1. Crée un compte sur https://github.com puis un **nouveau dépôt** (bouton « New »), en **Private**.
2. Sur la page du dépôt, clique sur **« uploading an existing file »**.
3. Dézippe `ClioLauncher.zip` sur ton PC. Ouvre le dossier `ClioLauncher` et **glisse tout son contenu**
   dans la page, y compris le dossier `.github`. Clique sur **Commit changes**.
4. Va dans l'onglet **Actions**. La compilation démarre toute seule et prend environ 5 minutes.
   Si GitHub demande d'activer les Actions, accepte.
5. Quand la ligne devient verte ✔, clique dessus. En bas, dans **Artifacts**, télécharge
   **ClioLauncher-apk**. C'est un zip qui contient le fichier `app-release.apk`.

### B. Avec Android Studio (sur PC)
1. Installe Android Studio (https://developer.android.com/studio).
2. Fais **File → Open** et choisis le dossier `ClioLauncher`. Attends la fin du chargement
   (barre en bas), ce qui peut prendre 10 minutes la première fois.
3. Fais **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. Clique sur « locate » : le fichier est `app/build/outputs/apk/debug/app-debug.apk`.

> Les deux méthodes signent l'APK avec la même clé (`app/clio.jks`).
> Les prochaines versions s'installeront donc **par-dessus**, sans désinstaller.

## 2. Installer sur le Junsun
1. Copie l'APK sur une **clé USB**, puis branche-la sur l'autoradio.
2. Ouvre le **gestionnaire de fichiers** de l'autoradio et touche l'APK.
3. Si Android demande d'autoriser les « sources inconnues », accepte pour le gestionnaire de fichiers.
4. Touche **Installer**.

## 3. Réglages sur l'autoradio

### Définir Clio Launcher comme écran d'accueil
- Appuie sur le bouton **Accueil** (la maison). Android demande quelle appli utiliser :
  choisis **Clio Launcher**, puis **Toujours**.
- Si rien ne s'affiche : ouvre Clio Launcher → **Réglages → Launcher par défaut → Ouvrir**,
  puis choisis Clio Launcher.
- ⚠️ Certains firmwares Junsun remettent leur launcher d'origine à chaque démarrage.
  Si c'est ton cas, dis-le-moi.

### Autoriser la position (vitesse GPS + météo)
Clio Launcher → **Réglages → Position → Autoriser**.

### Autoriser la musique en cours
Clio Launcher → **Réglages → Musique en cours → Autoriser**, puis active « Clio Launcher ».

Si l'interrupteur est **grisé** (« paramètre restreint ») : c'est une protection d'Android 13 et plus
pour les applis installées depuis un APK. Pour la lever :
1. Clio Launcher → Réglages → **Infos de l'appli → Ouvrir**.
2. Menu **⋮** en haut à droite → **Autoriser les paramètres restreints**.
3. Recommence l'autorisation de la musique.

Si l'écran n'existe pas sur l'autoradio, il reste la commande ADB (depuis un PC) :
```
adb shell cmd notification allow_listener fr.ayoub.cliolauncher/fr.ayoub.cliolauncher.media.MediaListenerService
```

### Choisir les applis CarPlay et Radio
Le launcher essaie de les trouver tout seul. Sinon : **Réglages → Appli CarPlay / Appli Radio FM → Choisir**.

## 4. Revenir au launcher d'origine (en cas de problème)
Du plus simple au plus radical :
1. Clio Launcher → **Réglages → Launcher par défaut** → choisis le launcher Junsun.
2. **Paramètres Android → Applications → Applications par défaut → Appli d'accueil** → launcher Junsun.
3. **Paramètres Android → Applications → Clio Launcher → Désinstaller**. Android revient tout seul
   au launcher d'origine.
4. Depuis un PC : `adb uninstall fr.ayoub.cliolauncher`.

Tu ne modifies rien dans le système : désinstaller l'appli remet tout comme avant.

## 5. Ce qui est fait, simulé, ou dépend du matériel

| Élément | État |
|---|---|
| Mise en page 3 colonnes, thème sombre, police Rajdhani | ✅ Fait |
| Heure, date en français | ✅ Fait |
| Météo (Open-Meteo) | ✅ Fait, **si l'autoradio a internet** (partage de connexion du téléphone) |
| Vitesse en km/h | ✅ Réelle, via le **GPS** de l'autoradio (pas via le CAN) |
| Raccourcis Waze, Maps, Chrome, Play Store, tiroir d'applis | ✅ Fait (grisés si l'appli n'est pas installée) |
| Tuile CarPlay → ouvre Zlink | ✅ Fait (détection auto ou choix manuel) |
| Tuile Radio FM → ouvre l'appli radio Junsun | ✅ Fait (détection auto ou choix manuel) |
| Lecteur de musique (titre, progression, ⏮ ⏯ ⏭) | ✅ Fait pour les applis qui publient leur lecture (Spotify, YouTube Music, Deezer…). ⚠️ Non garanti pour la musique du téléphone via **CarPlay/Zlink** ni pour le lecteur Junsun |
| Mode nuit | ✅ Baisse la luminosité et assombrit l'écran |
| Modèle 3D de la Clio sur le plateau | ⏳ Étape 2 (le plateau et l'anneau bleu sont déjà là) |
| Phares et portes animés | ⏳ Étape 3 (les portes doivent d'abord être découpées dans Blender) |
| Feux et portes de la vraie voiture | 🟠 **Boutons manuels** (écran Véhicule). Lecture du CAN SimpleSoft : à vérifier à l'étape 4, **rien n'est promis** |

## Crédits
- Modèle 3D « clio 4 » par claveranass (Sketchfab), licence CC BY 4.0.
- Police Rajdhani (Indian Type Foundry), licence SIL Open Font License. Elle est téléchargée automatiquement à la compilation.
- Données météo : Open-Meteo.com, licence CC BY 4.0.
