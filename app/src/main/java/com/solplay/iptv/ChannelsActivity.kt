package com.solplay.iptv

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.solplay.iptv.databinding.ActivityChannelsBinding
import kotlinx.coroutines.launch

class ChannelsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNELS = "extra_channels"
        const val EXTRA_INITIAL_TYPE = "extra_initial_type"
        private const val ALL_BOUQUETS = "Tous"
    }

    private lateinit var binding: ActivityChannelsBinding
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var bouquetAdapter: BouquetAdapter

    private var allChannels: List<Channel> = emptyList()
    private var currentType: ContentType = ContentType.LIVE
    private var currentBouquet: String = ALL_BOUQUETS
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        allChannels = ChannelRepository.channels

        val activePlaylist = PlaylistStore.getActiveId(this)
            ?.let { id -> PlaylistStore.getAll(this).firstOrNull { it.id == id } }

        channelAdapter = ChannelAdapter(
            emptyList(),
            epgPlaylist = activePlaylist,
            onLongClick = { channel -> showProgramGuide(channel, activePlaylist) }
        ) { channel -> openPlayer(channel) }
        binding.recyclerChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerChannels.adapter = channelAdapter

        bouquetAdapter = BouquetAdapter(emptyList()) { bouquet ->
            currentBouquet = bouquet.name
            bouquetAdapter.setSelected(bouquet.name)
            applyFilters()
        }
        binding.recyclerBouquets.layoutManager = LinearLayoutManager(this)
        binding.recyclerBouquets.adapter = bouquetAdapter

        setupTabs()
        setupSearch()

        binding.btnEpgGrid.setOnClickListener { openEpgGrid() }

        when (intent.getStringExtra(EXTRA_INITIAL_TYPE)) {
            ContentType.MOVIE.name -> binding.tabLayout.getTabAt(1)?.select()
            ContentType.SERIES.name -> binding.tabLayout.getTabAt(2)?.select()
            else -> binding.tabLayout.getTabAt(0)?.select()
        }

        refreshBouquets()
        applyFilters()

        checkSubscriptionExpiration(activePlaylist)
    }

    /**
     * Vérifie en tâche de fond si l'abonnement (code M3U/Xtream) est expiré,
     * et affiche une alerte claire si c'est le cas.
     *
     * Pourquoi c'est nécessaire : un code M3U/Xtream expiré continue très
     * souvent d'être servi tel quel par le panel du fournisseur (la liste de
     * chaînes se charge normalement, comme si de rien n'était) - seuls les
     * flux vidéo eux-mêmes cessent de fonctionner à la lecture, sans qu'aucun
     * message ne l'explique à l'utilisateur. On interroge donc ici l'API du
     * panel pour connaître le vrai statut du compte.
     *
     * Sans effet (silencieux) si la playlist n'est pas reconnue comme un
     * lien Xtream (voir SavedPlaylist.extractXtreamCredentials) ou en cas
     * d'erreur réseau : on ne bloque jamais l'accès sur la base d'une
     * vérification qui a échoué.
     */
    private fun checkSubscriptionExpiration(playlist: SavedPlaylist?) {
        if (playlist == null) return
        lifecycleScope.launch {
            val status = XtreamApiClient.checkAccountStatus(playlist) ?: return@launch
            if (status.expired && !isFinishing) {
                val expiryText = status.expiresAtMillis?.let { TrialManager.formatDate(it) }
                val message = buildString {
                    append("Votre abonnement IPTV (")
                    append(playlist.name)
                    append(") est arrivé à expiration")
                    if (expiryText != null) append(" le $expiryText")
                    append(".\n\nLes chaînes affichées ne pourront plus être lues. ")
                    append("Contactez votre fournisseur pour renouveler votre code.")
                }
                AlertDialog.Builder(this@ChannelsActivity)
                    .setTitle("⚠️ Abonnement expiré")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .setCancelable(true)
                    .show()
            }
        }
    }

    /**
     * Affiche le programme complet à venir d'une chaîne (appui long sur la
     * chaîne dans la liste). Contrairement à la ligne "en cours" affichée
     * directement dans la liste, ceci montre plusieurs émissions dans
     * l'ordre chronologique - le maximum qu'on puisse obtenir sans
     * construire un écran de grille façon zappeur multi-chaînes.
     */
    private fun showProgramGuide(channel: Channel, playlist: SavedPlaylist?) {
        if (playlist == null) return
        val streamId = XtreamApiClient.extractStreamId(channel.streamUrl)
        if (streamId <= 0) return

        val loadingDialog = AlertDialog.Builder(this)
            .setTitle(channel.name)
            .setMessage("Chargement du programme…")
            .setCancelable(true)
            .create()
        loadingDialog.show()

        lifecycleScope.launch {
            val programs = XtreamApiClient.fetchProgramGuide(playlist, streamId, limit = 20)
            if (isFinishing) return@launch
            loadingDialog.dismiss()

            if (programs.isEmpty()) {
                AlertDialog.Builder(this@ChannelsActivity)
                    .setTitle(channel.name)
                    .setMessage("Aucune information de programme disponible pour cette chaîne.")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }

            val lines = programs.map { "${it.startTime} - ${it.endTime}\n${it.title}" }.toTypedArray()
            AlertDialog.Builder(this@ChannelsActivity)
                .setTitle(channel.name)
                .setItems(lines, null)
                .setPositiveButton("Fermer", null)
                .show()
        }
    }

    /**
     * Ouvre la grille EPG multi-chaînes pour les chaînes Live actuellement
     * affichées (bouquet + recherche en cours). La grille s'appuie sur l'API
     * Xtream (`get_short_epg`) : elle nécessite donc des identifiants Xtream
     * (serveur/user/password), qu'ils aient été saisis directement en mode
     * "Xtream Codes", ou détectés automatiquement dans un lien M3U qui est
     * en réalité un lien Xtream déguisé (voir SavedPlaylist.extractXtreamCredentials).
     */
    private fun openEpgGrid() {
        val activePlaylist = PlaylistStore.getActiveId(this)
            ?.let { id -> PlaylistStore.getAll(this).firstOrNull { it.id == id } }

        if (activePlaylist == null || activePlaylist.extractXtreamCredentials() == null) {
            android.widget.Toast.makeText(
                this,
                "La grille EPG nécessite une playlist Xtream Codes (le programme n'est pas fourni par un simple lien M3U générique).",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        val liveChannels = channelAdapter.currentList().filter { it.contentType() == ContentType.LIVE }
        if (liveChannels.isEmpty()) {
            android.widget.Toast.makeText(this, "Aucune chaîne Live à afficher.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        ChannelRepository.setEpgGridChannels(liveChannels)
        startActivity(Intent(this, EpgGridActivity::class.java))
    }

    private fun openPlayer(channel: Channel) {
        // Une "coquille" de série (chargement direct JSON, voir XtreamApiClient) ne
        // contient pas encore d'épisodes lisibles : on les récupère à la demande, au
        // moment où l'utilisateur ouvre cette série précise (plutôt que de charger
        // les épisodes de toutes les séries d'un coup, bien trop long).
        if (XtreamApiClient.isSeriesShell(channel)) {
            openSeriesEpisodes(channel)
            return
        }
        ChannelRepository.setPlayingList(channelsForCurrentType())
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
        intent.putExtra(PlayerActivity.EXTRA_STREAM_NAME, channel.name)
        startActivity(intent)
    }

    private fun openSeriesEpisodes(seriesChannel: Channel) {
        val activePlaylist = PlaylistStore.getActiveId(this)
            ?.let { id -> PlaylistStore.getAll(this).firstOrNull { it.id == id } } ?: return

        val progress = android.app.ProgressDialog.show(this, null, "Chargement des épisodes…", true, false)
        lifecycleScope.launch {
            val episodes = XtreamApiClient.fetchSeriesEpisodes(activePlaylist, seriesChannel)
            progress.dismiss()
            if (isFinishing) return@launch
            if (episodes.isEmpty()) {
                android.widget.Toast.makeText(this@ChannelsActivity, "Aucun épisode trouvé pour cette série.", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = episodes.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@ChannelsActivity)
                .setTitle(seriesChannel.name)
                .setItems(labels) { _, index ->
                    val episode = episodes[index]
                    ChannelRepository.setPlayingList(episodes)
                    val intent = Intent(this@ChannelsActivity, PlayerActivity::class.java)
                    intent.putExtra(PlayerActivity.EXTRA_STREAM_URL, episode.streamUrl)
                    intent.putExtra(PlayerActivity.EXTRA_STREAM_NAME, episode.name)
                    startActivity(intent)
                }
                .setNegativeButton("Fermer", null)
                .show()
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Live"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Films"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Séries"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentType = when (tab.position) {
                    1 -> ContentType.MOVIE
                    2 -> ContentType.SERIES
                    else -> ContentType.LIVE
                }
                currentBouquet = ALL_BOUQUETS
                refreshBouquets()
                applyFilters()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun channelsForCurrentType(): List<Channel> =
        allChannels.filter { it.contentType() == currentType }

    private fun refreshBouquets() {
        val channels = channelsForCurrentType()
        val bouquets = mutableListOf(Bouquet(ALL_BOUQUETS, channels.size))

        bouquets += channels
            .mapNotNull { it.groupTitle?.trim()?.takeIf { g -> g.isNotEmpty() } }
            .groupingBy { it }
            .eachCount()
            .toSortedMap()
            .map { (name, count) -> Bouquet(name, count) }

        bouquetAdapter.updateData(bouquets, currentBouquet)
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString()?.trim() ?: ""
                applyFilters()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun applyFilters() {
        var list = channelsForCurrentType()

        if (currentBouquet != ALL_BOUQUETS) {
            list = list.filter { it.groupTitle?.trim() == currentBouquet }
        }
        if (currentQuery.isNotEmpty()) {
            list = list.filter { it.name.contains(currentQuery, ignoreCase = true) }
        }

        channelAdapter.updateData(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }
}
