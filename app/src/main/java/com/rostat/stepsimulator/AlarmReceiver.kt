package com.rostat.stepsimulator

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.random.Random

/*
 * Ce fichier regroupe toute la logique "métier" de la v2, en trois blocs :
 *   1. Seance        : construction et écriture des 35 enregistrements de pas dans Health Connect
 *                      (+ effacement, notification, mémorisation du dernier résultat).
 *   2. Planificateur : calcul de la prochaine occurrence de 6h35 et armement de l'alarme.
 *   3. AlarmReceiver : point d'entrée déclenché par l'alarme (écriture) et par le redémarrage (réarmement).
 *
 * Principe v2 : rien ne tourne en arrière-plan pendant la séance. À 6h35 l'alarme réveille
 * l'app quelques secondes, qui écrit d'un coup la séance 6h00-6h35 déjà passée, minute par minute.
 */

/** Paramètres de la séance et opérations Health Connect. */
object Seance {
    const val TAG = "StepSimulator"

    // ---- Réglages (les seules valeurs à toucher pour changer la séance) ----
    val HEURE_DEBUT: LocalTime = LocalTime.of(6, 0)    // début de la séance écrite
    val HEURE_ALARME: LocalTime = LocalTime.of(6, 35)  // heure de réveil : la séance est déjà terminée
    const val DUREE_MINUTES = 35                       // durée de la séance = nombre d'enregistrements
    const val PAS_MIN = 6200                           // total quotidien tiré au sort dans [PAS_MIN, PAS_MAX]
    const val PAS_MAX = 6800                           // ("environ 6 500", sans être identique chaque jour)

    /** Permission Health Connect demandée : "android.permission.health.WRITE_STEPS". */
    val PERMISSION_ECRITURE_PAS: String = HealthPermission.getWritePermission(StepsRecord::class)

    const val CANAL_NOTIF = "seances"
    private const val PREFS = "stepsimulator"
    private const val CLE_DATE_DERNIERE_SEANCE = "date_derniere_seance"
    private const val CLE_DERNIER_RESULTAT = "dernier_resultat"
    private val FORMAT_HEURE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)
    private val FORMAT_DATE_HEURE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.FRENCH)

    /** Compte rendu d'une opération, affiché à l'écran, notifié et mémorisé. */
    data class Resultat(val succes: Boolean, val message: String)

    /**
     * Métadonnées attachées à chaque enregistrement.
     * Choix v2 : "enregistré automatiquement par le téléphone", car certaines applications de
     * challenge ignorent les pas marqués "saisie manuelle". Pour l'alternative transparente,
     * remplacer par : Metadata.manualEntry()
     */
    private fun metadonnees(): Metadata = Metadata.autoRecorded(
        Device(type = Device.TYPE_PHONE, manufacturer = Build.MANUFACTURER, model = Build.MODEL)
    )

    /**
     * Répartit [total] pas sur [minutes] enregistrements avec un léger aléa (±15 %) pour éviter
     * un rythme parfaitement régulier. La dernière minute absorbe l'arrondi : la somme vaut [total].
     */
    fun repartirPas(total: Int, minutes: Int, alea: Random = Random.Default): List<Long> {
        require(minutes > 0) { "Durée invalide" }
        val poids = List(minutes) { 0.85 + alea.nextDouble() * 0.30 }
        val sommePoids = poids.sum()
        val repartition = poids.map { (it / sommePoids * total).roundToLong() }.toMutableList()
        repartition[repartition.lastIndex] += total - repartition.sum()
        return repartition
    }

    /**
     * Écrit la séance [debut, fin[ dans Health Connect : un StepsRecord par minute.
     * Appelée par l'alarme (séance 6h00-6h35) et par le bouton "Tester maintenant".
     * Ne lève jamais d'exception : le résultat est renvoyé, notifié et mémorisé.
     */
    suspend fun ecrire(context: Context, debut: ZonedDateTime, fin: ZonedDateTime, origine: String): Resultat {
        val resultat = try {
            when {
                HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE ->
                    Resultat(false, "Health Connect indisponible sur ce téléphone")

                else -> {
                    val client = HealthConnectClient.getOrCreate(context)
                    val accordees = client.permissionController.getGrantedPermissions()
                    if (PERMISSION_ECRITURE_PAS !in accordees) {
                        Resultat(false, "Permission d'écriture des pas non accordée : ouvrez l'app et autorisez Health Connect")
                    } else {
                        val minutes = Duration.between(debut, fin).toMinutes().toInt()
                        val total = Random.nextInt(PAS_MIN, PAS_MAX + 1)
                        val enregistrements = repartirPas(total, minutes).mapIndexed { i, pas ->
                            val debutMinute = debut.plusMinutes(i.toLong())
                            val finMinute = debutMinute.plusMinutes(1)
                            StepsRecord(
                                startTime = debutMinute.toInstant(),
                                startZoneOffset = debutMinute.offset,
                                endTime = finMinute.toInstant(),
                                endZoneOffset = finMinute.offset,
                                count = pas,
                                metadata = metadonnees()
                            )
                        }
                        client.insertRecords(enregistrements)
                        Resultat(true, "$total pas écrits de ${debut.format(FORMAT_HEURE)} à ${fin.format(FORMAT_HEURE)} ($origine)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Échec de l'écriture ($origine)", e)
            Resultat(false, "Échec de l'écriture : ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        }
        memoriser(context, resultat)
        notifier(context, if (resultat.succes) "Séance de pas écrite" else "Séance de pas NON écrite", resultat.message)
        return resultat
    }

    /**
     * Efface les pas écrits par CETTE application au cours des [heures] dernières heures.
     * Health Connect n'autorise une app à supprimer que ses propres données : aucun risque
     * pour les pas d'autres sources.
     */
    suspend fun effacer(context: Context, heures: Long = 24): Resultat {
        val resultat = try {
            val client = HealthConnectClient.getOrCreate(context)
            val maintenant = Instant.now()
            client.deleteRecords(
                StepsRecord::class,
                TimeRangeFilter.between(maintenant.minus(Duration.ofHours(heures)), maintenant)
            )
            Resultat(true, "Pas écrits par l'app effacés (dernières $heures h)")
        } catch (e: Exception) {
            Log.e(TAG, "Échec de l'effacement", e)
            Resultat(false, "Échec de l'effacement : ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        }
        memoriser(context, resultat)
        return resultat
    }

    // ---- Notification ----

    fun creerCanalNotification(context: Context) {
        val gestionnaire = context.getSystemService(NotificationManager::class.java)
        gestionnaire.createNotificationChannel(
            NotificationChannel(CANAL_NOTIF, "Séances de pas", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun notifier(context: Context, titre: String, texte: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        creerCanalNotification(context)
        val ouvrirApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CANAL_NOTIF)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle(titre)
            .setContentText(texte)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texte))
            .setContentIntent(ouvrirApp)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(1, notification)
    }

    // ---- Mémoire locale (SharedPreferences) ----

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun memoriser(context: Context, resultat: Resultat) {
        val horodatage = ZonedDateTime.now().format(FORMAT_DATE_HEURE)
        prefs(context).edit().putString(CLE_DERNIER_RESULTAT, "$horodatage – ${resultat.message}").apply()
    }

    fun dernierResultat(context: Context): String = prefs(context).getString(CLE_DERNIER_RESULTAT, null) ?: "aucune écriture pour l'instant"

    fun dateDerniereSeanceAutomatique(context: Context): String? = prefs(context).getString(CLE_DATE_DERNIERE_SEANCE, null)

    fun memoriserDateSeanceAutomatique(context: Context, date: LocalDate) {
        prefs(context).edit().putString(CLE_DATE_DERNIERE_SEANCE, date.toString()).apply()
    }
}

/** Armement de l'alarme quotidienne (inexacte : aucune permission d'alarme exacte requise). */
object Planificateur {
    const val ACTION_SEANCE = "com.rostat.stepsimulator.action.SEANCE"

    /** Prochain 6h35 strictement dans le futur (aujourd'hui si pas encore passé, sinon demain). */
    fun prochaineOccurrence(maintenant: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        val aujourdHui = maintenant.with(Seance.HEURE_ALARME).withSecond(0).withNano(0)
        return if (aujourdHui.isAfter(maintenant)) aujourdHui else aujourdHui.plusDays(1)
    }

    /**
     * (Ré)arme l'alarme. Le même PendingIntent (requestCode 0) est remplacé à chaque appel :
     * appeler plusieurs fois ne crée jamais de doublon.
     * setAndAllowWhileIdle : sonne même en mode "Doze" (écran éteint, téléphone posé),
     * avec un décalage possible de quelques minutes, accepté par la v2.
     */
    fun planifier(context: Context): ZonedDateTime {
        val prochaine = prochaineOccurrence()
        val gestionnaire = context.getSystemService(AlarmManager::class.java)
        val intention = Intent(context, AlarmReceiver::class.java).setAction(ACTION_SEANCE)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intention, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        gestionnaire.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prochaine.toInstant().toEpochMilli(), pendingIntent)
        Log.i(Seance.TAG, "Alarme armée pour $prochaine")
        return prochaine
    }
}

/**
 * Récepteur déclenché par :
 *  - l'alarme quotidienne (ACTION_SEANCE) : réarme pour demain, puis écrit la séance du jour ;
 *  - le redémarrage (BOOT_COMPLETED) : réarme seulement (les alarmes ne survivent pas au reboot).
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Planificateur.planifier(context)

            Planificateur.ACTION_SEANCE -> {
                Planificateur.planifier(context) // réarmer AVANT d'écrire : même en cas d'échec, demain est couvert
                // goAsync() : garde le processus (et le réveil du téléphone) vivant le temps de l'écriture,
                // l'API Health Connect étant asynchrone. finish() est obligatoire à la fin.
                val enAttente = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        ecrireSeanceDuJour(context)
                    } finally {
                        enAttente.finish()
                    }
                }
            }
        }
    }

    private suspend fun ecrireSeanceDuJour(context: Context) {
        val zone = ZoneId.systemDefault()
        val maintenant = ZonedDateTime.now(zone)
        val aujourdHui = maintenant.toLocalDate()
        val debut = aujourdHui.atTime(Seance.HEURE_DEBUT).atZone(zone)
        val fin = debut.plusMinutes(Seance.DUREE_MINUTES.toLong())

        // Garde-fou 1 : alarme délivrée très en retard, après minuit (téléphone éteint la veille) :
        // la séance "du jour" serait dans le futur, refusée par Health Connect. On attend 6h35.
        if (fin.isAfter(maintenant)) {
            Log.w(Seance.TAG, "Séance du jour pas encore terminée ($fin) : écriture reportée")
            return
        }
        // Garde-fou 2 : ne jamais écrire deux fois la même journée (double déclenchement, reboot...).
        if (Seance.dateDerniereSeanceAutomatique(context) == aujourdHui.toString()) {
            Log.i(Seance.TAG, "Séance du $aujourdHui déjà écrite : rien à faire")
            return
        }
        val resultat = Seance.ecrire(context, debut, fin, origine = "alarme")
        if (resultat.succes) Seance.memoriserDateSeanceAutomatique(context, aujourdHui)
    }
}
