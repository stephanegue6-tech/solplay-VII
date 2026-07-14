package com.solplay.iptv

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.solplay.iptv.databinding.ActivityHomeBinding

/**
 * Écran d'accueil affiché juste après le chargement d'une playlist : un menu
 * en grille (Live TV / Films / Séries / Compte / Changer serveur / Réglages)
 * plutôt que d'atterrir directement sur la liste brute des chaînes.
 */
class HomeActivity : AppCompatActivity() {

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
    }

    private fun openChannels(type: ContentType) {
        val intent = Intent(this, ChannelsActivity::class.java)
        intent.putExtra(ChannelsActivity.EXTRA_INITIAL_TYPE, type.name)
        startActivity(intent)
    }
}
