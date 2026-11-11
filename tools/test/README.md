# Tests du lecteur d'écran générique

`ScreenRead.java` (repérage des valeurs d'une appli CAN par leurs textes et leur position) n'utilise rien d'Android :
il se teste sur un ordinateur, avec un JDK seulement.

```
javac -d /tmp/t app/src/main/java/fr/ibiza/launcher/ScreenRead.java tools/test/ScreenReadTest.java
java -cp /tmp/t fr.ibiza.launcher.ScreenReadTest
```

Chaque page du test imite une mise en page réelle (CarCan en liste et en grille, liste anglaise en unités impériales,
tuiles empilées, menu sans données). Pour ajouter l'appli CAN d'un client : reprends le relevé « APPLI CAN : capture
page… » de son journal (identifiant, texte et cadre de chaque texte) et ajoute une page au test.
