package com.solplay.iptv

import android.content.Context
import com.solplay.desktop.core.SecureStorage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gère la liste des playlists enregistrées localement, au format JSON.
 *
 * Différence avec la version Android : celle-ci chiffre le stockage via
 * Jetpack Security (EncryptedSharedPreferences), qui s'appuie sur l'Android
 * Keystore matériel - une API qui n'existe pas sur desktop.
 *
 * --- Correctif (audit) : chiffrement au repos via Windows DPAPI ---
 * Les champs sensibles (identifiants Xtream, et le lien M3U qui peut être un
 * lien Xtream déguisé embarquant username/password — voir
 * SavedPlaylist.detectXtreamCredentials) sont maintenant chiffrés via
 * [SecureStorage] (Windows DPAPI) avant d'être écrits dans
 * %APPDATA%\SolPlay\prefs\solplay_prefs_secure.json. Le fichier ne contient
 * donc plus aucun mot de passe/identifiant en clair, même en cas de copie du
 * fichier ou d'accès par un autre utilisateur Windows de la même machine.
 * `id`, `name`, `mode`, `xtreamServer` (une simple URL de serveur, pas un
 * secret en soi) et `fromCode` restent en clair : les chiffrer n'apporterait
 * aucune protection supplémentaire et compliquerait le débogage pour rien.
 *
 * Migration automatique : les playlists sauvegardées AVANT ce correctif sont
 * encore en clair sur le disque des utilisateurs existants. [fromJson]
 * détecte ce cas (le déchiffrement échoue proprement, voir
 * [SecureStorage.decrypt]) et retombe sur la valeur brute plutôt que de la
 * perdre — la prochaine sauvegarde ([save]) la rechiffrera automatiquement,
 * sans action requise de l'utilisateur.
 */
object PlaylistStore {

    private const val PREFS = "solplay_prefs_secure"
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
        put("m3uUrl", SecureStorage.encrypt(p.m3uUrl))
        put("xtreamServer", p.xtreamServer)
        put("xtreamUsername", SecureStorage.encrypt(p.xtreamUsername))
        put("xtreamPassword", SecureStorage.encrypt(p.xtreamPassword))
        put("fromCode", p.fromCode ?: "")
    }

    private fun fromJson(o: JSONObject): SavedPlaylist = SavedPlaylist(
        id = o.getString("id"),
        name = o.getString("name"),
        mode = PlaylistMode.valueOf(o.getString("mode")),
        m3uUrl = decryptOrLegacyPlain(o.optString("m3uUrl", "")),
        xtreamServer = o.optString("xtreamServer", ""),
        xtreamUsername = decryptOrLegacyPlain(o.optString("xtreamUsername", "")),
        xtreamPassword = decryptOrLegacyPlain(o.optString("xtreamPassword", "")),
        fromCode = o.optString("fromCode", "").ifEmpty { null }
    )

    /**
     * Déchiffre une valeur stockée par [toJson]. Si le déchiffrement échoue
     * (chaîne vide renvoyée par [SecureStorage.decrypt]) alors que la valeur
     * brute n'est pas vide, c'est très probablement une playlist enregistrée
     * avant ce correctif, encore en clair sur le disque — on la retourne
     * telle quelle plutôt que de faire disparaître les identifiants de
     * l'utilisateur. Elle sera rechiffrée à la prochaine sauvegarde.
     */
    private fun decryptOrLegacyPlain(storedValue: String): String {
        if (storedValue.isEmpty()) return ""
        val decrypted = SecureStorage.decrypt(storedValue)
        return decrypted.ifEmpty { storedValue }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
