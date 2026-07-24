package com.solplay.iptv

import android.content.Context

/**
 * Mémorise la dernière chaîne/film/série regardé(e) et sa position de lecture
 * (en millisecondes), pour proposer "Reprendre" au prochain lancement.
 *
 * Position : utile pour les films/séries VOD (point précis). Pour les chaînes
 * Live le flux est toujours en direct donc on ne sauvegarde pas la position,
 * uniquement l'URL et le nom pour remettre la dernière chaîne active.
 */
object ResumeStore {

    private const val PREFS      = "solplay_prefs"
    private const val KEY_URL    = "resume_url"
    private const val KEY_NAME   = "resume_name"
    private const val KEY_POS    = "resume_pos_ms"
    private const val KEY_LOGO   = "resume_logo"
    private const val KEY_IS_LIVE = "resume_is_live"

    data class ResumeInfo(
        val streamUrl : String,
        val name      : String,
        val positionMs: Long,
        val logoUrl   : String?,
        val isLive    : Boolean
    )

    fun save(context: Context, channel: Channel, positionMs: Long) {
        val isLive = channel.contentType() == ContentType.LIVE
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL,     channel.streamUrl)
            .putString(KEY_NAME,    channel.name)
            .putLong  (KEY_POS,     if (isLive) 0L else positionMs)
            .putString(KEY_LOGO,    channel.logoUrl ?: "")
            .putBoolean(KEY_IS_LIVE, isLive)
            .apply()
    }

    fun get(context: Context): ResumeInfo? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url   = prefs.getString(KEY_URL,  null) ?: return null
        val name  = prefs.getString(KEY_NAME, null) ?: return null
        return ResumeInfo(
            streamUrl  = url,
            name       = name,
            positionMs = prefs.getLong(KEY_POS, 0L),
            logoUrl    = prefs.getString(KEY_LOGO, null).takeIf { !it.isNullOrEmpty() },
            isLive     = prefs.getBoolean(KEY_IS_LIVE, false)
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_URL).remove(KEY_NAME).remove(KEY_POS)
            .remove(KEY_LOGO).remove(KEY_IS_LIVE).apply()
    }
}
