package com.solplay.iptv

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Point d'entrée de l'application : programme la tâche périodique qui
 * rappelle chaque heure à l'utilisateur le temps restant sur son essai
 * gratuit ou son abonnement Pro.
 */
class SolPlayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleHourlyReminder()
    }

    private fun scheduleHourlyReminder() {
        // 1 heure = intervalle minimum supporté nativement par WorkManager
        // pour les tâches périodiques, ce qui correspond exactement au besoin.
        val request = PeriodicWorkRequestBuilder<RemainingTimeReminderWorker>(1, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RemainingTimeReminderWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
