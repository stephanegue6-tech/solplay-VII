package com.solplay.iptv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RatingBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.launch

/**
 * Page détail Netflix-like pour un Film ou une Série.
 *
 * Affiche :
 *  - Affiche TMDB (grande)
 *  - Titre, année, genre, note /10, synopsis
 *  - Bouton "▶ Lire" (lance PlayerActivity)
 *  - Bouton "⭐ Favori"
 *  - Pour les Séries Xtream : liste des épisodes à charger
 *
 * Peut être ouvert depuis ChannelsActivity (long-press) ou depuis l'historique.
 */
class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_URL   = "detail_url"
        const val EXTRA_CHANNEL_NAME  = "detail_name"
        const val EXTRA_CHANNEL_LOGO  = "detail_logo"
        const val EXTRA_CHANNEL_GROUP = "detail_group"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Layout construit programmatiquement pour éviter un XML dédié
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF111827.toInt())
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(root)
        setContentView(scroll)

        val url   = intent.getStringExtra(EXTRA_CHANNEL_URL)   ?: return
        val name  = intent.getStringExtra(EXTRA_CHANNEL_NAME)  ?: ""
        val logo  = intent.getStringExtra(EXTRA_CHANNEL_LOGO)
        val group = intent.getStringExtra(EXTRA_CHANNEL_GROUP) ?: ""

        val channel = Channel(name = name, logoUrl = logo, groupTitle = group, streamUrl = url)
        val isMovie = channel.contentType() == ContentType.MOVIE

        val px = resources.displayMetrics.density

        // ── Affiche grande ──
        val poster = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (220 * px).toInt()
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1E2D42.toInt())
        }
        root.addView(poster)

        // ── Bouton retour ──
        val btnBack = Button(this).apply {
            text = "← Retour"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x80000000.toInt())
            val m = (12 * px).toInt()
            setPadding(m, m / 2, m, m / 2)
            setOnClickListener { finish() }
        }
        root.addView(btnBack)

        // ── Titre ──
        val tvTitle = TextView(this).apply {
            text = name
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val p = (16 * px).toInt()
            setPadding(p, p, p, 4)
        }
        root.addView(tvTitle)

        // ── Groupe / genre ──
        val tvGroup = TextView(this).apply {
            text = group
            textSize = 13f
            setTextColor(0xFF64748B.toInt())
            val p = (16 * px).toInt()
            setPadding(p, 0, p, 8)
        }
        root.addView(tvGroup)

        // ── Spinner TMDB ──
        val spinner = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (32 * px).toInt(), (32 * px).toInt()
            ).apply { val m = (16 * px).toInt(); setMargins(m, 0, m, 0) }
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFFF7A00.toInt())
        }
        root.addView(spinner)

        // ── Synopsis ──
        val tvSynopsis = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(0xFFCBD5E1.toInt())
            val p = (16 * px).toInt()
            setPadding(p, 8, p, 8)
            visibility = View.GONE
        }
        root.addView(tvSynopsis)

        // ── Note ──
        val tvMeta = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(0xFF94A3B8.toInt())
            val p = (16 * px).toInt()
            setPadding(p, 0, p, 16)
            visibility = View.GONE
        }
        root.addView(tvMeta)

        // ── Boutons action ──
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = (16 * px).toInt()
            setPadding(p, 0, p, 16)
        }
        root.addView(btnRow)

        val btnPlay = Button(this).apply {
            text = "▶  Lire"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFFF7A00.toInt())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * px).toInt()
            }
        }
        btnRow.addView(btnPlay)

        var isFav = FavoritesStore.isFavorite(this, url)
        val btnFav = Button(this).apply {
            text = if (isFav) "★ Favori" else "☆ Favori"
            setTextColor(0xFFFF7A00.toInt())
            setBackgroundColor(0xFF1E2D42.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        btnRow.addView(btnFav)

        // ── Zone épisodes (séries uniquement) ──
        val episodeSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(episodeSection)

        // ── Listeners ──
        btnPlay.setOnClickListener {
            val activeId = PlaylistStore.getActiveId(this)
            val playlist = activeId?.let { id -> PlaylistStore.getAll(this).firstOrNull { it.id == id } }
            ChannelRepository.setPlayingList(listOf(channel))
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_STREAM_URL,  url)
                putExtra(PlayerActivity.EXTRA_STREAM_NAME, name)
            }
            startActivity(intent)
        }

        btnFav.setOnClickListener {
            isFav = FavoritesStore.toggle(this, channel)
            btnFav.text = if (isFav) "★ Favori" else "☆ Favori"
        }

        // ── Chargement TMDB ──
        lifecycleScope.launch {
            val info = if (isMovie) TmdbClient.searchMovie(name).info
            else TmdbClient.searchTv(name).info

            spinner.visibility = View.GONE

            if (info != null) {
                // Affiche
                val posterUrl = info.posterUrl
                if (!posterUrl.isNullOrEmpty()) {
                    poster.load(posterUrl, ImageLoader.get(this@DetailActivity)) {
                        crossfade(true)
                    }
                } else if (!logo.isNullOrEmpty()) {
                    poster.load(logo, ImageLoader.get(this@DetailActivity))
                }

                // Synopsis
                val overview = info.overview
                if (!overview.isNullOrEmpty()) {
                    tvSynopsis.text = overview
                    tvSynopsis.visibility = View.VISIBLE
                }

                // Métadonnées
                val meta = buildString {
                    info.year?.let { append("$it  ") }
                    info.voteAverage?.let { v ->
                        if (v > 0) append("⭐ ${String.format("%.1f", v)}/10  ")
                    }
                    info.genres?.firstOrNull()?.let { append(it) }
                }
                if (meta.isNotBlank()) {
                    tvMeta.text = meta
                    tvMeta.visibility = View.VISIBLE
                }
            } else {
                // Pas de résultat TMDB : logo M3U si dispo
                if (!logo.isNullOrEmpty()) {
                    poster.load(logo, ImageLoader.get(this@DetailActivity))
                }
            }

            // Épisodes pour les séries Xtream
            if (!isMovie && XtreamApiClient.isSeriesShell(channel)) {
                val activeId = PlaylistStore.getActiveId(this@DetailActivity)
                val playlist = activeId?.let { id ->
                    PlaylistStore.getAll(this@DetailActivity).firstOrNull { it.id == id }
                }
                if (playlist != null) {
                    val episodes = XtreamApiClient.fetchSeriesEpisodes(playlist, channel)
                    if (episodes.isNotEmpty()) {
                        episodeSection.visibility = View.VISIBLE
                        val px2 = resources.displayMetrics.density
                        val tvEpTitle = TextView(this@DetailActivity).apply {
                            text = "Épisodes (${episodes.size})"
                            textSize = 16f
                            setTextColor(0xFFFFFFFF.toInt())
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            val p = (16 * px2).toInt()
                            setPadding(p, 8, p, 8)
                        }
                        episodeSection.addView(tvEpTitle)

                        episodes.forEachIndexed { idx, ep ->
                            val epRow = LinearLayout(this@DetailActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                val p = (16 * px2).toInt()
                                val v = (8 * px2).toInt()
                                setPadding(p, v, p, v)
                                setBackgroundColor(if (idx % 2 == 0) 0xFF162032.toInt() else 0xFF111827.toInt())
                                isClickable = true
                                isFocusable = true
                                setOnClickListener {
                                    ChannelRepository.setPlayingList(episodes)
                                    startActivity(Intent(this@DetailActivity, PlayerActivity::class.java).apply {
                                        putExtra(PlayerActivity.EXTRA_STREAM_URL,  ep.streamUrl)
                                        putExtra(PlayerActivity.EXTRA_STREAM_NAME, ep.name)
                                    })
                                }
                            }
                            val tvEp = TextView(this@DetailActivity).apply {
                                text = ep.name
                                textSize = 13f
                                setTextColor(0xFFCBD5E1.toInt())
                                layoutParams = LinearLayout.LayoutParams(0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }
                            val tvPlay = TextView(this@DetailActivity).apply {
                                text = "▶"
                                textSize = 16f
                                setTextColor(0xFFFF7A00.toInt())
                            }
                            epRow.addView(tvEp)
                            epRow.addView(tvPlay)
                            episodeSection.addView(epRow)
                        }
                    }
                }
            }
        }
    }
}
