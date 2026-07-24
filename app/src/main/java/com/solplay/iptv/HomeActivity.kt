package com.solplay.iptv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import android.view.View
import com.solplay.iptv.databinding.ActivityHomeBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** Remet l'horloge de la barre du haut à jour toutes les minutes tant que l'écran est visible. */
    private val clockRunnable = object : Runnable {
        override fun run() {
            binding.tvClock.text = clockFormat.format(Date())
            clockHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        // Si la playlist active a été supprimée pendant que cet écran était en
        // arrière-plan (ex: retour depuis "Mes playlists" après suppression),
        // on ne doit plus rien afficher ici : retour à l'écran de chargement.
        if (PlaylistStore.getActiveId(this) == null) {
            ChannelRepository.clear()
            startActivity(Intent(this, PlaylistActivity::class.java))
            finish()
        }
        clockHandler.post(clockRunnable)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tileLiveTv.setOnClickListener { openChannels(ContentType.LIVE) }
        binding.tileMovies.setOnClickListener { openChannels(ContentType.MOVIE) }
        binding.tileSeries.setOnClickListener { openChannels(ContentType.SERIES) }

        // ── Tuile Favoris ──
        binding.tileFavorites.setOnClickListener { openFavorites() }

        // ── Tuile Historique ──
        binding.tileHistory.setOnClickListener { openHistory() }

        // ── Tuile Reprendre ──
        val resume = ResumeStore.get(this)
        if (resume != null) {
            binding.tileResume.visibility = View.VISIBLE
            val label = if (resume.isLive) "▶ ${resume.name}" else "▶ ${resume.name}"
            binding.tvResumeLabel.text = label
            binding.tileResume.setOnClickListener {
                val ch = Channel(
                    name       = resume.name,
                    logoUrl    = resume.logoUrl,
                    groupTitle = null,
                    streamUrl  = resume.streamUrl
                )
                ChannelRepository.setPlayingList(listOf(ch))
                val intent = android.content.Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_STREAM_URL,  resume.streamUrl)
                    putExtra(PlayerActivity.EXTRA_STREAM_NAME, resume.name)
                    if (!resume.isLive) putExtra(PlayerActivity.EXTRA_RESUME_POS, resume.positionMs)
                }
                startActivity(intent)
            }
        } else {
            binding.tileResume.visibility = View.GONE
        }

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

        showAccountInfo()
        refreshCacheInBackgroundIfStale()
    }

    private fun openChannels(type: ContentType) {
        val intent = android.content.Intent(this, ChannelsActivity::class.java)
        intent.putExtra(ChannelsActivity.EXTRA_INITIAL_TYPE, type.name)
        startActivity(intent)
    }

    /** Ouvre la liste complète de l'historique de visionnage. */
    private fun openHistory() {
        startActivity(android.content.Intent(this, HistoryActivity::class.java))
    }

    /** Ouvre l'écran Chaînes pré-filtré sur la liste des favoris de l'utilisateur. */
    private fun openFavorites() {
        val favs = FavoritesStore.getAll(this)
        if (favs.isEmpty()) {
            android.widget.Toast.makeText(this,
                "Aucun favori pour l'instant.\nAppuyez longuement sur ☆ Favori dans le lecteur pour en ajouter.",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        ChannelRepository.setChannels(favs)
        val intent = android.content.Intent(this, ChannelsActivity::class.java).apply {
            putExtra(ChannelsActivity.EXTRA_INITIAL_TYPE, ContentType.LIVE.name)
            putExtra(ChannelsActivity.EXTRA_FAVORITES_MODE, true)
        }
        startActivity(intent)
    }

    /**
     * Affiche en bas de l'écran le nom de la playlist active ("Connecté : ...")
     * immédiatement (donnée déjà en mémoire), puis complète avec la date
     * d'expiration de l'abonnement dès qu'elle est connue (nécessite un appel
     * réseau à l'API Xtream, silencieux si la playlist n'est pas Xtream ou en
     * cas d'erreur - voir XtreamApiClient.checkAccountStatus).
     */
    private fun showAccountInfo() {
        val activeId = PlaylistStore.getActiveId(this) ?: return
        val playlist = PlaylistStore.getAll(this).firstOrNull { it.id == activeId } ?: return

        binding.tvConnectedAs.text = "Connecté : ${playlist.name}"

        lifecycleScope.launch {
            val status = XtreamApiClient.checkAccountStatus(playlist) ?: return@launch
            val expiresAt = status.expiresAtMillis ?: return@launch
            if (isFinishing) return@launch
            binding.tvExpiration.text = "Expiration : ${TrialManager.formatDate(expiresAt)}"
        }
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
        lifecycleScope.launch {
            val activeId = PlaylistStore.getActiveId(this@HomeActivity) ?: return@launch
            val playlist = PlaylistStore.getAll(this@HomeActivity).firstOrNull { it.id == activeId } ?: return@launch
            if (ChannelCacheStore.ageMillis(this@HomeActivity, playlist.id) < CACHE_REFRESH_THRESHOLD_MS) return@launch

            ChannelRefresher.refresh(this@HomeActivity, playlist)
        }
    }
}
