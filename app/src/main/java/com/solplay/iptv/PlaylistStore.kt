package com.solplay.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gère la liste des playlists enregistrées localement (SharedPreferences,
 * au format JSON) : ajout, modification, suppression, et suivi de la
 * playlist actuellement "connectée".
 */
object PlaylistStore {

    private const val PREFS = "solplay_prefs"
    private const val KEY_PLAYLISTS = "saved_playlists"
    private const val KEY_ACTIVE_ID = "active_playlist_id"

    fun getAll(context: Context): List<SavedPlaylist> {
        val raw = prefs(context).getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i -> fromJson(array.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Ajoute la playlist, ou la remplace si une playlist avec le même id existe déjà. */
    fun save(context: Context, playlist: SavedPlaylist) {
        val list = getAll(context).toMutableList()
        val index = list.indexOfFirst { it.id == playlist.id }
        if (index >= 0) list[index] = playlist else list.add(playlist)
        persist(context, list)
    }

    fun delete(context: Context, id: String) {
        val list = getAll(context).filterNot { it.id == id }
        persist(context, list)
        if (getActiveId(context) == id) setActiveId(context, null)
    }

    fun getActiveId(context: Context): String? = prefs(context).getString(KEY_ACTIVE_ID, null)

    fun setActiveId(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    private fun persist(context: Context, list: List<SavedPlaylist>) {
        val array = JSONArray()
        list.forEach { array.put(toJson(it)) }
        prefs(context).edit().putString(KEY_PLAYLISTS, array.toString()).apply()
    }

    private fun toJson(p: SavedPlaylist): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("mode", p.mode.name)
        put("m3uUrl", p.m3uUrl)
        put("xtreamServer", p.xtreamServer)
        put("xtreamUsername", p.xtreamUsername)
        put("xtreamPassword", p.xtreamPassword)
        put("fromCode", p.fromCode ?: "")
    }

    private fun fromJson(o: JSONObject): SavedPlaylist = SavedPlaylist(
        id = o.getString("id"),
        name = o.getString("name"),
        mode = PlaylistMode.valueOf(o.getString("mode")),
        m3uUrl = o.optString("m3uUrl", ""),
        xtreamServer = o.optString("xtreamServer", ""),
        xtreamUsername = o.optString("xtreamUsername", ""),
        xtreamPassword = o.optString("xtreamPassword", ""),
        fromCode = o.optString("fromCode", "").ifEmpty { null }
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
