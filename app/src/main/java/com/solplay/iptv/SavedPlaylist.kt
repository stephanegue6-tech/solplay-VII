package com.solplay.iptv

import java.io.Serializable
import java.util.UUID

/** Type de connexion d'une playlist enregistrée. */
enum class PlaylistMode { M3U, XTREAM }

/**
 * Une playlist enregistrée localement sur l'appareil : soit ajoutée
 * manuellement par l'utilisateur, soit obtenue via un code fourni par
 * l'administrateur (voir CodeRedeemer / champ fromCode).
 */
data class SavedPlaylist(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var mode: PlaylistMode,
    var m3uUrl: String = "",
    var xtreamServer: String = "",
    var xtreamUsername: String = "",
    var xtreamPassword: String = "",
    var fromCode: String? = null
) : Serializable {

    /** Construit l'URL effective de la playlist selon le mode choisi. */
    fun buildUrl(): String {
        return if (mode == PlaylistMode.XTREAM) {
            val server = xtreamServer.trim().trimEnd('/')
            "$server/get.php?username=$xtreamUsername&password=$xtreamPassword&type=m3u_plus&output=ts"
        } else {
            m3uUrl.trim()
        }
    }
}
