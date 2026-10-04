package com.rostat.stepsimulator

import android.Manifest
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Écran principal v3 : état du dispositif, réglages de la séance quotidienne (heure, durée,
 * objectif en pas ou en km), séance manuelle (distance ou pas, durée, heure de fin), permissions
 * et raccourcis HyperOS/MIUI. Interface construite en code, sans layout XML.
 */
class MainActivity : ComponentActivity() {

    private lateinit var parametres: Parametres
    private lateinit var colonne: LinearLayout
    private lateinit var texteEtat: TextView

    // Séance quotidienne
    private lateinit var switchActive: Switch
    private lateinit var boutonHeureDebut: Button
    private var heureDebut: LocalTime = LocalTime.of(6, 0)
    private lateinit var champDuree: EditText
    private lateinit var champObjectif: EditText
    private lateinit var uniteObjectif: ChoixUnite
    private lateinit var champVariation: EditText
    private lateinit var texteApercuQuotidien: TextView

    // Réglages communs
    private lateinit var champFoulee: EditText
    private lateinit var switchSaisieManuelle: Switch
    private lateinit var switchDistance: Switch

    // Séance manuelle
    private lateinit var champManuelValeur: EditText
    private lateinit var uniteManuel: ChoixUnite
    private lateinit var champManuelDuree: EditText
    private lateinit var switchFinMaintenant: Switch
    private lateinit var boutonFinManuelle: Button
    private var finManuelle: LocalTime = LocalTime.of(7, 0)
    private lateinit var texteApercuManuel: TextView

    /** Identifiants fixes : Android restaure alors le texte saisi quand l'écran pivote. */
    private var prochainId = 1000
    private val formatDate = DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH:mm", Locale.FRENCH)

    /** Demande de permission Health Connect : ouvre l'écran système, puis rend les permissions accordées. */
    private val demandePermissionHc = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { accordees ->
        toast(if (Seance.PERMISSION_ECRITURE_PAS in accordees) "Écriture des pas autorisée" else "Permission refusée")
        rafraichirEtat()
    }

    /** Permission notifications (Android 13+). */
    private val demandePermissionNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { rafraichirEtat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parametres = Parametres.charger(this)
        heureDebut = parametres.heureDebut
        Seance.creerCanalNotification(this)
        construireInterface()
        Planificateur.planifier(this, parametres) // ouvrir l'app suffit à (ré)armer l'alarme
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            demandePermissionNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        rafraichirEtat()
    }

    // ================================================================ interface

    private fun construireInterface() {
        colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Ouvert par Health Connect pour expliquer l'usage des données ("justification").
        val actionsJustification = setOf("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE", Intent.ACTION_VIEW_PERMISSION_USAGE)
        if (intent?.action in actionsJustification) {
            texte(
                "Pourquoi cette permission ? Step Simulator écrit des séances de pas (et, en option, la " +
                    "distance) dans Health Connect, à l'heure que vous réglez. Il ne lit aucune donnée."
            )
        }

        texte("Step Simulator v3", 22f)
        texteEtat = texte("Chargement de l'état…")
        bouton("Autoriser l'écriture dans Health Connect (pas + distance)") { demanderPermissionHc() }

        // ---- Séance quotidienne ----
        titre("Séance quotidienne automatique")
        switchActive = interrupteur("Activée (alarme à l'heure de fin)", parametres.seanceActive)
        boutonHeureDebut = bouton("Heure de début : $heureDebut") {
            TimePickerDialog(this, { _, h, m ->
                heureDebut = LocalTime.of(h, m)
                boutonHeureDebut.text = "Heure de début : $heureDebut"
                majApercus()
            }, heureDebut.hour, heureDebut.minute, true).show()
        }
        champDuree = champNombre("Durée (minutes)", parametres.dureeMinutes.toString())
        champObjectif = champNombre("Objectif par séance", formatSaisie(parametres.objectifValeur, parametres.objectifEnKm), decimal = true)
        uniteObjectif = ChoixUnite("Unité de l'objectif", parametres.objectifEnKm)
        champVariation = champNombre("Variation aléatoire du total (±%)", parametres.variationPourcent.toString())
        texteApercuQuotidien = texte("")

        // ---- Réglages communs ----
        titre("Réglages communs")
        champFoulee = champNombre("Longueur d'un pas (cm) : marche ≈ 72, course ≈ 100", parametres.fouleeCm.toString())
        switchSaisieManuelle = interrupteur("Déclarer à Health Connect comme « saisie manuelle »", parametres.saisieManuelle)
        switchDistance = interrupteur("Écrire aussi la distance (km) dans Health Connect", parametres.ecrireDistance)
        bouton("Enregistrer les réglages et réarmer l'alarme") { enregistrer() }

        // ---- Séance manuelle ----
        titre("Séance manuelle")
        texte("Écrit immédiatement une séance qui se termine maintenant, ou à une heure déjà passée aujourd'hui.")
        champManuelValeur = champNombre("Distance ou pas", formatSaisie(parametres.manuelValeur, parametres.manuelEnKm), decimal = true)
        uniteManuel = ChoixUnite("Unité", parametres.manuelEnKm)
        champManuelDuree = champNombre("Durée (minutes)", parametres.manuelDureeMinutes.toString())
        switchFinMaintenant = interrupteur("Fin de la séance : maintenant", true) { maintenant ->
            boutonFinManuelle.visibility = if (maintenant) View.GONE else View.VISIBLE
        }
        boutonFinManuelle = bouton("Heure de fin : $finManuelle") {
            TimePickerDialog(this, { _, h, m ->
                finManuelle = LocalTime.of(h, m)
                boutonFinManuelle.text = "Heure de fin : $finManuelle"
            }, finManuelle.hour, finManuelle.minute, true).show()
        }
        boutonFinManuelle.visibility = View.GONE
        texteApercuManuel = texte("")
        bouton("Écrire la séance manuelle") { ecrireSeanceManuelle() }

        // ---- Outils ----
        titre("Outils")
        bouton("Effacer les données écrites par l'app (24 h)") { effacerDonnees() }
        bouton("Exclure de l'optimisation batterie") { demanderExclusionBatterie() }
        bouton("Démarrage automatique (HyperOS / MIUI)") { ouvrirDemarrageAutomatique() }
        bouton("Ouvrir Health Connect") { ouvrirHealthConnect() }

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(colonne)
        })
        majApercus()
    }

    // ---- fabriques de vues ----

    private fun dp(valeur: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, valeur.toFloat(), resources.displayMetrics).toInt()

    private fun titre(contenu: String) = TextView(this).apply {
        text = contenu
        textSize = 18f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }.also { colonne.addView(it) }

    private fun texte(contenu: String, taille: Float = 14f) = TextView(this).apply {
        text = contenu
        textSize = taille
        setPadding(0, dp(2), 0, dp(6))
    }.also { colonne.addView(it) }

    private fun bouton(libelle: String, action: () -> Unit) = Button(this).apply {
        text = libelle
        isAllCaps = false
        setOnClickListener { action() }
    }.also { colonne.addView(it) }

    private fun interrupteur(libelle: String, coche: Boolean, action: (Boolean) -> Unit = {}) = Switch(this).apply {
        id = prochainId++
        text = libelle
        isChecked = coche
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, valeur -> action(valeur) }
    }.also { colonne.addView(it) }

    /** Ligne "libellé ....... [champ numérique]" ; toute modification met à jour les aperçus. */
    private fun champNombre(libelle: String, valeur: String, decimal: Boolean = false): EditText {
        val ligne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ligne.addView(
            TextView(this).apply { text = libelle; textSize = 14f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val champ = EditText(this).apply {
            id = prochainId++
            setText(valeur)
            inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            gravity = Gravity.END
            doAfterTextChanged { majApercus() }
        }
        ligne.addView(champ, LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
        colonne.addView(ligne)
        return champ
    }

    /** Ligne "libellé  (•) pas  ( ) km". */
    private inner class ChoixUnite(libelle: String, enKm: Boolean) {
        private val idPas = prochainId++
        private val idKm = prochainId++
        private val groupe = RadioGroup(this@MainActivity).apply {
            id = prochainId++
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@MainActivity).apply { id = idPas; text = "pas" })
            addView(RadioButton(this@MainActivity).apply { id = idKm; text = "km" })
            check(if (enKm) idKm else idPas)
            setOnCheckedChangeListener { _, _ -> majApercus() }
        }

        init {
            val ligne = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            ligne.addView(
                TextView(this@MainActivity).apply { text = libelle; textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            ligne.addView(groupe)
            colonne.addView(ligne)
        }

        val enKm: Boolean get() = groupe.checkedRadioButtonId == idKm
    }

    // ================================================================ lecture et aperçus

    private fun EditText.entier(): Int? = text.toString().trim().toIntOrNull()
    private fun EditText.decimal(): Double? = text.toString().trim().replace(',', '.').toDoubleOrNull()

    /** Valeur affichée dans un champ : entier pour des pas, une décimale pour des km. */
    private fun formatSaisie(valeur: Double, enKm: Boolean): String =
        if (enKm) String.format(Locale.ROOT, "%.1f", valeur) else valeur.toInt().toString()

    /** Construit les réglages depuis le formulaire ; null (et un message) si un champ est invalide. */
    private fun lireFormulaire(): Parametres? {
        val duree = champDuree.entier()
        val objectif = champObjectif.decimal()
        val variation = champVariation.entier()
        val foulee = champFoulee.entier()
        val manuelValeur = champManuelValeur.decimal()
        val manuelDuree = champManuelDuree.entier()
        if (duree == null || objectif == null || variation == null || foulee == null || manuelValeur == null || manuelDuree == null) {
            toast("Un champ numérique est vide ou invalide")
            return null
        }
        val nouveaux = Parametres(
            seanceActive = switchActive.isChecked,
            heureDebut = heureDebut,
            dureeMinutes = duree,
            objectifValeur = objectif,
            objectifEnKm = uniteObjectif.enKm,
            variationPourcent = variation,
            fouleeCm = foulee,
            saisieManuelle = switchSaisieManuelle.isChecked,
            ecrireDistance = switchDistance.isChecked,
            manuelValeur = manuelValeur,
            manuelEnKm = uniteManuel.enKm,
            manuelDureeMinutes = manuelDuree,
        )
        nouveaux.erreur()?.let { toast(it); return null }
        return nouveaux
    }

    /** Aperçus "≈ pas · km · cadence" recalculés à chaque frappe. */
    private fun majApercus() {
        if (!::texteApercuManuel.isInitialized) return
        val foulee = champFoulee.entier()?.takeIf { it in 30..150 } ?: 72
        fun apercu(valeur: Double?, enKm: Boolean, duree: Int?): String {
            if (valeur == null || duree == null || duree < 1) return "Valeurs incomplètes"
            val pas = Parametres.pasDepuis(valeur, enKm, foulee)
            val km = Parametres.kmDepuisPas(pas, foulee)
            val cadence = pas / duree
            val lecture = when {
                cadence > 200 -> "⚠ cadence de sprint, peu crédible"
                cadence >= 150 -> "course"
                cadence >= 80 -> "marche"
                else -> "⚠ cadence très lente"
            }
            return "≈ ${Seance.formatPas(pas)} pas · ${Seance.formatKm(km)} km · $cadence pas/min ($lecture)"
        }
        val duree = champDuree.entier()
        texteApercuQuotidien.text = apercu(champObjectif.decimal(), uniteObjectif.enKm, duree) +
            "\nAlarme et écriture à ${heureDebut.plusMinutes((duree ?: 0).toLong())}"
        texteApercuManuel.text = apercu(champManuelValeur.decimal(), uniteManuel.enKm, champManuelDuree.entier())
    }

    /** Diagnostic affiché en tête d'écran. */
    private fun rafraichirEtat() {
        lifecycleScope.launch {
            val statutHc = HealthConnectClient.getSdkStatus(this@MainActivity)
            val libelleHc = when (statutHc) {
                HealthConnectClient.SDK_AVAILABLE -> "disponible"
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "À INSTALLER / METTRE À JOUR (Play Store)"
                else -> "INDISPONIBLE sur ce téléphone"
            }
            val accordees = if (statutHc == HealthConnectClient.SDK_AVAILABLE) {
                runCatching {
                    HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions()
                }.getOrDefault(emptySet())
            } else emptySet()
            val permissionPas = Seance.PERMISSION_ECRITURE_PAS in accordees
            val permissionDistance = Seance.PERMISSION_ECRITURE_DISTANCE in accordees
            val notifOk = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val batterieExclue = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
            val prochaine = Planificateur.prochaineOccurrence(parametres.heureFin)

            texteEtat.text = buildString {
                appendLine("Health Connect : $libelleHc")
                appendLine("Permission pas : ${if (permissionPas) "accordée ✔" else "MANQUANTE ✘"} · distance : ${if (permissionDistance) "accordée ✔" else "manquante (optionnelle)"}")
                appendLine("Notifications : ${if (notifOk) "autorisées ✔" else "refusées (compte rendu invisible)"}")
                appendLine("Optimisation batterie : ${if (batterieExclue) "exclue ✔" else "ACTIVE ✘ (l'alarme peut être ignorée)"}")
                if (parametres.seanceActive) {
                    appendLine(
                        "Séance quotidienne : ${parametres.heureDebut}–${parametres.heureFin}, " +
                            "${Seance.formatPas(parametres.objectifPas())} pas (${Seance.formatKm(parametres.kmDepuisPas(parametres.objectifPas()))} km) " +
                            "±${parametres.variationPourcent} %"
                    )
                    appendLine("Prochaine écriture : ${prochaine.format(formatDate)}")
                } else {
                    appendLine("Séance quotidienne : DÉSACTIVÉE")
                }
                append("Dernier résultat : ${Seance.dernierResultat(this@MainActivity)}")
            }
        }
    }

    // ================================================================ actions

    private fun enregistrer() {
        val nouveaux = lireFormulaire() ?: return
        nouveaux.sauvegarder(this)
        parametres = nouveaux
        val prochaine = Planificateur.planifier(this, nouveaux)
        toast(if (prochaine != null) "Réglages enregistrés, prochaine écriture ${prochaine.format(formatDate)}" else "Réglages enregistrés, séance quotidienne désactivée")
        rafraichirEtat()
    }

    /** Écrit une séance de la valeur et de la durée saisies, se terminant maintenant ou à l'heure choisie. */
    private fun ecrireSeanceManuelle() {
        val nouveaux = lireFormulaire() ?: return
        nouveaux.sauvegarder(this)
        parametres = nouveaux
        val maintenant = ZonedDateTime.now().withSecond(0).withNano(0)
        val fin = if (switchFinMaintenant.isChecked) maintenant else maintenant.with(finManuelle)
        if (fin.isAfter(maintenant)) {
            toast("L'heure de fin est dans le futur : choisissez une heure déjà passée")
            return
        }
        val debut = fin.minusMinutes(nouveaux.manuelDureeMinutes.toLong())
        lifecycleScope.launch {
            texteEtat.text = "Écriture en cours…"
            val resultat = Seance.ecrire(this@MainActivity, debut, fin, nouveaux.manuelPas(), nouveaux, origine = "séance manuelle")
            toast(resultat.message)
            rafraichirEtat()
        }
    }

    private fun demanderPermissionHc() {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE ->
                demandePermissionHc.launch(setOf(Seance.PERMISSION_ECRITURE_PAS, Seance.PERMISSION_ECRITURE_DISTANCE))
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> ouvrirPlayStoreHealthConnect()
            else -> toast("Health Connect n'est pas disponible sur ce téléphone")
        }
    }

    private fun effacerDonnees() {
        lifecycleScope.launch {
            val resultat = Seance.effacer(this@MainActivity)
            toast(resultat.message)
            rafraichirEtat()
        }
    }

    private fun demanderExclusionBatterie() {
        val intention = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        lancer(intention) ?: lancer(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** Écran "Démarrage automatique" de MIUI/HyperOS, sinon la fiche de l'app dans les réglages. */
    private fun ouvrirDemarrageAutomatique() {
        val miui = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        val fiche = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        if (lancer(miui) == null) {
            lancer(fiche)
            toast("Dans la fiche de l'app : activer « Démarrage automatique » et « Aucune restriction » (batterie)")
        }
    }

    private fun ouvrirHealthConnect() {
        lancer(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
            ?: toast("Impossible d'ouvrir Health Connect")
    }

    private fun ouvrirPlayStoreHealthConnect() {
        val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
        val intention = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.android.vending")
            putExtra("overlay", true)
            putExtra("callerId", packageName)
        }
        lancer(intention) ?: toast("Installez « Health Connect » depuis le Play Store")
    }

    // ================================================================ utilitaires

    /** Lance une activité ; renvoie null si aucune activité ne répond (au lieu de planter). */
    private fun lancer(intention: Intent): Unit? = try {
        startActivity(intention)
        Unit
    } catch (e: ActivityNotFoundException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
