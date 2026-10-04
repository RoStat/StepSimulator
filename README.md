# StepSimulator v3 – Health Connect

Application Android (Kotlin) qui écrit des séances de pas dans **Health Connect**, sans rien faire
tourner en arrière-plan pendant la séance :

- **Séance quotidienne automatique** : à l'heure de fin paramétrée, une alarme réveille l'app quelques
  secondes et elle insère d'un coup la séance déjà passée, minute par minute.
- **Séance manuelle** : distance (km) ou nombre de pas, durée, fin « maintenant » ou à une heure déjà
  passée dans la journée. Écriture immédiate.

Tout est réglable dans l'app : heure de début, durée, objectif en pas **ou** en km, variation
aléatoire, longueur de foulée, méthode déclarée à Health Connect, écriture optionnelle de la distance.

## Récupérer l'APK (sans Android Studio)

Chaque `git push` déclenche le workflow **Build APK** (GitHub Actions) qui compile l'APK de debug
et le publie dans la pré-release **`latest`** :

1. Sur le téléphone, ouvrir `https://github.com/RoStat/StepSimulator/releases/tag/latest`
2. Déplier *Assets*, télécharger `StepSimulator-v3.0-debug.apk`
3. Autoriser l'installation depuis le navigateur (« sources inconnues »), installer. La v3 s'installe
   par-dessus la v2 sans désinstaller : tous les APK sont signés avec la même clé de debug
   versionnée (`app/debug.keystore`, mot de passe `android`).

L'ancienne v2 reste disponible dans la release `v2-latest`.

## Mise en service

1. Ouvrir l'app, accepter les notifications.
2. **Autoriser l'écriture dans Health Connect** → cocher « Pas » (et « Distance » si vous voulez
   l'option distance). Sous Android 13 et moins, installer d'abord l'app Health Connect (Play Store).
3. **Exclure de l'optimisation batterie** → accepter.
4. **Démarrage automatique (HyperOS / MIUI)** → activer Step Simulator ; puis *Paramètres →
   Applications → Step Simulator → Économiseur de batterie* : **Aucune restriction**.
5. Régler la séance quotidienne, puis **Enregistrer les réglages et réarmer l'alarme**.

L'alarme est (ré)armée à chaque ouverture de l'app, à chaque enregistrement des réglages,
à chaque déclenchement et après un redémarrage.

## Réglages

| Réglage | Défaut | Rôle |
|---|---|---|
| Séance quotidienne activée | oui | Désactiver annule l'alarme |
| Heure de début | 06:00 | Début de la séance écrite |
| Durée (minutes) | 35 | Fin = début + durée = heure de l'alarme |
| Objectif par séance | 6 500 pas | En pas ou en km (converti avec la foulée) |
| Variation aléatoire (±%) | 5 | Le total du jour varie pour ne pas être identique chaque jour |
| Longueur d'un pas (cm) | 72 | Conversion pas ↔ km. Marche ≈ 72, course ≈ 100 |
| Déclarer comme saisie manuelle | non | Non : « enregistré automatiquement par le téléphone ». Oui : marquage transparent |
| Écrire aussi la distance | non | Ajoute un enregistrement de distance par minute (permission Distance requise) |

L'aperçu sous chaque séance affiche pas, km et cadence, avec une alerte si la cadence est peu
crédible. Repères : marche 100–130 pas/min, course 150–190.

## Recette (le test 3 est bloquant)

| # | Test | Comment |
|---|------|---------|
| 1 | L'app s'installe et s'ouvre sans plantage | Installer l'APK, ouvrir |
| 2 | Permission d'écriture accordée | Bouton d'autorisation, puis « Permission pas : accordée ✔ » |
| 3 | Séance manuelle : les pas apparaissent dans Health Connect en < 1 min | Section « Séance manuelle », « Écrire la séance manuelle », puis Health Connect → Pas |
| 4 | Les pas apparaissent dans Kiplin après synchronisation | Vérifier d'abord que Kiplin a la lecture des pas dans Health Connect |
| 5 | Le lendemain, écran éteint, téléphone non touché : la séance est présente à l'heure de fin + 25 min | Notification « Séance de pas écrite » ; sinon « Dernier résultat » dans l'app |

**Effacer les données écrites par l'app (24 h)** supprime les séances de test : Health Connect
n'autorise une app à effacer que ses propres données.

## Structure

| Fichier | Rôle |
|---------|------|
| `app/src/main/AndroidManifest.xml` | Permissions (pas, distance, notifications, boot), écran, alias Health Connect, récepteur |
| `app/build.gradle.kts` | Dépendances (connect-client 1.1.0), SDK 36, signature de debug |
| `app/src/main/java/com/rostat/stepsimulator/Parametres.kt` | Réglages persistants, conversions pas ↔ km, contrôle des bornes |
| `app/src/main/java/com/rostat/stepsimulator/MainActivity.kt` | Écran : état, formulaire de réglages, séance manuelle, outils |
| `app/src/main/java/com/rostat/stepsimulator/AlarmReceiver.kt` | Écriture Health Connect, planification de l'alarme, récepteur alarme + redémarrage |
| `.github/workflows/build-apk.yml` | Compilation et publication de l'APK dans le cloud |

## Comportement et garde-fous

- Alarme **inexacte** (`setAndAllowWhileIdle`) : sonne même en veille profonde, avec quelques
  minutes de décalage possibles ; aucune permission d'alarme exacte.
- Une séance est toujours écrite **dans le passé** ; une fin dans le futur est refusée.
- Une seule séance automatique par jour, même en cas de double déclenchement ou de redémarrage.
- Alarme délivrée après minuit (téléphone éteint la veille) : la séance du jour n'est pas encore
  terminée, rien n'est écrit, la prochaine occurrence est réarmée.
- Chaque minute compte au moins 1 pas (exigence Health Connect) ; le total est réparti avec ±15 %
  d'aléa par minute.
- Chaque écriture (succès ou échec) produit une notification et met à jour « Dernier résultat ».

## Hors périmètre

Play Store, Google Fit, iPhone, montre connectée, GPS.
