package com.rostat.stepsimulator

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Écran principal : état du dispositif, demande de permission Health Connect,
 * "Tester maintenant", effacement des pas de test, raccourcis vers les réglages HyperOS/MIUI.
 * L'interface est construite en code (aucun layout XML) pour tenir dans 4 fichiers.
 */
class MainActivity : ComponentActivity() {

    private lateinit var texteEtat: TextView
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
        Seance.creerCanalNotification(this)
        construireInterface()
        Planificateur.planifier(this) // ouvrir l'app suffit à (ré)armer l'alarme de 6h35
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

    // ---------------------------------------------------------------- interface

    private fun construireInterface() {
        val dp = { valeur: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, valeur.toFloat(), resources.displayMetrics).toInt()
        }
        val colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        fun texte(contenu: String, taille: Float = 15f) = TextView(this).apply {
            text = contenu
            textSize = taille
            setPadding(0, dp(4), 0, dp(8))
        }.also { colonne.addView(it) }
        fun bouton(libelle: String, action: () -> Unit) = Button(this).apply {
            text = libelle
            isAllCaps = false
            setOnClickListener { action() }
        }.also { colonne.addView(it) }

        // Ouvert par Health Connect pour expliquer l'usage des données ("justification").
        val actionsJustification = setOf("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE", Intent.ACTION_VIEW_PERMISSION_USAGE)
        if (intent?.action in actionsJustification) {
            texte(
                "Pourquoi cette permission ? Step Simulator écrit chaque matin une séance de pas " +
                    "(${Seance.HEURE_DEBUT}–${Seance.HEURE_ALARME}) dans Health Connect. Il n'écrit rien d'autre et ne lit aucune donnée.",
                14f
            )
        }

        texte("Step Simulator v2", 22f)
        texte(
            "Chaque jour à ${Seance.HEURE_ALARME}, l'app écrit dans Health Connect la séance " +
                "${Seance.HEURE_DEBUT}–${Seance.HEURE_ALARME} : ${Seance.DUREE_MINUTES} enregistrements d'une minute, " +
                "${Seance.PAS_MIN}–${Seance.PAS_MAX} pas au total.",
            14f
        )
        texteEtat = texte("Chargement de l'état…", 14f)

        bouton("1 · Autoriser l'écriture des pas (Health Connect)") { demanderPermissionHc() }
        bouton("2 · Tester maintenant (${Seance.DUREE_MINUTES} min, ≈ 6 500 pas)") { testerMaintenant() }
        bouton("Effacer les pas écrits par l'app (24 h)") { effacerPasDeTest() }
        bouton("Exclure de l'optimisation batterie") { demanderExclusionBatterie() }
        bouton("Démarrage automatique (HyperOS / MIUI)") { ouvrirDemarrageAutomatique() }
        bouton("Ouvrir Health Connect") { ouvrirHealthConnect() }

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(colonne)
        })
    }

    /** Recalcule le diagnostic affiché (état HC, permissions, alarme, batterie, dernier résultat). */
    private fun rafraichirEtat() {
        lifecycleScope.launch {
            val statutHc = HealthConnectClient.getSdkStatus(this@MainActivity)
            val libelleHc = when (statutHc) {
                HealthConnectClient.SDK_AVAILABLE -> "disponible"
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "À INSTALLER / METTRE À JOUR (Play Store)"
                else -> "INDISPONIBLE sur ce téléphone"
            }
            val permissionHc = if (statutHc == HealthConnectClient.SDK_AVAILABLE) {
                runCatching {
                    Seance.PERMISSION_ECRITURE_PAS in HealthConnectClient.getOrCreate(this@MainActivity)
                        .permissionController.getGrantedPermissions()
                }.getOrDefault(false)
            } else false
            val notifOk = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val batterieExclue = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

            texteEtat.text = buildString {
                appendLine("Health Connect : $libelleHc")
                appendLine("Permission écriture des pas : ${if (permissionHc) "accordée ✔" else "MANQUANTE ✘ (bouton 1)"}")
                appendLine("Notifications : ${if (notifOk) "autorisées ✔" else "refusées (compte rendu invisible)"}")
                appendLine("Optimisation batterie : ${if (batterieExclue) "exclue ✔" else "ACTIVE ✘ (l'alarme peut être ignorée)"}")
                appendLine("Prochaine séance automatique : ${Planificateur.prochaineOccurrence().format(formatDate)}")
                append("Dernier résultat : ${Seance.dernierResultat(this@MainActivity)}")
            }
        }
    }

    // ---------------------------------------------------------------- actions

    private fun demanderPermissionHc() {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> demandePermissionHc.launch(setOf(Seance.PERMISSION_ECRITURE_PAS))
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> ouvrirPlayStoreHealthConnect()
            else -> toast("Health Connect n'est pas disponible sur ce téléphone")
        }
    }

    /** Écrit une séance de 35 minutes se terminant maintenant (jamais dans le futur). */
    private fun testerMaintenant() {
        lifecycleScope.launch {
            texteEtat.text = "Écriture en cours…"
            val fin = ZonedDateTime.now().withSecond(0).withNano(0)
            val debut = fin.minusMinutes(Seance.DUREE_MINUTES.toLong())
            val resultat = Seance.ecrire(this@MainActivity, debut, fin, origine = "test manuel")
            toast(resultat.message)
            rafraichirEtat()
        }
    }

    private fun effacerPasDeTest() {
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

    // ---------------------------------------------------------------- utilitaires

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
