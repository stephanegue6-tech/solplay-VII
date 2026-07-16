package com.solplay.iptv

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.solplay.iptv.databinding.ActivityPlaylistsListBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran "Mes playlists" : liste des playlists enregistrées (manuelles ou via
 * code admin), avec possibilité de se connecter, modifier ou supprimer.
 */
class PlaylistsListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistsListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistsListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerPlaylists.layoutManager = LinearLayoutManager(this)

        binding.btnAddManual.setOnClickListener {
            startActivity(Intent(this, PlaylistActivity::class.java))
        }
        binding.btnRedeemCode.setOnClickListener {
            startActivity(Intent(this, RedeemCodeActivity::class.java))
        }
        binding.btnRefreshAccounts.setOnClickListener {
            refreshAccounts()
        }
    }

   override fun onResume() {
    super.onResume()
    refresh()
    // Vérifie si l'admin a assigné une playlist à la clé de cet appareil
    // depuis le panneau admin, et l'ajoute automatiquement si oui.
    lifecycleScope.launch {
        DevicePlaylistSync.sync(this@PlaylistsListActivity)
        refresh()
    }
}


    /**
     * Force une resynchronisation immédiate avec les codes/comptes assignés par
     * l'admin (Firebase "device_playlists"), sans avoir à ouvrir "Modifier".
     * Utile juste après que l'admin a assigné ou changé un code M3U/Xtream.
     */
    private fun refreshAccounts() {
        binding.btnRefreshAccounts.isEnabled = false
        binding.btnRefreshAccounts.text = "Actualisation…"
        lifecycleScope.launch {
            try {
                DevicePlaylistSync.sync(this@PlaylistsListActivity)
                refresh()
                Toast.makeText(
                    this@PlaylistsListActivity,
                    "Comptes actualisés.",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@PlaylistsListActivity,
                    "Impossible d'actualiser : ${e.message ?: "erreur inconnue"}.",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.btnRefreshAccounts.isEnabled = true
                binding.btnRefreshAccounts.text = "🔄 Actualiser les comptes"
            }
        }
    }

    private fun refresh() {
        val playlists = PlaylistStore.getAll(this)
        val activeId = PlaylistStore.getActiveId(this)
        binding.tvEmptyPlaylists.visibility =
            if (playlists.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        binding.recyclerPlaylists.adapter = SavedPlaylistAdapter(
            playlists = playlists,
            activeId = activeId,
            onConnect = { connect(it) },
            onEdit = { edit(it) },
            onDelete = { confirmDelete(it) }
        )
    }

    /** Se connecte à une playlist enregistrée : la retélécharge et ouvre l'écran des chaînes. */
    private fun connect(playlist: SavedPlaylist) {
        Toast.makeText(this, "Connexion à « ${playlist.name} »…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val channels = if (playlist.extractXtreamCredentials() != null) {
                    XtreamApiClient.fetchAllChannelsDirect(playlist).channels
                } else {
                    val parsed = withContext(Dispatchers.IO) { M3uParser.fetchAndParse(playlist.buildUrl()) }
                    XtreamApiClient.enrichChannelsWithCategories(playlist, parsed)
                }
                if (channels.isEmpty()) {
                    Toast.makeText(
                        this@PlaylistsListActivity,
                        "Aucune chaîne trouvée pour cette playlist.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                PlaylistStore.setActiveId(this@PlaylistsListActivity, playlist.id)
                ChannelCacheStore.save(this@PlaylistsListActivity, playlist.id, channels)
                ChannelRepository.setChannels(channels)
                startActivity(Intent(this@PlaylistsListActivity, HomeActivity::class.java))
            } catch (e: PlaylistLoadException) {
                Toast.makeText(this@PlaylistsListActivity, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@PlaylistsListActivity,
                    "Erreur de connexion : ${e.message ?: "inconnue"}.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun edit(playlist: SavedPlaylist) {
        val intent = Intent(this, PlaylistActivity::class.java)
        intent.putExtra(PlaylistActivity.EXTRA_EDIT_ID, playlist.id)
        startActivity(intent)
    }

    private fun confirmDelete(playlist: SavedPlaylist) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer « ${playlist.name} » ?")
            .setMessage("Cette playlist sera retirée de la liste.")
            .setPositiveButton("Supprimer") { _, _ ->
                PlaylistStore.delete(this, playlist.id)
                ChannelCacheStore.clear(this)
                refresh()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
