package com.solplay.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Résultat TMDB minimal utile à l'affichage dans la liste des chaînes. */
data class TmdbInfo(
    val posterUrl: String?,
    val overview: String?,
    val year: String?
)

/**
 * Client pour l'API TMDB (The Movie Database), utilisé pour enrichir les
 * entrées "Films" et "Séries" des playlists M3U/Xtream : ces playlists
 * fournissent rarement une vraie affiche (tvg-logo est souvent absent, cassé,
 * ou juste un logo générique de la chaîne), donc on recherche le titre
 * correspondant sur TMDB pour récupérer une affiche et un résumé propres.
 *
 * Recherche par titre texte (endpoint /search) : moins précis qu'un vrai
 * matching par ID (Xtream ne fournit pas d'ID TMDB dans le nom du flux),
 * mais suffisant en pratique une fois le titre nettoyé (cf. cleanTitle()).
 */
object TmdbClient {

    private const val API_KEY = BuildConfig.TMDB_API_KEY
    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w342"

    private val client = OkHttpClient.Builder().build()

    // Cache en mémoire : évite de refaire un appel réseau pour chaque scroll
    // de la RecyclerView sur un titre déjà résolu (ou déjà su introuvable).
    private val cache = ConcurrentHashMap<String, TmdbInfo?>()

    suspend fun searchMovie(rawTitle: String): TmdbInfo? = search(rawTitle, "movie")

    suspend fun searchTv(rawTitle: String): TmdbInfo? = search(rawTitle, "tv")

    private suspend fun search(rawTitle: String, type: String): TmdbInfo? {
        if (API_KEY.isBlank()) return null

        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null

        val cacheKey = "$type:$title"
        if (cache.containsKey(cacheKey)) return cache[cacheKey]

        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(title, "UTF-8")
                val url = "$BASE_URL/search/$type?api_key=$API_KEY&language=fr-FR&query=$encoded"
                val request = Request.Builder().url(url).get().build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        cache[cacheKey] = null
                        return@withContext null
                    }
                    val body = response.body?.string().orEmpty()
                    val results = JSONObject(body).optJSONArray("results")
                    if (results == null || results.length() == 0) {
                        cache[cacheKey] = null
                        return@withContext null
                    }

                    val first = results.getJSONObject(0)
                    val posterPath = first.optString("poster_path", "")
                    val dateField = if (type == "movie") "release_date" else "first_air_date"
                    val date = first.optString(dateField, "")

                    val info = TmdbInfo(
                        posterUrl = if (posterPath.isNotBlank()) "$IMAGE_BASE_URL$posterPath" else null,
                        overview = first.optString("overview", "").ifBlank { null },
                        year = date.take(4).ifBlank { null }
                    )
                    cache[cacheKey] = info
                    info
                }
            } catch (e: IOException) {
                null // Pas de cache sur une erreur réseau : on retentera au prochain bind/scroll.
            }
        }
    }

    /**
     * Nettoie un nom de chaîne IPTV brut pour en extraire un titre exploitable
     * par la recherche TMDB.
     *
     * Exemples typiques de noms fournis par les playlists Xtream :
     *   "FR | Inception (2010) HD"        -> "Inception"
     *   "VF - The Matrix 4K VOSTFR"        -> "The Matrix"
     *   "Breaking Bad S01 E05"             -> "Breaking Bad"
     */
    fun cleanTitle(rawName: String): String {
        var t = rawName

        // Préfixes de langue/pays fréquents suivis de | ou -
        t = t.replace(Regex("^\\s*[A-Z]{2,4}\\s*[|:-]\\s*", RegexOption.IGNORE_CASE), "")

        // Numéro de saison/épisode (séries) : on coupe à partir de là, tout le
        // reste (titre) précède toujours ce marqueur dans les playlists Xtream.
        t = t.replace(Regex("\\bS\\d{1,2}\\s*E\\d{1,3}\\b", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\bSaison\\s*\\d+\\b", RegexOption.IGNORE_CASE), "")

        // Année entre parenthèses ou isolée
        t = t.replace(Regex("\\(\\d{4}\\)"), "")

        // Étiquettes qualité/langue courantes
        val tags = listOf(
            "4K", "FHD", "HD", "SD", "VF", "VFF", "VOSTFR", "VO", "MULTI",
            "HDR", "10BIT", "WEBRIP", "BLURAY"
        )
        for (tag in tags) {
            t = t.replace(Regex("\\b$tag\\b", RegexOption.IGNORE_CASE), "")
        }

        // Nettoyage des séparateurs restants et espaces multiples
        t = t.replace(Regex("[|_]"), " ")
        t = t.replace(Regex("\\s{2,}"), " ")
        t = t.trim(' ', '-', ':', '.')

        return t
    }
}
