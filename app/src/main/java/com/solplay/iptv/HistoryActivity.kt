package com.solplay.iptv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Historique de visionnage complet — liste des 50 derniers contenus regardés.
 * Tap → relance la lecture. Long-press (Film/Série) → page détail.
 */
class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF111827.toInt()) }
        val root   = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)

        val px   = resources.displayMetrics.density
        val pad  = (16 * px).toInt()

        // En-tête
        val header = LinearLayout(this).apply {
            setBackgroundColor(0xFFFF7A00.toInt())
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, (12 * px).toInt(), pad, (12 * px).toInt())
        }
        val btnBack = TextView(this).apply {
            text = "←"; textSize = 20f; setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, pad, 0); setOnClickListener { finish() }
        }
        val tvTitle = TextView(this).apply {
            text = "🕐 Historique"; textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(btnBack)
        header.addView(tvTitle)
        root.addView(header)

        val history = WatchHistoryStore.getAll(this)

        if (history.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Aucun contenu regardé pour l'instant."
                textSize = 14f; setTextColor(0xFF64748B.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(pad, (40 * px).toInt(), pad, pad)
            }
            root.addView(empty)
            return
        }

        // Bouton vider l'historique
        val btnClear = TextView(this).apply {
            text = "🗑 Vider l'historique"
            textSize = 12f; setTextColor(0xFF94A3B8.toInt())
            gravity = android.view.Gravity.END
            setPadding(pad, (8 * px).toInt(), pad, (4 * px).toInt())
            setOnClickListener {
                WatchHistoryStore.clear(this@HistoryActivity)
                root.removeAllViews()
                root.addView(header)
                val empty = TextView(this@HistoryActivity).apply {
                    text = "Historique vidé."
                    textSize = 14f; setTextColor(0xFF64748B.toInt())
                    gravity = android.view.Gravity.CENTER
                    setPadding(pad, (40 * px).toInt(), pad, pad)
                }
                root.addView(empty)
            }
        }
        root.addView(btnClear)

        val dateFmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale("fr"))
        val activeId = PlaylistStore.getActiveId(this)
        val playlist = activeId?.let { id -> PlaylistStore.getAll(this).firstOrNull { it.id == id } }

        history.forEachIndexed { idx, entry ->
            val typeIcon = when (entry.contentType) {
                "MOVIE"  -> "🎬"
                "SERIES" -> "🎞"
                else     -> "📺"
            }
            val posLabel = if (entry.positionMs > 0) {
                val min = entry.positionMs / 60_000
                "  · Arrêté à ${min}min"
            } else ""

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(if (idx % 2 == 0) 0xFF162032.toInt() else 0xFF111827.toInt())
                val v = (10 * px).toInt()
                setPadding(pad, v, pad, v)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    val ch = Channel(entry.name, entry.logoUrl, entry.groupTitle, entry.streamUrl)
                    ChannelRepository.setPlayingList(listOf(ch))
                    startActivity(Intent(this@HistoryActivity, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_STREAM_URL,  entry.streamUrl)
                        putExtra(PlayerActivity.EXTRA_STREAM_NAME, entry.name)
                        if (entry.positionMs > 0)
                            putExtra(PlayerActivity.EXTRA_RESUME_POS, entry.positionMs)
                    })
                }
                setOnLongClickListener {
                    if (entry.contentType != "LIVE") {
                        startActivity(Intent(this@HistoryActivity, DetailActivity::class.java).apply {
                            putExtra(DetailActivity.EXTRA_CHANNEL_URL,   entry.streamUrl)
                            putExtra(DetailActivity.EXTRA_CHANNEL_NAME,  entry.name)
                            putExtra(DetailActivity.EXTRA_CHANNEL_LOGO,  entry.logoUrl)
                            putExtra(DetailActivity.EXTRA_CHANNEL_GROUP, entry.groupTitle)
                        })
                        true
                    } else false
                }
            }

            val tvInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvName = TextView(this).apply {
                text = "$typeIcon  ${entry.name}"
                textSize = 14f; setTextColor(0xFFE2E8F0.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val tvMeta = TextView(this).apply {
                text = dateFmt.format(Date(entry.watchedAt)) + posLabel
                textSize = 11f; setTextColor(0xFF64748B.toInt())
            }
            tvInfo.addView(tvName)
            tvInfo.addView(tvMeta)

            val tvPlay = TextView(this).apply {
                text = "▶"
                textSize = 18f; setTextColor(0xFFFF7A00.toInt())
                setPadding((12 * px).toInt(), 0, 0, 0)
            }

            row.addView(tvInfo)
            row.addView(tvPlay)
            root.addView(row)
        }
    }
}
