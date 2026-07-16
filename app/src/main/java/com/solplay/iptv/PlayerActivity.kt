package com.solplay.iptv

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.solplay.iptv.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_STREAM_NAME = "extra_stream_name"

        /** Durée d'affichage du bandeau orange avant qu'il ne disparaisse automatiquement. */
        private const val TITLE_DISPLAY_MS = 5000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var sideAdapter: ChannelAdapter
    private var sideChannels: List<Channel> = emptyList()
    private var activePlaylist: SavedPlaylist? = null
    private var programInfoJob: Job? = null

    private val hideHandler = Handler(Looper.getMainLooper())

    // Le bandeau du titre ET le bouton "Chaînes" apparaissent/disparaissent toujours ensemble :
    // visibles 5 secondes puis masqués automatiquement, et réaffichés sur simple tap écran.
    private val hideControlsRunnable = Runnable {
        binding.tvChannelTitle.visibility = View.GONE
        binding.tvProgramInfo.visibility = View.GONE
        if (sideChannels.size > 1) binding.btnChannelList.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Plein écran immersif : masque la barre de statut et la barre de navigation
        // pour que la vidéo occupe tout l'écran. L'écran reste aussi allumé pendant
        // la lecture (évite la mise en veille automatique).
        enableImmersiveFullscreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val startUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: return
        val startName = intent.getStringExtra(EXTRA_STREAM_NAME) ?: ""

        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    handlePlaybackError()
                }
            })
        }

        setupSidePanel()
        playStream(startUrl, startName)

        // Un tap sur l'écran (en dehors des contrôles ExoPlayer) refait apparaître
        // le titre et le bouton "Chaînes" ensemble, pour 5 secondes.
        binding.playerView.setOnClickListener { showControlsTemporarily() }

        binding.btnChannelList.setOnClickListener { toggleSidePanel() }
        setupSideSearch()
        startAssignmentWatcher()
    }

    /**
     * Tant que le lecteur est ouvert, revérifie périodiquement (toutes les
     * 20 secondes, plus une fois immédiatement au lancement) que la playlist
     * assignée par l'admin est toujours active.
     * Sans ça, une suppression d'assignation pendant que l'utilisateur
     * regarde déjà une chaîne n'avait AUCUN effet avant la fin de la
     * session : PlaylistStore.getActiveId()/getAll() ne sont lus qu'une
     * fois à l'ouverture (setupSidePanel), et ExoPlayer continue de lire un
     * flux réseau déjà ouvert indépendamment de ce que devient la base
     * locale ensuite.
     *
     * Ne s'applique qu'aux playlists assignées par l'admin ("device:*") -
     * une playlist que l'utilisateur a lui-même ajoutée n'est jamais coupée
     * de cette façon.
     */
    private var assignmentWatcherJob: Job? = null

    private fun startAssignmentWatcher() {
        val tag = activePlaylist?.fromCode ?: return
        if (!tag.startsWith("device:")) return

        assignmentWatcherJob = lifecycleScope.launch {
            while (true) {
                val stillValid = DevicePlaylistSync.checkStillAssigned(this@PlayerActivity, tag)
                if (!stillValid) {
                    Toast.makeText(
                        this@PlayerActivity,
                        "L'accès à cette playlist a été retiré par l'administrateur.",
                        Toast.LENGTH_LONG
                    ).show()
                    player?.stop()
                    finish()
                    break
                }
                kotlinx.coroutines.delay(20_000L)
            }
        }
    }

    /**
     * Passe la fenêtre en plein écran immersif : la barre de statut et la barre de
     * navigation système sont masquées et la vidéo occupe tout l'écran. Elles peuvent
     * revenir temporairement avec un balayage depuis le bord, puis se remasquent seules
     * (comportement "immersive sticky").
     */
    private fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Sur certains appareils, les barres système peuvent réapparaître quand la
        // fenêtre reprend le focus (ex: retour d'un dialogue) : on les remasque.
        if (hasFocus) enableImmersiveFullscreen()
    }

    /** Construit le panneau latéral avec la liste de chaînes transmise par ChannelsActivity. */
    private fun setupSidePanel() {
        sideChannels = ChannelRepository.playingList
        activePlaylist = PlaylistStore.getActiveId(this)
            ?.let { id -> PlaylistStore.getAll(this).firstOrNull { it.id == id } }
        // Layout "dark" : texte blanc, lisible sur le fond transparent qui laisse voir la vidéo.
        sideAdapter = ChannelAdapter(sideChannels, itemLayoutRes = R.layout.item_channel_dark, epgPlaylist = activePlaylist) { channel ->
            playStream(channel.streamUrl, channel.name)
            binding.channelListPanel.visibility = View.GONE
            binding.etSideSearch.text?.clear()
        }
        binding.recyclerSideChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerSideChannels.adapter = sideAdapter

        // On ne montre le bouton "Chaînes" que s'il y a effectivement d'autres chaînes
        // à proposer (évite un bouton inutile si on arrive d'ailleurs sans contexte).
        binding.btnChannelList.visibility = if (sideChannels.size > 1) View.VISIBLE else View.GONE
    }

    /** Filtre la liste du panneau latéral par nom de chaîne, en direct pendant la saisie. */
    private fun setupSideSearch() {
        binding.etSideSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                val filtered = if (query.isEmpty()) {
                    sideChannels
                } else {
                    sideChannels.filter { it.name.contains(query, ignoreCase = true) }
                }
                sideAdapter.updateData(filtered)
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun toggleSidePanel() {
        val opening = binding.channelListPanel.visibility != View.VISIBLE
        binding.channelListPanel.visibility = if (opening) View.VISIBLE else View.GONE
        if (opening) {
            // Le panneau étant ouvert, on garde titre + bouton visibles et on suspend
            // la disparition automatique pendant que l'utilisateur cherche/parcourt.
            showControlsTemporarily(keepVisible = true)
        } else {
            binding.etSideSearch.text?.clear()
            showControlsTemporarily()
        }
    }

    /**
     * Appelé quand ExoPlayer échoue à lire le flux en cours. Avant d'afficher
     * une simple erreur générique, on vérifie si la cause probable est
     * l'expiration de l'abonnement (voir XtreamApiClient.checkAccountStatus) :
     * c'est le cas le plus fréquent et le plus déroutant pour l'utilisateur,
     * puisque la liste de chaînes continue elle de s'afficher normalement.
     */
    private fun handlePlaybackError() {
        val playlist = activePlaylist
        if (playlist == null) {
            Toast.makeText(this, "Erreur de lecture. Vérifiez votre connexion et réessayez.", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val status = XtreamApiClient.checkAccountStatus(playlist)
            if (isFinishing) return@launch
            if (status?.expired == true) {
                val expiryText = status.expiresAtMillis?.let { TrialManager.formatDate(it) }
                val message = buildString {
                    append("Votre abonnement IPTV est arrivé à expiration")
                    if (expiryText != null) append(" le $expiryText")
                    append(".\n\nContactez votre fournisseur pour renouveler votre code.")
                }
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("⚠️ Abonnement expiré")
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } else {
                Toast.makeText(
                    this@PlayerActivity,
                    "Impossible de lire cette chaîne pour le moment. Réessayez plus tard.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Change (ou démarre) le flux en cours de lecture, sans recréer l'Activity. */
    private fun playStream(url: String, name: String) {
        binding.tvChannelTitle.text = name
        player?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
        loadProgramInfo(url)
        showControlsTemporarily()
    }

    /** Récupère et affiche le programme en cours (EPG) pour la chaîne en cours de lecture. */
    private fun loadProgramInfo(streamUrl: String) {
        programInfoJob?.cancel()
        binding.tvProgramInfo.visibility = View.GONE

        val playlist = activePlaylist ?: return
        val isLive = Channel(name = "", logoUrl = null, groupTitle = null, streamUrl = streamUrl).contentType() == ContentType.LIVE
        if (!isLive) return
        val streamId = XtreamApiClient.extractStreamId(streamUrl)
        if (streamId <= 0) return

        programInfoJob = lifecycleScope.launch {
            val program = XtreamApiClient.fetchNowPlaying(playlist, streamId) ?: return@launch
            binding.tvProgramInfo.text = "${program.startTime}-${program.endTime} · ${program.title}"
            if (binding.tvChannelTitle.visibility == View.VISIBLE) {
                binding.tvProgramInfo.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Affiche ensemble le bandeau du titre et le bouton "Chaînes".
     * Si [keepVisible] est vrai (panneau latéral ouvert), on ne programme pas leur disparition
     * automatique tant que l'utilisateur interagit avec la liste/recherche.
     */
    private fun showControlsTemporarily(keepVisible: Boolean = false) {
        binding.tvChannelTitle.visibility = View.VISIBLE
        if (binding.tvProgramInfo.text.isNotEmpty()) binding.tvProgramInfo.visibility = View.VISIBLE
        if (sideChannels.size > 1) binding.btnChannelList.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideControlsRunnable)
        if (!keepVisible) {
            hideHandler.postDelayed(hideControlsRunnable, TITLE_DISPLAY_MS)
        }
    }

    override fun onStop() {
        super.onStop()
        hideHandler.removeCallbacks(hideControlsRunnable)
        player?.release()
        player = null
    }
}
