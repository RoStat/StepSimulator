package com.rostat.stepsimulator

import android.content.Context
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * Réglages de l'application (v3), persistés dans les SharedPreferences.
 * Toute valeur absente ou corrompue retombe sur la valeur par défaut, qui reproduit la v2
 * (séance 6h00-6h35, environ 6 500 pas).
 */
data class Parametres(
    // ---- séance quotidienne automatique ----
    val seanceActive: Boolean = true,
    val heureDebut: LocalTime = LocalTime.of(6, 0),
    val dureeMinutes: Int = 35,
    val objectifValeur: Double = 6500.0,   // en pas, ou en km si objectifEnKm
    val objectifEnKm: Boolean = false,
    val variationPourcent: Int = 5,        // le total quotidien varie de ± ce pourcentage
    // ---- réglages communs ----
    val fouleeCm: Int = 72,                // longueur d'un pas, pour convertir pas <-> km
    val saisieManuelle: Boolean = false,   // méthode déclarée à Health Connect (voir Seance.metadonnees)
    val ecrireDistance: Boolean = false,   // écrire aussi la distance (DistanceRecord) à côté des pas
    // ---- séance manuelle (mémorisée pour ne pas la ressaisir) ----
    val manuelValeur: Double = 5.0,
    val manuelEnKm: Boolean = true,
    val manuelDureeMinutes: Int = 50,
) {
    /** Heure de fin de la séance quotidienne = heure de l'alarme. Peut passer minuit (ex. 23h50 + 35 min). */
    val heureFin: LocalTime get() = heureDebut.plusMinutes(dureeMinutes.toLong())

    fun objectifPas(): Int = pasDepuis(objectifValeur, objectifEnKm, fouleeCm)
    fun manuelPas(): Int = pasDepuis(manuelValeur, manuelEnKm, fouleeCm)
    fun kmDepuisPas(pas: Int): Double = kmDepuisPas(pas, fouleeCm)

    /** Contrôle des bornes : renvoie le message d'erreur, ou null si tout est valide. */
    fun erreur(): String? = when {
        dureeMinutes !in 1..720 -> "Durée quotidienne : entre 1 et 720 minutes"
        manuelDureeMinutes !in 1..720 -> "Durée manuelle : entre 1 et 720 minutes"
        fouleeCm !in 30..150 -> "Foulée : entre 30 et 150 cm"
        variationPourcent !in 0..30 -> "Variation : entre 0 et 30 %"
        objectifPas() !in 1..200_000 -> "Objectif quotidien : entre 1 et 200 000 pas"
        manuelPas() !in 1..200_000 -> "Séance manuelle : entre 1 et 200 000 pas"
        objectifPas() < dureeMinutes -> "Objectif quotidien : au moins 1 pas par minute"
        manuelPas() < manuelDureeMinutes -> "Séance manuelle : au moins 1 pas par minute"
        else -> null
    }

    fun sauvegarder(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("seance_active", seanceActive)
            .putInt("heure_debut_minutes", heureDebut.toSecondOfDay() / 60)
            .putInt("duree_minutes", dureeMinutes)
            .putFloat("objectif_valeur", objectifValeur.toFloat())
            .putBoolean("objectif_en_km", objectifEnKm)
            .putInt("variation_pourcent", variationPourcent)
            .putInt("foulee_cm", fouleeCm)
            .putBoolean("saisie_manuelle", saisieManuelle)
            .putBoolean("ecrire_distance", ecrireDistance)
            .putFloat("manuel_valeur", manuelValeur.toFloat())
            .putBoolean("manuel_en_km", manuelEnKm)
            .putInt("manuel_duree_minutes", manuelDureeMinutes)
            .apply()
    }

    companion object {
        private const val PREFS = "stepsimulator_parametres"

        fun charger(context: Context): Parametres {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val defaut = Parametres()
            val debutMinutes = p.getInt("heure_debut_minutes", defaut.heureDebut.toSecondOfDay() / 60).coerceIn(0, 24 * 60 - 1)
            return Parametres(
                seanceActive = p.getBoolean("seance_active", defaut.seanceActive),
                heureDebut = LocalTime.ofSecondOfDay(debutMinutes * 60L),
                dureeMinutes = p.getInt("duree_minutes", defaut.dureeMinutes),
                objectifValeur = p.getFloat("objectif_valeur", defaut.objectifValeur.toFloat()).toDouble(),
                objectifEnKm = p.getBoolean("objectif_en_km", defaut.objectifEnKm),
                variationPourcent = p.getInt("variation_pourcent", defaut.variationPourcent),
                fouleeCm = p.getInt("foulee_cm", defaut.fouleeCm),
                saisieManuelle = p.getBoolean("saisie_manuelle", defaut.saisieManuelle),
                ecrireDistance = p.getBoolean("ecrire_distance", defaut.ecrireDistance),
                manuelValeur = p.getFloat("manuel_valeur", defaut.manuelValeur.toFloat()).toDouble(),
                manuelEnKm = p.getBoolean("manuel_en_km", defaut.manuelEnKm),
                manuelDureeMinutes = p.getInt("manuel_duree_minutes", defaut.manuelDureeMinutes),
            )
        }

        /** Convertit une valeur saisie (pas ou km) en nombre de pas. */
        fun pasDepuis(valeur: Double, enKm: Boolean, fouleeCm: Int): Int =
            if (enKm) pasDepuisKm(valeur, fouleeCm) else valeur.roundToInt()

        fun pasDepuisKm(km: Double, fouleeCm: Int): Int = (km * 100_000.0 / fouleeCm).roundToInt()

        fun kmDepuisPas(pas: Int, fouleeCm: Int): Double = pas * fouleeCm / 100_000.0
    }
}
