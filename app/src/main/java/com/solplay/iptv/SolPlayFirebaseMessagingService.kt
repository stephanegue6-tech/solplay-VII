package com.solplay.iptv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Reçoit les notifications envoyées par l'admin (voir carte "📢 Notifications"
 * du panel), que ce soit à tous les clients ou à un appareil précis (ciblé
 * via son jeton, lui-même stocké par [FcmTokenSync]).
 *
 * Le panel écrit la demande dans Firebase (`notifications/{id}`), et c'est
 * une Cloud Function côté serveur (voir functions/index.js) qui l'envoie
 * réellement via FCM - impossible de le faire en toute sécurité directement
 * depuis le panel HTML, qui n'a pas accès aux identifiants serveur requis.
 *
 * Envoyé en "data message" (pas "notification message") pour garantir un
 * comportement identique et sous notre contrôle que l'app soit au premier
 * plan, en arrière-plan, ou totalement fermée - onMessageReceived est alors
 * TOUJOURS appelé, contrairement à un message de type "notification" qui
 * n'est traité par le système que lorsque l'app n'est pas au premier plan.
 */
class SolPlayFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "solplay_admin_messages"
        private var notificationCounter = 9000
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FcmTokenSync.saveToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.data["title"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.title
            ?: "SolPlay"
        val body = message.data["body"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.body
            ?: return

        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        createChannelIfNeeded()

        val openAppIntent = Intent(this, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Identifiant différent à chaque message (au lieu d'un ID fixe comme pour
        // le rappel horaire) : plusieurs notifications admin doivent pouvoir
        // s'empiler, pas s'écraser l'une l'autre.
        manager.notify(notificationCounter++, notification)
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Messages de l'administrateur",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Messages envoyés depuis le panel admin (ex: rappel d'expiration, information)."
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
