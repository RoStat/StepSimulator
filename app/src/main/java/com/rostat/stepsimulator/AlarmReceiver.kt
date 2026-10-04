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
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
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
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/*
 * Logique "métier" de la v3, en trois blocs :
 *   1. Seance        : construction et écriture des enregistrements (pas, et distance en option)
 *                      dans Health Connect, minute par minute ; effacement ; notification ; mémoire.
 *   2. Planificateur : alarme quotidienne à l'heure de fin de la séance paramétrée.
 *   3. AlarmReceiver : point d'entrée de l'alarme (écriture de la séance du jour) et du redémarrage.
 *
 * Principe inchangé depuis la v2 : rien ne tourne pendant la séance. L'alarme réveille l'app
 * quelques secondes à la fin de la séance, qui écrit d'un coup une séance déjà passée.
 * Tous les réglages (heure, durée, objectif en pas ou en km...) viennent de Parametres.
 */

/** Opérations Health Connect et utilitaires de séance. */
object Seance {
    const val TAG = "StepSimulator"

    /** Permissions Health Connect : "android.permission.health.WRITE_STEPS" et "...WRITE_DISTANCE". */
    val PERMISSION_ECRITURE_PAS: String = HealthPermission.getWritePermission(StepsRecord::class)
    val PERMISSION_ECRITURE_DISTANCE: String = HealthPermission.getWritePermission(DistanceRecord::class)

    const val CANAL_NOTIF = "seances"
    private const val PREFS = "stepsimulator"
    private const val CLE_DATE_DERNIERE_SEANCE = "date_derniere_seance"
    private const val CLE_DERNIER_RESULTAT = "dernier_resultat"
    private val FORMAT_HEURE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)
    private val FORMAT_DATE_HEURE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.FRENCH)

    /** Compte rendu d'une opération, affiché à l'écran, notifié et mémorisé. */
    data class Resultat(val succes: Boolean, val message: String)

    fun formatPas(pas: Int): String = String.format(Locale.FRENCH, "%,d", pas)
    fun formatKm(km: Double): String = String.format(Locale.FRENCH, "%.1f", km)

    /**
     * Métadonnées attachées à chaque enregistrement.
     * Réglage "Déclarer comme saisie manuelle" désactivé (défaut) : "enregistré automatiquement par
     * le téléphone", car certaines applications de challenge ignorent les saisies manuelles.
     * Activé : "saisie manuelle", le marquage transparent.
     */
    private fun metadonnees(parametres: Parametres): Metadata =
        if (parametres.saisieManuelle) Metadata.manualEntry()
        else Metadata.autoRecorded(Device(type = Device.TYPE_PHONE, manufacturer = Build.MANUFACTURER, model = Build.MODEL))

    /**
     * Répartit [total] pas sur [minutes] enregistrements avec un léger aléa (±15 %) pour éviter un
     * rythme parfaitement régulier. Chaque minute compte au moins 1 pas (exigence Health Connect),
     * la dernière minute absorbe l'arrondi : la somme vaut exactement [total].
     */
    fun repartirPas(total: Int, minutes: Int, alea: Random = Random.Default): List<Long> {
        require(minutes in 1..total) { "Il faut au moins 1 pas par minute" }
        val poids = List(minutes) { 0.85 + alea.nextDouble() * 0.30 }
        val sommePoids = poids.sum()
        val repartition = poids.map { (it / sommePoids * total).roundToLong().coerceAtLeast(1L) }.toMutableList()
        repartition[repartition.lastIndex] = (total - repartition.dropLast(1).sum()).coerceAtLeast(1L)
        return repartition
    }

    /**
     * Écrit une séance [debut, fin[ de [totalPas] pas dans Health Connect : un StepsRecord par minute,
     * plus un DistanceRecord par minute si le réglage "écrire la distance" est actif et autorisé.
     * Appelée par l'alarme (séance quotidienne) et par l'écran (séance manuelle).
     * Ne lève jamais d'exception : le résultat est renvoyé, notifié et mémorisé.
     */
    suspend fun ecrire(
        context: Context,
        debut: ZonedDateTime,
        fin: ZonedDateTime,
        totalPas: Int,
        parametres: Parametres,
        origine: String,
    ): Resultat {
        val minutes = Duration.between(debut, fin).toMinutes().toInt()
        val resultat = try {
            when {
                minutes < 1 -> Resultat(false, "Durée invalide")
                totalPas < minutes -> Resultat(false, "Trop peu de pas pour la durée : au moins 1 pas par minute")
                fin.isAfter(ZonedDateTime.now()) -> Resultat(false, "La séance se terminerait dans le futur : Health Connect refuse")
                HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE ->
                    Resultat(false, "Health Connect indisponible sur ce téléphone")

                else -> {
                    val client = HealthConnectClient.getOrCreate(context)
                    val accordees = client.permissionController.getGrantedPermissions()
                    if (PERMISSION_ECRITURE_PAS !in accordees) {
                        Resultat(false, "Permission d'écriture des pas non accordée : ouvrez l'app et autorisez Health Connect")
                    } else {
                        val avecDistance = parametres.ecrireDistance && PERMISSION_ECRITURE_DISTANCE in accordees
                        val enregistrements = mutableListOf<Record>()
                        repartirPas(totalPas, minutes).forEachIndexed { i, pas ->
                            val debutMinute = debut.plusMinutes(i.toLong())
                            val finMinute = debutMinute.plusMinutes(1)
                            enregistrements += StepsRecord(
                                startTime = debutMinute.toInstant(),
                                startZoneOffset = debutMinute.offset,
                                endTime = finMinute.toInstant(),
                                endZoneOffset = finMinute.offset,
                                count = pas,
                                metadata = metadonnees(parametres)
                            )
                            if (avecDistance) enregistrements += DistanceRecord(
                                startTime = debutMinute.toInstant(),
                                startZoneOffset = debutMinute.offset,
                                endTime = finMinute.toInstant(),
                                endZoneOffset = finMinute.offset,
                                distance = Length.meters(pas * parametres.fouleeCm / 100.0),
                                metadata = metadonnees(parametres)
                            )
                        }
                        client.insertRecords(enregistrements)
                        val contenu = when {
                            avecDistance -> "pas + distance"
                            parametres.ecrireDistance -> "pas seuls, permission distance manquante"
                            else -> "pas"
                        }
                        Resultat(
                            true,
                            "${formatPas(totalPas)} pas (${formatKm(parametres.kmDepuisPas(totalPas))} km) écrits de " +
                                "${debut.format(FORMAT_DATE_HEURE)} à ${fin.format(FORMAT_HEURE)} · $origine · $contenu"
                        )
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
     * Efface les pas et distances écrits par CETTE application au cours des [heures] dernières heures.
     * Health Connect n'autorise une app à supprimer que ses propres données.
     */
    suspend fun effacer(context: Context, heures: Long = 24): Resultat {
        val resultat = try {
            val client = HealthConnectClient.getOrCreate(context)
            val maintenant = Instant.now()
            val plage = TimeRangeFilter.between(maintenant.minus(Duration.ofHours(heures)), maintenant)
            client.deleteRecords(StepsRecord::class, plage)
            client.deleteRecords(DistanceRecord::class, plage)
            Resultat(true, "Données écrites par l'app effacées (dernières $heures h)")
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

    fun dernierResultat(context: Context): String =
        prefs(context).getString(CLE_DERNIER_RESULTAT, null) ?: "aucune écriture pour l'instant"

    fun dateDerniereSeanceAutomatique(context: Context): String? = prefs(context).getString(CLE_DATE_DERNIERE_SEANCE, null)

    fun memoriserDateSeanceAutomatique(context: Context, date: LocalDate) {
        prefs(context).edit().putString(CLE_DATE_DERNIERE_SEANCE, date.toString()).apply()
    }
}

/** Armement de l'alarme quotidienne (inexacte : aucune permission d'alarme exacte requise). */
object Planificateur {
    const val ACTION_SEANCE = "com.rostat.stepsimulator.action.SEANCE"

    /** Prochaine occurrence de [heure] strictement dans le futur (aujourd'hui si pas encore passée, sinon demain). */
    fun prochaineOccurrence(heure: LocalTime, maintenant: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        val aujourdHui = maintenant.with(heure).withSecond(0).withNano(0)
        return if (aujourdHui.isAfter(maintenant)) aujourdHui else aujourdHui.plusDays(1)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, AlarmReceiver::class.java).setAction(ACTION_SEANCE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * (Ré)arme l'alarme à l'heure de fin de la séance paramétrée, ou l'annule si la séance
     * quotidienne est désactivée. Renvoie la prochaine occurrence, ou null si désactivée.
     * Le même PendingIntent (requestCode 0) est remplacé à chaque appel : jamais de doublon.
     * setAndAllowWhileIdle : sonne même en veille profonde (Doze), avec un décalage possible
     * de quelques minutes, accepté par conception.
     */
    fun planifier(context: Context, parametres: Parametres = Parametres.charger(context)): ZonedDateTime? {
        val gestionnaire = context.getSystemService(AlarmManager::class.java)
        if (!parametres.seanceActive) {
            gestionnaire.cancel(pendingIntent(context))
            Log.i(Seance.TAG, "Séance quotidienne désactivée : alarme annulée")
            return null
        }
        val prochaine = prochaineOccurrence(parametres.heureFin)
        gestionnaire.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prochaine.toInstant().toEpochMilli(), pendingIntent(context))
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
                val parametres = Parametres.charger(context)
                Planificateur.planifier(context, parametres) // réarmer AVANT d'écrire : demain est couvert même en cas d'échec
                if (!parametres.seanceActive) return
                // goAsync() : garde le processus (et le réveil du téléphone) vivant le temps de l'écriture,
                // l'API Health Connect étant asynchrone. finish() est obligatoire à la fin.
                val enAttente = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        ecrireSeanceDuJour(context, parametres)
                    } finally {
                        enAttente.finish()
                    }
                }
            }
        }
    }

    private suspend fun ecrireSeanceDuJour(context: Context, parametres: Parametres) {
        val zone = ZoneId.systemDefault()
        val maintenant = ZonedDateTime.now(zone)
        val aujourdHui = maintenant.toLocalDate()
        // La séance du jour est celle qui se termine aujourd'hui à l'heure de fin paramétrée ;
        // son début peut être la veille si elle passe minuit.
        val fin = aujourdHui.atTime(parametres.heureFin).atZone(zone)
        val debut = fin.minusMinutes(parametres.dureeMinutes.toLong())

        // Garde-fou 1 : alarme délivrée très en retard, après minuit (téléphone éteint la veille) :
        // la séance "du jour" serait dans le futur, refusée par Health Connect. On attend la prochaine.
        if (fin.isAfter(maintenant)) {
            Log.w(Seance.TAG, "Séance du jour pas encore terminée ($fin) : écriture reportée")
            return
        }
        // Garde-fou 2 : ne jamais écrire deux fois la même journée (double déclenchement, reboot...).
        if (Seance.dateDerniereSeanceAutomatique(context) == aujourdHui.toString()) {
            Log.i(Seance.TAG, "Séance du $aujourdHui déjà écrite : rien à faire")
            return
        }
        // Total du jour : objectif ± variation aléatoire, pour ne pas écrire le même total chaque jour.
        val objectif = parametres.objectifPas()
        val variation = parametres.variationPourcent / 100.0
        val total = if (variation <= 0.0) objectif
        else (objectif * (1.0 + Random.nextDouble(-variation, variation))).roundToInt().coerceAtLeast(1)

        val resultat = Seance.ecrire(context, debut, fin, total, parametres, origine = "séance quotidienne")
        if (resultat.succes) Seance.memoriserDateSeanceAutomatique(context, aujourdHui)
    }
}
