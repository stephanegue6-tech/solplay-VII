package com.solplay.iptv

import java.io.IOException
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

data class Channel(
    val name: String,
    val logoUrl: String?,
    val groupTitle: String?,
    val streamUrl: String
) : Serializable

/** Exception avec un message clair destiné à être affiché directement à l'utilisateur. */
class PlaylistLoadException(message: String) : Exception(message)

object M3uParser {

    /**
     * Télécharge et parse une playlist M3U depuis une URL distante.
     * Le parsing se fait en streaming (ligne par ligne) pendant le téléchargement,
     * sans jamais charger tout le fichier en mémoire d'un coup : plus rapide et
     * plus léger pour les grosses playlists (10 000+ chaînes).
     */
    fun fetchAndParse(playlistUrl: String): List<Channel> {
        val connection = URL(playlistUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000   // 20s pour établir la connexion
        connection.readTimeout = 120000     // 120s pour le téléchargement/lecture
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10; SM-A205U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
        )

        try {
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw PlaylistLoadException(
                    "Le serveur a répondu avec une erreur (code $responseCode). " +
                        "Vérifiez le lien ou vos identifiants Xtream."
                )
            }

            return connection.inputStream.bufferedReader().use { reader ->
                parseStream(reader)
            }
        } catch (e: SocketTimeoutException) {
            throw PlaylistLoadException(
                "Le serveur met trop de temps à répondre (timeout). " +
                    "La playlist est peut-être très volumineuse ou le serveur est lent. Réessayez."
            )
        } catch (e: UnknownHostException) {
            throw PlaylistLoadException(
                "Impossible de joindre le serveur. Vérifiez le lien saisi et votre connexion internet."
            )
        } catch (e: PlaylistLoadException) {
            throw e
        } catch (e: IOException) {
            throw PlaylistLoadException(
                "Erreur réseau pendant le chargement : ${e.message ?: "connexion interrompue"}."
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Parse le contenu texte brut d'un fichier M3U/M3U8 déjà en mémoire (ex. tests). */
    fun parse(content: String): List<Channel> = parseStream(content.reader().buffered())

    /** Parse une playlist M3U/M3U8 en streaming, ligne par ligne. */
    private fun parseStream(reader: java.io.BufferedReader): List<Channel> {
        val channels = mutableListOf<Channel>()
        var currentName = ""
        var currentLogo: String? = null
        var currentGroup: String? = null

        reader.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    currentName = line.substringAfterLast(",").trim().ifEmpty { "Chaîne inconnue" }
                    currentLogo = Regex("tvg-logo=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                    currentGroup = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    channels.add(Channel(currentName, currentLogo, currentGroup, line))
                    currentName = ""
                    currentLogo = null
                    currentGroup = null
                }
            }
        }
        return channels
    }
}
