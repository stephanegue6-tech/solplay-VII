package com.solplay.iptv

import android.content.Context
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gère l'essai gratuit de 24 heures et l'activation de la licence Pro
 * (avec date d'expiration).
 *
 * L'activation Pro fonctionne via Firebase Realtime Database :
 * 1. L'app génère une "clé appareil" unique (voir DeviceKeyManager).
 * 2. Le client envoie cette clé à l'administrateur (email/WhatsApp).
 * 3. L'administrateur active cette clé depuis le panneau admin (admin_panel.html)
 *    en choisissant une durée (test en heures, ou abonnement en mois).
 * 4. L'app vérifie en ligne le statut de cette clé, mémorise la date
 *    d'expiration localement et fonctionne ensuite hors-ligne jusqu'à expiration.
 */
object TrialManager {

    private const val PREFS = "solplay_prefs"
    private const val KEY_FIRST_LAUNCH = "first_launch_time"
    private const val KEY_LICENSED = "is_licensed"
    private const val KEY_LICENSE_EXPIRES_AT = "license_expires_at"
    private const val KEY_LICENSE_PLAN_LABEL = "license_plan_label"

    /** Essai gratuit : 24 heures (au lieu des 30 jours précédents). */
    private const val TRIAL_HOURS = 24L
    private const val MILLIS_PER_HOUR = 1000L * 60 * 60
    private const val TRIAL_MILLIS = TRIAL_HOURS * MILLIS_PER_HOUR

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Doit être appelé une fois au démarrage de l'app (ex: SplashActivity). */
    fun ensureFirstLaunchRecorded(context: Context) {
        val p = prefs(context)
        if (p.getLong(KEY_FIRST_LAUNCH, 0L) == 0L) {
            p.edit().putLong(KEY_FIRST_LAUNCH, System.currentTimeMillis()).apply()
        }
    }

    // ---------------------------------------------------------------------
    // Essai gratuit (24h)
    // ---------------------------------------------------------------------

    /** Millisecondes restantes dans l'essai gratuit (0 si terminé). */
    fun getRemainingTrialMillis(context: Context): Long {
        val first = prefs(context).getLong(KEY_FIRST_LAUNCH, System.currentTimeMillis())
        val elapsed = System.currentTimeMillis() - first
        return (TRIAL_MILLIS - elapsed).coerceAtLeast(0)
    }

    fun isTrialActive(context: Context): Boolean = getRemainingTrialMillis(context) > 0

    // ---------------------------------------------------------------------
    // Licence payante (avec expiration)
    // ---------------------------------------------------------------------

    /** true si une licence est active ET pas encore expirée. */
    fun isLicensed(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_LICENSED, false)) return false
        val expiresAt = p.getLong(KEY_LICENSE_EXPIRES_AT, 0L)
        // expiresAt == 0L est traité comme "sans expiration" (compatibilité/illimité)
        if (expiresAt == 0L) return true
        return System.currentTimeMillis() < expiresAt
    }

    fun getLicenseExpiresAt(context: Context): Long = prefs(context).getLong(KEY_LICENSE_EXPIRES_AT, 0L)

    fun getLicensePlanLabel(context: Context): String? = prefs(context).getString(KEY_LICENSE_PLAN_LABEL, null)

    /** Millisecondes restantes sur la licence payante (0 si expirée ou sans licence). */
    fun getRemainingLicenseMillis(context: Context): Long {
        val expiresAt = getLicenseExpiresAt(context)
        if (expiresAt == 0L) return Long.MAX_VALUE // licence sans expiration
        return (expiresAt - System.currentTimeMillis()).coerceAtLeast(0)
    }

    /** L'utilisateur peut utiliser l'app s'il est licencié (et pas expiré) OU encore dans l'essai. */
    fun canAccessApp(context: Context): Boolean = isLicensed(context) || isTrialActive(context)

    /**
     * Vérifie en ligne (Firebase) si la clé de cet appareil a été activée par
     * l'administrateur, et récupère sa date d'expiration éventuelle.
     * Nécessite une connexion internet.
     * Retourne true si activée ET non expirée (et mémorise le statut
     * localement pour un accès hors-ligne par la suite), false sinon.
     */
    suspend fun checkOnlineLicense(context: Context): Boolean {
        val deviceKey = DeviceKeyManager.getDeviceKey(context)
        return try {
            val ref = FirebaseDatabase.getInstance()
                .getReference("licenses")
                .child(deviceKey)
            val snapshot = ref.get().await()
            val active = snapshot.child("active").getValue(Boolean::class.java) ?: false
            val expiresAt = snapshot.child("expiresAt").getValue(Long::class.java) ?: 0L
            val planLabel = snapshot.child("planLabel").getValue(String::class.java)

            val stillValid = active && (expiresAt == 0L || System.currentTimeMillis() < expiresAt)

            val p = prefs(context)
            p.edit()
                .putBoolean(KEY_LICENSED, active)
                .putLong(KEY_LICENSE_EXPIRES_AT, expiresAt)
                .putString(KEY_LICENSE_PLAN_LABEL, planLabel)
                .apply()

            stillValid
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------------
    // Formatage de durée / dates pour l'affichage
    // ---------------------------------------------------------------------

    /** Formate une durée en millisecondes en "Xj Xh Xmin" (ou "Xh Xmin" / "Xmin"). */
    fun formatDuration(millis: Long): String {
        if (millis <= 0) return "0 min"
        if (millis == Long.MAX_VALUE) return "illimité"
        val totalMinutes = millis / 60000
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes % (60 * 24)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "${days}j ${hours}h ${minutes}min"
            hours > 0 -> "${hours}h ${minutes}min"
            else -> "${minutes}min"
        }
    }

    fun formatDate(millis: Long): String {
        if (millis == 0L) return "-"
        val sdf = SimpleDateFormat("dd/MM/yyyy 'à' HH:mm", Locale.FRENCH)
        return sdf.format(Date(millis))
    }
}
