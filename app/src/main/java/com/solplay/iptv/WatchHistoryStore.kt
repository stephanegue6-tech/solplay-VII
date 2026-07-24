package com.solplay.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Historique de visionnage : mémorise les 50 derniers contenus regardés
 * (chaîne Live, film ou série) avec la durée regardée et l'horodatage.
 *
 * Utilisé par :
 *  - HomeActivity  → section "Continuer à regarder"
 *  - HistoryActivity → liste complète consultable
 *  - PlayerActivity → enregistrement automatique à chaque lecture
 */
object WatchHistoryStore {

    private const val PREFS   = "solplay_prefs"
    private const val KEY     = "watch_history_v1"
    private const val MAX     = 50

    data class HistoryEntry(
        val streamUrl   : String,
        val name        : String,
        val logoUrl     : String?,
        val groupTitle  : String?,
        val contentType : String,   // "LIVE", "MOVIE", "SERIES"
        val watchedAt   : Long,     // timestamp ms
        val positionMs  : Long      // 0 pour Live
    )

    fun getAll(context: Context): List<HistoryEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                HistoryEntry(
                    streamUrl   = o.getString("streamUrl"),
                    name        = o.getString("name"),
                    logoUrl     = o.optString("logoUrl").ifEmpty { null },
                    groupTitle  = o.optString("groupTitle").ifEmpty { null },
                    contentType = o.optString("contentType", "LIVE"),
                    watchedAt   = o.optLong("watchedAt", 0L),
                    positionMs  = o.optLong("positionMs", 0L)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /**
     * Ajoute ou met à jour une entrée. Si l'URL est déjà présente, on met à
     * jour la position et l'horodatage plutôt que d'ajouter un doublon.
     */
    fun record(context: Context, channel: Channel, positionMs: Long) {
        val list = getAll(context).toMutableList()
        val type = channel.contentType().name
        val existing = list.indexOfFirst { it.streamUrl == channel.streamUrl }
        val entry = HistoryEntry(
            streamUrl   = channel.streamUrl,
            name        = channel.name,
            logoUrl     = channel.logoUrl,
            groupTitle  = channel.groupTitle,
            contentType = type,
            watchedAt   = System.currentTimeMillis(),
            positionMs  = if (channel.contentType() == ContentType.LIVE) 0L else positionMs
        )
        if (existing >= 0) list.removeAt(existing)
        list.add(0, entry)
        persist(context, list.take(MAX))
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    private fun persist(context: Context, list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("streamUrl",   e.streamUrl)
                put("name",        e.name)
                put("logoUrl",     e.logoUrl ?: "")
                put("groupTitle",  e.groupTitle ?: "")
                put("contentType", e.contentType)
                put("watchedAt",   e.watchedAt)
                put("positionMs",  e.positionMs)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
