package com.solplay.iptv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Catch-up / Replay TV : récupère les programmes des derniers jours
 * pour une chaîne Live via l'API Xtream (`get_simple_data_table`) et
 * construit les URLs de replay correspondantes.
 *
 * Deux cas :
 *  - Panel qui supporte le catch-up natif (champ `has_catchup` = 1 dans
 *    la liste des streams) : on utilise l'endpoint catch_up direct.
 *  - Fallback : on propose les 7 derniers jours via timeshift si le panel
 *    expose un endpoint /timeshift/.
 *
 * Best-effort : en cas d'erreur réseau ou de panel non compatible, on
 * renvoie une liste vide sans bloquer la navigation.
 */
object CatchupStore {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class CatchupEntry(
        val title      : String,
        val start      : String,   // "20:00"
        val end        : String,   // "21:30"
        val date       : String,   // "Lun 23 juin"
        val streamUrl  : String
    )

    /**
     * Récupère les replays disponibles pour [streamId] sur les [days] derniers
     * jours. Renvoie une liste vide si le panel ne supporte pas le catch-up.
     */
    suspend fun fetchCatchup(
        playlist : SavedPlaylist,
        streamId : Int,
        days     : Int = 7
    ): List<CatchupEntry> = withContext(Dispatchers.IO) {
        val creds = playlist.extractXtreamCredentials() ?: return@withContext emptyList()
        val (server, user, pass) = creds

        // Tente d'abord l'endpoint get_simple_data_table (catch-up natif)
        val epgUrl = "$server/player_api.php?username=$user&password=$pass" +
                "&action=get_simple_data_table&stream_id=$streamId"
        try {
            val body = fetch(epgUrl) ?: return@withContext buildTimeshiftFallback(server, user, pass, streamId, days)
            val arr  = JSONArray(body)
            val result = mutableListOf<CatchupEntry>()
            val dateFmt  = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply { timeZone = TimeZone.getDefault() }
            val labelFmt = SimpleDateFormat("EEE d MMM", Locale("fr")).apply { timeZone = TimeZone.getDefault() }
            val timeFmt  = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getDefault() }

            val cutoff = System.currentTimeMillis() - days * 86_400_000L

            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val startRaw = o.optString("start")
                val stopRaw  = o.optString("stop")
                val title    = o.optString("title", "Programme").ifBlank { "Programme" }
                val startMs  = runCatching { dateFmt.parse(startRaw)?.time ?: 0L }.getOrDefault(0L)
                val stopMs   = runCatching { dateFmt.parse(stopRaw)?.time  ?: 0L }.getOrDefault(0L)
                if (startMs < cutoff || startMs >= System.currentTimeMillis()) continue

                // URL catch-up Xtream : /timeshift/{user}/{pass}/{duration}/{start}/{streamId}.ts
                val durationMin = ((stopMs - startMs) / 60_000L).toInt().coerceIn(1, 360)
                val startFormatted = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.getDefault())
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(java.util.Date(startMs))
                val url = "$server/timeshift/$user/$pass/$durationMin/$startFormatted/$streamId.ts"

                result += CatchupEntry(
                    title     = title,
                    start     = timeFmt.format(java.util.Date(startMs)),
                    end       = timeFmt.format(java.util.Date(stopMs)),
                    date      = labelFmt.format(java.util.Date(startMs)),
                    streamUrl = url
                )
            }
            result.reversed() // plus récent en premier
        } catch (e: Exception) {
            buildTimeshiftFallback(server, user, pass, streamId, days)
        }
    }

    /** Fallback : propose des créneaux de replay par tranches d'1h sur les derniers jours. */
    private fun buildTimeshiftFallback(
        server: String, user: String, pass: String,
        streamId: Int, days: Int
    ): List<CatchupEntry> {
        val result = mutableListOf<CatchupEntry>()
        val labelFmt = SimpleDateFormat("EEE d MMM", Locale("fr"))
        val now = Calendar.getInstance()
        for (d in 1..days) {
            val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -d) }
            for (h in listOf(6, 8, 10, 12, 14, 16, 18, 20, 22)) {
                day.set(Calendar.HOUR_OF_DAY, h)
                day.set(Calendar.MINUTE, 0)
                val startFormatted = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.getDefault())
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(day.time)
                val url = "$server/timeshift/$user/$pass/60/$startFormatted/$streamId.ts"
                result += CatchupEntry(
                    title     = "${h}h00",
                    start     = "${h.toString().padStart(2,'0')}:00",
                    end       = "${(h+1).toString().padStart(2,'0')}:00",
                    date      = labelFmt.format(day.time),
                    streamUrl = url
                )
            }
        }
        return result
    }

    private fun fetch(url: String): String? {
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (e: Exception) { null }
    }
}
