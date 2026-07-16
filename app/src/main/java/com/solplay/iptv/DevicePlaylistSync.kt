package com.solplay.iptv

import android.content.Context
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Synchronise les playlists que l'administrateur a assignées DIRECTEMENT à la
 * clé appareil de cet utilisateur, depuis le panneau admin (nœud Firebase
 * "device_playlists/{deviceKey}/{id}").
 *
 * Contrairement à CodeRedeemer (où le client doit lui-même saisir un code),
 * ce mécanisme ne demande AUCUNE action au client : dès que l'admin assigne
 * une playlist à sa clé appareil, elle apparaît automatiquement dans "Mes
 * playlists" la prochaine fois que l'app se connecte à internet.
 */
object DevicePlaylistSync {

    suspend fun sync(context: Context) {
        val deviceKey = DeviceKeyManager.getDeviceKey(context)
        try {
            val snapshot = FirebaseDatabase.getInstance()
                .getReference("device_playlists")
                .child(deviceKey)
                .get()
                .await()

            val existing = PlaylistStore.getAll(context)

            // Tags ("device:remoteId") des assignations encore actives et non
            // expirées après ce passage - sert à nettoyer en fin de fonction
            // toute playlist locale "device:*" qui ne s'y retrouve plus, y
            // compris quand l'admin a carrément SUPPRIMÉ l'assignation côté
            // Firebase (elle n'apparaît alors plus du tout dans
            // snapshot.children, donc jamais vue par la boucle ci-dessous -
            // sans ce nettoyage final, elle restait affichée indéfiniment
            // côté app tant que l'utilisateur ne la supprimait pas lui-même).
            val stillValidTags = mutableSetOf<String>()

            if (snapshot.exists()) {
                for (child in snapshot.children) {
                    val remoteId = child.key ?: continue
                    val tag = "device:$remoteId"
                    val active = child.child("active").getValue(Boolean::class.java) ?: true
                    val expiresAt = child.child("expiresAt").getValue(Long::class.java) ?: 0L
                    // Même correction d'horloge que TrialManager (offset serveur Firebase),
                    // pour éviter qu'un simple changement de date locale sur l'appareil
                    // ne prolonge artificiellement une assignation expirée.
                    val trustedNow = System.currentTimeMillis() + TrialManager.getServerTimeOffsetMillis(context)
                    val expired = expiresAt > 0L && trustedNow >= expiresAt
                    val alreadySaved = existing.firstOrNull { it.fromCode == tag }

                    if (!active || expired) {
                        // L'admin a désactivé cette assignation, ou sa durée est
                        // écoulée : on enlève la copie locale.
                        alreadySaved?.let { PlaylistStore.delete(context, it.id) }
                        continue
                    }

                    stillValidTags += tag

                    val type = child.child("type").getValue(String::class.java) ?: "m3u"
                    val name = child.child("name").getValue(String::class.java)
                        ?.takeIf { it.isNotBlank() } ?: "Playlist"

                    val playlist = if (type == "xtream") {
                        SavedPlaylist(
                            id = alreadySaved?.id ?: UUID.randomUUID().toString(),
                            name = name,
                            mode = PlaylistMode.XTREAM,
                            xtreamServer = child.child("xtreamServer").getValue(String::class.java) ?: "",
                            xtreamUsername = child.child("xtreamUsername").getValue(String::class.java) ?: "",
                            xtreamPassword = child.child("xtreamPassword").getValue(String::class.java) ?: "",
                            fromCode = tag
                        )
                    } else {
                        SavedPlaylist(
                            id = alreadySaved?.id ?: UUID.randomUUID().toString(),
                            name = name,
                            mode = PlaylistMode.M3U,
                            m3uUrl = child.child("m3uUrl").getValue(String::class.java) ?: "",
                            fromCode = tag
                        )
                    }
                    PlaylistStore.save(context, playlist)
                }
            }

            // Nettoyage final : toute playlist locale marquée "device:*" dont le
            // tag n'est plus dans stillValidTags a été retirée/supprimée côté
            // admin (partiellement ou entièrement) - on la retire ici, qu'elle
            // ait été vue désactivée dans la boucle ci-dessus OU carrément
            // absente de snapshot.children (assignation supprimée) OU que
            // snapshot n'existe plus du tout (toutes les assignations supprimées).
            existing
                .filter { it.fromCode?.startsWith("device:") == true && it.fromCode !in stillValidTags }
                .forEach { PlaylistStore.delete(context, it.id) }
        } catch (e: Exception) {
            // Silencieux : pas grave si hors-ligne, on retentera à la prochaine ouverture de l'écran.
        }
    }
}
