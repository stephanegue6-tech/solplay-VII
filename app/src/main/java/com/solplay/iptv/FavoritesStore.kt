package com.solplay.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gère la liste des chaînes/films/séries marqués en favori par l'utilisateur.
 * Stocké dans SharedPreferences (texte JSON), même fichier non chiffré que
 * DeviceKeyManager (les favoris ne sont pas sensibles).
 *
 * Un favori est identifié par son streamUrl (URL unique par entrée côté panel).
 */
object FavoritesStore {

    private const val PREFS = "solplay_prefs"
    private const val KEY = "favorites_v1"

    fun getAll(context: Context): List<Channel> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Channel(
                    name      = o.getString("name"),
                    logoUrl   = o.optString("logoUrl").ifEmpty { null },
                    groupTitle = o.optString("groupTitle").ifEmpty { null },
                    streamUrl = o.getString("streamUrl")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun isFavorite(context: Context, streamUrl: String): Boolean =
        getAll(context).any { it.streamUrl == streamUrl }

    fun toggle(context: Context, channel: Channel): Boolean {
        val list = getAll(context).toMutableList()
        val existing = list.indexOfFirst { it.streamUrl == channel.streamUrl }
        val nowFav: Boolean
        if (existing >= 0) {
            list.removeAt(existing)
            nowFav = false
        } else {
            list.add(0, channel) // ajout en tête pour visibilité immédiate
            nowFav = true
        }
        persist(context, list)
        return nowFav
    }

    private fun persist(context: Context, list: List<Channel>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("name",       c.name)
                put("logoUrl",    c.logoUrl ?: "")
                put("groupTitle", c.groupTitle ?: "")
                put("streamUrl",  c.streamUrl)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
