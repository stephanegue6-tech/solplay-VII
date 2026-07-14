package com.solplay.iptv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Rappelle à l'utilisateur, toutes les heures, le temps restant sur son
 * essai gratuit ou sur sa licence Pro (via une notification).
 *
 * Se déclenche automatiquement grâce à SolPlayApplication (WorkManager,
 * tâche périodique toutes les 1h). S'arrête tout seul de notifier une fois
 * que le temps est écoulé et qu'aucune licence n'est active (l'utilisateur
 * est de toute façon bloqué sur l'écran d'expiration à ce moment-là).
 */
class RemainingTimeReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "solplay_remaining_time"
        const val NOTIFICATION_ID = 4821
        const val UNIQUE_WORK_NAME = "solplay_hourly_reminder"
    }

    override suspend fun doWork(): Result {
        val context = applicationContext

        // Se resynchronise avec Firebase si possible (au cas où l'admin aurait
        // changé/renouvelé la licence depuis le dernier lancement de l'app).
        try {
            TrialManager.checkOnlineLicense(context)
        } catch (_: Exception) {
            // Pas de réseau : on continue avec les données locales connues.
        }

        val licensed = TrialManager.isLicensed(context)
        val trialActive = TrialManager.isTrialActive(context)

        if (!licensed && !trialActive) {
            // Plus rien à rappeler : l'utilisateur est bloqué sur l'écran
            // d'expiration qui l'invite déjà à contacter le revendeur.
            return Result.success()
        }

        val message = if (licensed) {
            val remaining = TrialManager.getRemainingLicenseMillis(context)
            if (remaining == Long.MAX_VALUE) {
                "Votre abonnement SolPlay Pro est actif (sans expiration)."
            } else {
                "Il vous reste ${TrialManager.formatDuration(remaining)} sur votre abonnement SolPlay Pro."
            }
        } else {
            val remaining = TrialManager.getRemainingTrialMillis(context)
            "Il vous reste ${TrialManager.formatDuration(remaining)} sur votre essai gratuit SolPlay."
        }

        showNotification(context, message)
        return Result.success()
    }

    private fun showNotification(context: Context, message: String) {
        createChannelIfNeeded(context)

        val openAppIntent = Intent(context, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("SolPlay — Temps restant")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Temps restant (essai / abonnement)",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Rappel toutes les heures du temps restant avant expiration."
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
