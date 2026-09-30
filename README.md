# StepSimulator v2 – Health Connect

Application Android (Kotlin) qui écrit, chaque matin, une séance de pas dans **Health Connect** :
à **6h35**, une alarme réveille l'app quelques secondes et elle insère d'un coup la séance
**6h00–6h35** (35 enregistrements d'une minute, 6 200 à 6 800 pas au total). Plus aucun service
en arrière-plan pendant la séance : c'était le maillon fragile de la v1.

## Récupérer l'APK (sans Android Studio)

Chaque `git push` déclenche le workflow **Build APK** (GitHub Actions) qui compile l'APK de debug
et le publie dans la pré-release **`v2-latest`** :

1. Sur le téléphone, ouvrir `https://github.com/RoStat/StepSimulator/releases/tag/v2-latest`
2. Télécharger `StepSimulator-v2-debug.apk`
3. Autoriser l'installation depuis le navigateur (« sources inconnues ») puis installer.

L'APK est aussi disponible comme artefact du run dans l'onglet *Actions*. Tous les APK sont signés
avec la même clé de debug versionnée (`app/debug.keystore`, mot de passe `android`) : une nouvelle
version s'installe par-dessus l'ancienne sans désinstaller.

Alternative USB : `adb install -r StepSimulator-v2-debug.apk` avec les *SDK Platform Tools*
(zip de ~10 Mo, pas besoin d'Android Studio).

## Mise en service (5 minutes)

1. Ouvrir l'app, accepter les notifications.
2. Bouton **1 · Autoriser l'écriture des pas** → dans l'écran Health Connect, autoriser « Pas ».
   Sous Android 13 et moins, l'app Health Connect doit d'abord être installée depuis le Play Store
   (le bouton y renvoie).
3. Bouton **Exclure de l'optimisation batterie** → accepter.
4. Bouton **Démarrage automatique (HyperOS / MIUI)** → activer Step Simulator dans la liste.
   Puis, dans *Paramètres → Applications → Step Simulator → Économiseur de batterie* : **Aucune restriction**.
5. Vérifier dans l'écran principal que toutes les lignes sont ✔ et lire l'heure de la prochaine séance.

L'alarme est (ré)armée à chaque ouverture de l'app, à chaque déclenchement et après un redémarrage.

## Recette (dans l'ordre, le test 3 est bloquant)

| # | Test | Comment |
|---|------|---------|
| 1 | L'app s'installe et s'ouvre sans plantage | Installer l'APK, ouvrir |
| 2 | Permission d'écriture des pas accordée | Bouton 1, puis ligne « Permission … accordée ✔ » |
| 3 | « Tester maintenant » : ≈ 6 500 pas visibles dans Health Connect en < 1 min | Bouton 2, puis Health Connect → Pas (séance = les 35 dernières minutes) |
| 4 | Les pas apparaissent dans Kiplin après synchronisation | Vérifier d'abord que Kiplin a l'autorisation de lecture des pas dans Health Connect |
| 5 | Le lendemain, écran éteint, téléphone non touché : la séance 6h00–6h35 est présente à 7h | Une notification « Séance de pas écrite » confirme ; sinon lire « Dernier résultat » dans l'app |

Le bouton **Effacer les pas écrits par l'app (24 h)** supprime les séances de test : Health Connect
n'autorise une app à effacer que ses propres données.

## Réglages

Tout se règle en tête de `app/src/main/java/com/rostat/stepsimulator/AlarmReceiver.kt` (objet `Seance`) :
`HEURE_DEBUT`, `HEURE_ALARME`, `DUREE_MINUTES`, `PAS_MIN`, `PAS_MAX`. Modifier le fichier directement
sur GitHub suffit : la CI reconstruit l'APK.

Méthode d'enregistrement déclarée à Health Connect (`Seance.metadonnees()`) : par défaut
`Metadata.autoRecorded(Device(TYPE_PHONE))` (« enregistré automatiquement par le téléphone »),
car certaines applications de challenge ignorent les pas marqués « saisie manuelle ».
Alternative transparente : `Metadata.manualEntry()`.

## Structure

| Fichier | Rôle |
|---------|------|
| `app/src/main/AndroidManifest.xml` | Permissions (WRITE_STEPS, notifications, boot), écran, alias Health Connect, récepteur |
| `app/build.gradle.kts` | Dépendances (connect-client 1.1.0), SDK 36, signature de debug |
| `app/src/main/java/com/rostat/stepsimulator/MainActivity.kt` | Écran principal : diagnostic, permissions, « Tester maintenant », raccourcis réglages |
| `app/src/main/java/com/rostat/stepsimulator/AlarmReceiver.kt` | Écriture de la séance, planification 6h35, récepteur alarme + redémarrage |
| `.github/workflows/build-apk.yml` | Compilation et publication de l'APK dans le cloud |

Le reste (`settings.gradle.kts`, `build.gradle.kts`, `gradle/`, `gradlew`, `debug.keystore`) est
l'ossature standard d'un projet Gradle Android, sans logique.

## Comportement et garde-fous

- Alarme **inexacte** (`setAndAllowWhileIdle`) : sonne même téléphone en veille profonde, avec
  quelques minutes de décalage possibles ; aucune permission d'alarme exacte demandée.
- La séance écrite est toujours **dans le passé** (fin à 6h35, alarme à 6h35 ou après).
- Une seule séance automatique par jour, même en cas de double déclenchement ou de redémarrage.
- Si l'alarme est délivrée après minuit (téléphone éteint la veille), la séance du jour n'est pas
  encore terminée : rien n'est écrit, la prochaine occurrence est réarmée.
- Chaque écriture (succès ou échec) produit une notification et met à jour « Dernier résultat ».

## Hors périmètre

Play Store, Google Fit, iPhone, montre connectée, GPS/distance.
