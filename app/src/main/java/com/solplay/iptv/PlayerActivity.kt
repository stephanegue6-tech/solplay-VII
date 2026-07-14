package com.solplay.iptv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.solplay.iptv.databinding.ActivityPlayerBinding

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

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideTitleRunnable = Runnable { binding.tvChannelTitle.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val startUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: return
        val startName = intent.getStringExtra(EXTRA_STREAM_NAME) ?: ""

        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer
        }

        setupSidePanel()
        playStream(startUrl, startName)

        // Un tap sur l'écran (en dehors des contrôles ExoPlayer) refait apparaître
        // le bandeau du nom de la chaîne pour 5 secondes.
        binding.playerView.setOnClickListener { showTitleTemporarily() }

        binding.btnChannelList.setOnClickListener { toggleSidePanel() }
    }

    /** Construit le panneau latéral avec la liste de chaînes transmise par ChannelsActivity. */
    private fun setupSidePanel() {
        val list = ChannelRepository.playingList
        sideAdapter = ChannelAdapter(list) { channel ->
            playStream(channel.streamUrl, channel.name)
            binding.channelListPanel.visibility = View.GONE
        }
        binding.recyclerSideChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerSideChannels.adapter = sideAdapter

        // On ne montre le bouton "Chaînes" que s'il y a effectivement d'autres chaînes
        // à proposer (évite un bouton inutile si on arrive d'ailleurs sans contexte).
        binding.btnChannelList.visibility = if (list.size > 1) View.VISIBLE else View.GONE
    }

    private fun toggleSidePanel() {
        binding.channelListPanel.visibility =
            if (binding.channelListPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** Change (ou démarre) le flux en cours de lecture, sans recréer l'Activity. */
    private fun playStream(url: String, name: String) {
        binding.tvChannelTitle.text = name
        player?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
        showTitleTemporarily()
    }

    private fun showTitleTemporarily() {
        binding.tvChannelTitle.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideTitleRunnable)
        hideHandler.postDelayed(hideTitleRunnable, TITLE_DISPLAY_MS)
    }

    override fun onStop() {
        super.onStop()
        hideHandler.removeCallbacks(hideTitleRunnable)
        player?.release()
        player = null
    }
}
