package com.solplay.iptv

import android.content.Context
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Envoie une demande de renouvellement d'abonnement depuis l'appareil client
 * vers Firebase Realtime Database (nœud `renewal_requests/`).
 *
 * L'administrateur voit ces demandes dans son tableau de bord admin_panel.html
 * et peut les approuver en un clic — sans avoir à saisir la clé appareil
 * manuellement, sans échange WhatsApp pour le renouvellement.
 *
 * Schéma Firebase :
 * renewal_requests/
 *   {pushId}/
 *     deviceKey     : "A1B2C3D4E5F6G7H8"
 *     customerName  : "Nom du client" (si connu)
 *     requestedPlan : "3 mois"
 *     requestedAt   : "2025-07-24T10:30:00Z"
 *     status        : "pending" | "approved" | "dismissed"
 */
object RenewalRequester {

    data class RenewalResult(val success: Boolean, val message: String)

    /**
     * Envoie une demande de renouvellement.
     *
     * @param context       Contexte Android (pour lire la clé appareil).
     * @param requestedPlan Label du plan souhaité ("1 mois", "3 mois"…).
     * @param customerName  Nom du client (optionnel, aide l'admin à identifier).
     */
    suspend fun sendRequest(
        context      : Context,
        requestedPlan: String,
        customerName : String = ""
    ): RenewalResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val deviceKey = DeviceKeyManager.getDeviceKey(context)
            val db = FirebaseDatabase.getInstance()

            // Vérifie s'il n'y a pas déjà une demande pending pour éviter les doublons.
            val existing = db.reference.child("renewal_requests")
                .orderByChild("deviceKey")
                .equalTo(deviceKey)
                .get().await()

            val hasPending = existing.children.any { it.child("status").value == "pending" }
            if (hasPending) {
                return@withContext RenewalResult(
                    false,
                    "Une demande de renouvellement est déjà en attente. Votre revendeur va la traiter prochainement."
                )
            }

            val data = mapOf(
                "deviceKey"     to deviceKey,
                "customerName"  to customerName,
                "requestedPlan" to requestedPlan,
                "requestedAt"   to java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault()
                ).format(java.util.Date()),
                "status"        to "pending"
            )

            db.reference.child("renewal_requests").push().setValue(data).await()

            RenewalResult(
                true,
                "✅ Demande envoyée ! Votre revendeur recevra une notification et renouvellera votre abonnement très prochainement."
            )
        } catch (e: Exception) {
            RenewalResult(false, "Impossible d'envoyer la demande : ${e.message}")
        }
    }
}
