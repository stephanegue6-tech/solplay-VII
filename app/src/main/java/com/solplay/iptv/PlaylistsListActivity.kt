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
    // depuis le panneau admin, et l'ajoute automatiquement si oui - puis s'y
    // connecte automatiquement (silencieusement, sans Toast) si ce n'est pas
    // déjà la playlist active. Ainsi, même un utilisateur qui ne sait pas/ne
    // peut pas se connecter lui-même se retrouve connecté simplement en
    // rouvrant l'app - l'admin gère tout depuis son espace.
    lifecycleScope.launch {
        DevicePlaylistSync.sync(this@PlaylistsListActivity)
        refresh()

        val activeId = PlaylistStore.getActiveId(this@PlaylistsListActivity)
        val deviceAssigned = PlaylistStore.getAll(this@PlaylistsListActivity)
            .filter { it.fromCode?.startsWith("device:") == true }

        // Un seul compte assigné par l'admin, pas encore actif sur cet
        // appareil (nouvelle assignation, ou admin a changé les identifiants
        // depuis son espace) : connexion automatique silencieuse.
        if (deviceAssigned.size == 1 && deviceAssigned.first().id != activeId) {
            connect(deviceAssigned.first(), silent = true)
        }
    }
}


    /**
     * Force une resynchronisation immédiate avec les codes/comptes assignés par
     * l'admin (Firebase "device_playlists"), ET se reconnecte automatiquement
     * à la playlist assignée (retélécharge ses chaînes, l'active, ouvre
     * l'accueil) - sans que l'utilisateur ait besoin de savoir/pouvoir aller
     * dans "Modifier" pour relancer la connexion lui-même. C'est ce qui permet
     * à l'admin de "connecter" un client à distance depuis son espace : il
     * assigne/modifie la playlist côté admin, le client n'a plus qu'à appuyer
     * sur ce seul bouton (ou même automatiquement au prochain lancement, voir
     * onResume) pour que tout se fasse tout seul.
     */
    private fun refreshAccounts() {
        binding.btnRefreshAccounts.isEnabled = false
        binding.btnRefreshAccounts.text = "Actualisation…"
        lifecycleScope.launch {
            try {
                DevicePlaylistSync.sync(this@PlaylistsListActivity)
                refresh()

                // Playlist(s) assignée(s) directement par l'admin (par opposition
                // à celles ajoutées manuellement ou via un code saisi par le
                // client) : c'est celle(s) qu'on reconnecte automatiquement.
                val deviceAssigned = PlaylistStore.getAll(this@PlaylistsListActivity)
                    .filter { it.fromCode?.startsWith("device:") == true }

                when {
                    deviceAssigned.isEmpty() -> {
                        Toast.makeText(
                            this@PlaylistsListActivity,
                            "Comptes actualisés.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    deviceAssigned.size == 1 -> {
                        // Cas normal (un seul compte assigné par l'admin à cet
                        // appareil) : reconnexion automatique complète, même si
                        // c'était déjà la playlist active (l'utilisateur a
                        // appuyé sur "Actualiser" volontairement : on force le
                        // rechargement, par exemple après que l'admin a changé
                        // les identifiants d'un compte déjà assigné).
                        connect(deviceAssigned.first())
                    }
                    else -> {
                        // Plusieurs assignations actives à la fois : cas rare,
                        // on ne devine pas laquelle activer à la place de
                        // l'utilisateur - on se contente de les avoir
                        // synchronisées, l'utilisateur choisit dans la liste.
                        Toast.makeText(
                            this@PlaylistsListActivity,
                            "Comptes actualisés (${deviceAssigned.size} comptes assignés, choisissez lequel connecter).",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@PlaylistsListActivity,
                    "Impossible d'actualiser : ${e.message ?: "erreur inconnue"}.",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.btnRefreshAccounts.isEnabled = true
                binding.btnRefreshAccounts.text = "🔄 Actualiser (connexion automatique)"
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

    /**
     * Se connecte à une playlist enregistrée : la retélécharge et ouvre l'écran des chaînes.
     * [silent] : true quand appelé automatiquement en arrière-plan (onResume) plutôt que
     * suite à un appui explicite de l'utilisateur - évite d'afficher des Toasts "Connexion…"
     * intempestifs à chaque simple retour sur cet écran.
     */
    private fun connect(playlist: SavedPlaylist, silent: Boolean = false) {
        if (!silent) {
            Toast.makeText(this, "Connexion à « ${playlist.name} »…", Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            try {
                val channels = if (playlist.extractXtreamCredentials() != null) {
                    XtreamApiClient.fetchAllChannelsDirect(playlist).channels
                } else {
                    val parsed = withContext(Dispatchers.IO) { M3uParser.fetchAndParse(playlist.buildUrl()) }
                    XtreamApiClient.enrichChannelsWithCategories(playlist, parsed)
                }
                if (channels.isEmpty()) {
                    if (!silent) {
                        Toast.makeText(
                            this@PlaylistsListActivity,
                            "Aucune chaîne trouvée pour cette playlist.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                PlaylistStore.setActiveId(this@PlaylistsListActivity, playlist.id)
                ChannelCacheStore.save(this@PlaylistsListActivity, playlist.id, channels)
                ChannelRepository.setChannels(channels)
                startActivity(Intent(this@PlaylistsListActivity, HomeActivity::class.java))
            } catch (e: PlaylistLoadException) {
                if (!silent) {
                    Toast.makeText(this@PlaylistsListActivity, e.message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                if (!silent) {
                    Toast.makeText(
                        this@PlaylistsListActivity,
                        "Erreur de connexion : ${e.message ?: "inconnue"}.",
                        Toast.LENGTH_LONG
                    ).show()
                }
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
                ChannelRepository.clear()
                refresh()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
