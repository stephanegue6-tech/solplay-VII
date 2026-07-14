package com.solplay.iptv

import android.content.Context
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * Gère l'essai gratuit de 30 jours et l'activation de la licence Pro.
 *
 * L'activation Pro fonctionne désormais via Firebase Realtime Database :
 * 1. L'app génère une "clé appareil" unique (voir DeviceKeyManager).
 * 2. Le client envoie cette clé à l'administrateur (email/WhatsApp).
 * 3. L'administrateur active cette clé depuis le panneau admin (admin_panel.html).
 * 4. L'app vérifie en ligne le statut de cette clé et, si activée, mémorise
 *    le statut "licencié" localement pour un fonctionnement hors-ligne ensuite.
 */
object TrialManager {

    private const val PREFS = "solplay_prefs"
    private const val KEY_FIRST_LAUNCH = "first_launch_time"
    private const val KEY_LICENSED = "is_licensed"
    private const val TRIAL_DAYS = 30L
    private const val MILLIS_PER_DAY = 1000L * 60 * 60 * 24

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Doit être appelé une fois au démarrage de l'app (ex: SplashActivity). */
    fun ensureFirstLaunchRecorded(context: Context) {
        val p = prefs(context)
        if (p.getLong(KEY_FIRST_LAUNCH, 0L) == 0L) {
            p.edit().putLong(KEY_FIRST_LAUNCH, System.currentTimeMillis()).apply()
        }
    }

    fun isLicensed(context: Context): Boolean = prefs(context).getBoolean(KEY_LICENSED, false)

    fun getRemainingTrialDays(context: Context): Long {
        val first = prefs(context).getLong(KEY_FIRST_LAUNCH, System.currentTimeMillis())
        val elapsedDays = (System.currentTimeMillis() - first) / MILLIS_PER_DAY
        return (TRIAL_DAYS - elapsedDays).coerceAtLeast(0)
    }

    fun isTrialActive(context: Context): Boolean = getRemainingTrialDays(context) > 0

    /** L'utilisateur peut utiliser l'app s'il est licencié OU encore dans l'essai. */
    fun canAccessApp(context: Context): Boolean = isLicensed(context) || isTrialActive(context)

    /**
     * Vérifie en ligne (Firebase) si la clé de cet appareil a été activée par
     * l'administrateur. Nécessite une connexion internet.
     * Retourne true si activée (et mémorise le statut localement pour un
     * accès hors-ligne par la suite), false sinon (ou en cas d'erreur réseau).
     */
    suspend fun checkOnlineLicense(context: Context): Boolean {
        val deviceKey = DeviceKeyManager.getDeviceKey(context)
        return try {
            val ref = FirebaseDatabase.getInstance()
                .getReference("licenses")
                .child(deviceKey)
                .child("active")
            val snapshot = ref.get().await()
            val active = snapshot.getValue(Boolean::class.java) ?: false
            if (active) {
                prefs(context).edit().putBoolean(KEY_LICENSED, true).apply()
            }
            active
        } catch (e: Exception) {
            false
        }
    }
}
