package com.solplay.iptv

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.solplay.iptv.databinding.ActivityHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran d'accueil affiché juste après le chargement d'une playlist : un menu
 * en grille (Live TV / Films / Séries / Compte / Changer serveur / Réglages)
 * plutôt que d'atterrir directement sur la liste brute des chaînes.
 */
class HomeActivity : AppCompatActivity() {

    companion object {
        /** Au-delà de cette ancienneté, le cache est rafraîchi en arrière-plan à l'ouverture. */
        private const val CACHE_REFRESH_THRESHOLD_MS = 30 * 60 * 1000L // 30 min
    }

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tileLiveTv.setOnClickListener { openChannels(ContentType.LIVE) }
        binding.tileMovies.setOnClickListener { openChannels(ContentType.MOVIE) }
        binding.tileSeries.setOnClickListener { openChannels(ContentType.SERIES) }

        // "Compte" et "Réglages" pointent tous les deux vers l'écran "À propos" :
        // c'est aujourd'hui le seul écran qui affiche le statut de licence,
        // la clé appareil et les infos de l'app.
        binding.tileAccount.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.tileSettings.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.tileChangeServer.setOnClickListener {
            startActivity(Intent(this, PlaylistsListActivity::class.java))
        }

        refreshCacheInBackgroundIfStale()
    }

    private fun openChannels(type: ContentType) {
        val intent = Intent(this, ChannelsActivity::class.java)
        intent.putExtra(ChannelsActivity.EXTRA_INITIAL_TYPE, type.name)
        startActivity(intent)
    }

    /**
     * Rafraîchit silencieusement la playlist active en arrière-plan si le
     * cache utilisé pour afficher cet écran commence à dater. Ne bloque
     * jamais l'interface, n'affiche rien à l'utilisateur : c'est ce qui
     * permet à l'app de "rester connectée" (comme les autres lecteurs IPTV)
     * tout en gardant les données à jour sans repasser par l'écran de
     * connexion à chaque lancement.
     */
    private fun refreshCacheInBackgroundIfStale() {
        val activeId = PlaylistStore.getActiveId(this) ?: return
        val playlist = PlaylistStore.getAll(this).firstOrNull { it.id == activeId } ?: return
        if (ChannelCacheStore.ageMillis(this, playlist.id) < CACHE_REFRESH_THRESHOLD_MS) return

        lifecycleScope.launch {
            try {
                val channels = if (playlist.extractXtreamCredentials() != null) {
                    XtreamApiClient.fetchAllChannelsDirect(playlist).channels
                } else {
                    val parsed = withContext(Dispatchers.IO) { M3uParser.fetchAndParse(playlist.buildUrl()) }
                    XtreamApiClient.enrichChannelsWithCategories(playlist, parsed)
                }
                if (channels.isNotEmpty()) {
                    ChannelRepository.setChannels(channels)
                    ChannelCacheStore.save(this@HomeActivity, playlist.id, channels)
                }
            } catch (e: Exception) {
                // Silencieux : hors-ligne ou serveur temporairement indisponible,
                // on garde simplement les chaînes déjà en cache/mémoire.
            }
        }
    }
}
