package com.solplay.iptv

import android.content.Context
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Tient à jour, dans Firebase, le jeton FCM (Firebase Cloud Messaging) de cet
 * appareil, sous `device_tokens/{deviceKey}`. C'est ce qui permet au panel
 * admin de cibler UN client précis pour une notification (plutôt que
 * "tous les appareils" uniquement) : il suffit de lire le jeton associé à sa
 * clé appareil pour lui envoyer un message.
 *
 * Écriture "best effort" (silencieuse en cas d'échec réseau) : ne bloque
 * jamais le démarrage de l'app, et se corrige toute seule au prochain lancement
 * ou au prochain rafraîchissement de jeton (voir SolPlayFirebaseMessagingService.onNewToken).
 */
object FcmTokenSync {

    private const val TAG = "FcmTokenSync"

    fun syncTokenIfNeeded(context: Context) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> saveToken(context, token) }
            .addOnFailureListener { e -> Log.w(TAG, "Impossible de récupérer le jeton FCM : ${e.message}") }
    }

    fun saveToken(context: Context, token: String) {
        val deviceKey = DeviceKeyManager.getDeviceKey(context)
        val payload = mapOf(
            "token" to token,
            "updatedAt" to System.currentTimeMillis()
        )
        FirebaseDatabase.getInstance().getReference("device_tokens")
            .child(deviceKey)
            .setValue(payload)
            .addOnFailureListener { e -> Log.w(TAG, "Échec enregistrement jeton FCM : ${e.message}") }
    }
}
