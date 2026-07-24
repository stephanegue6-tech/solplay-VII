package com.solplay.iptv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recharge la liste complète des chaînes/films/séries directement depuis le
 * fournisseur (Xtream API ou M3U), en ignorant tout cache existant, puis met
 * à jour [ChannelRepository] et [ChannelCacheStore] en conséquence.
 *
 * Centralise une logique auparavant dupliquée dans HomeActivity (rafraîchissement
 * silencieux si le cache dépasse 30 min) et maintenant aussi utilisée par
 * PlayerActivity pour retenter automatiquement la lecture d'une chaîne en échec
 * (voir handlePlaybackError) : certains panels Xtream réattribuent leurs
 * stream_id avec le temps, ce qui invalide les liens en cache sans que les
 * identifiants du compte ne changent pour autant. Forcer un rechargement complet
 * corrige ce cas sans intervention de l'admin.
 */
object ChannelRefresher {

    suspend fun refresh(context: Context, playlist: SavedPlaylist): List<Channel>? = withContext(Dispatchers.IO) {
        try {
            val channels = if (playlist.extractXtreamCredentials() != null) {
                XtreamApiClient.fetchAllChannelsDirect(playlist).channels
            } else {
                val parsed = M3uParser.fetchAndParse(playlist.buildUrl())
                XtreamApiClient.enrichChannelsWithCategories(playlist, parsed)
            }
            if (channels.isNotEmpty()) {
                ChannelRepository.setChannels(channels)
                ChannelCacheStore.save(context, playlist.id, channels)
            }
            channels
        } catch (e: Exception) {
            null
        }
    }
}
