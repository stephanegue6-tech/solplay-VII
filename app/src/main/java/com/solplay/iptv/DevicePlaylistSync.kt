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

            if (!snapshot.exists()) return

            val existing = PlaylistStore.getAll(context)

            for (child in snapshot.children) {
                val remoteId = child.key ?: continue
                val tag = "device:$remoteId"
                val active = child.child("active").getValue(Boolean::class.java) ?: true
                val alreadySaved = existing.firstOrNull { it.fromCode == tag }

                if (!active) {
                    // L'admin a retiré/désactivé cette assignation : on enlève la copie locale.
                    alreadySaved?.let { PlaylistStore.delete(context, it.id) }
                    continue
                }

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
        } catch (e: Exception) {
            // Silencieux : pas grave si hors-ligne, on retentera à la prochaine ouverture de l'écran.
        }
    }
}
