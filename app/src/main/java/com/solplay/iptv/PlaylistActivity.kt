package com.solplay.iptv

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.solplay.iptv.databinding.ActivityPlaylistBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistActivity : AppCompatActivity() {

    companion object {
        /** Si présent, l'écran s'ouvre en mode "modification" de cette playlist existante. */
        const val EXTRA_EDIT_ID = "extra_edit_id"
    }

    private lateinit var binding: ActivityPlaylistBinding
    private var editingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sécurité : si l'essai a expiré et pas de licence, retour à l'écran d'activation
        if (!TrialManager.canAccessApp(this)) {
            startActivity(Intent(this, LicenseActivity::class.java))
            finish()
            return
        }

        binding = ActivityPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DisclaimerDialog.showIfNeeded(this)

        if (TrialManager.isLicensed(this)) {
            binding.tvTrialBanner.visibility = android.view.View.GONE
        } else {
            // Se met à jour chaque minute tant que l'écran est affiché, au lieu
            // de rester figé sur la valeur calculée à l'ouverture de l'écran.
            LiveCountdown.attach(this) { updateTrialBanner() }
        }

        // Mode édition : si on vient de "Mes playlists" avec une playlist à modifier,
        // on pré-remplit le formulaire avec ses valeurs actuelles.
        editingId = intent.getStringExtra(EXTRA_EDIT_ID)
        editingId?.let { id ->
            PlaylistStore.getAll(this).firstOrNull { it.id == id }?.let { fillFields(it) }
        }

        // Bascule entre le mode "Lien M3U" et le mode "Xtream Codes"
        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbXtream) {
                binding.llM3u.visibility = android.view.View.GONE
                binding.llXtream.visibility = android.view.View.VISIBLE
            } else {
                binding.llM3u.visibility = android.view.View.VISIBLE
                binding.llXtream.visibility = android.view.View.GONE
            }
        }

        binding.btnLoadPlaylist.setOnClickListener {
            val playlist = buildPlaylist()
            if (playlist == null) {
                Toast.makeText(this, "Veuillez remplir tous les champs requis", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            loadPlaylist(playlist)
        }

        binding.btnMyPlaylists.setOnClickListener {
            startActivity(Intent(this, PlaylistsListActivity::class.java))
        }

        binding.btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun updateTrialBanner() {
        val remaining = TrialManager.getRemainingTrialMillis(this)
        if (remaining <= 0) {
            // Essai terminé : on renvoie vers l'écran d'activation/licence.
            startActivity(Intent(this, LicenseActivity::class.java))
            finish()
            return
        }
        binding.tvTrialBanner.text = getString(
            R.string.trial_active_format,
            TrialManager.formatDuration(remaining)
        )
    }

    /** Pré-remplit le formulaire à partir d'une playlist existante (mode édition). */
    private fun fillFields(playlist: SavedPlaylist) {
        binding.etPlaylistName.setText(playlist.name)
        if (playlist.mode == PlaylistMode.XTREAM) {
            binding.rbXtream.isChecked = true
            binding.llM3u.visibility = android.view.View.GONE
            binding.llXtream.visibility = android.view.View.VISIBLE
        }
        binding.etPlaylistUrl.setText(playlist.m3uUrl)
        binding.etXtreamServer.setText(playlist.xtreamServer)
        binding.etXtreamUsername.setText(playlist.xtreamUsername)
        binding.etXtreamPassword.setText(playlist.xtreamPassword)
    }

    /**
     * Construit la playlist saisie dans le formulaire selon le mode choisi.
     * - Mode M3U : utilise directement le lien saisi.
     * - Mode Xtream Codes : construit l'URL standard de l'API Xtream
     *   (utilisée par la plupart des fournisseurs IPTV légaux avec ce protocole).
     */
    private fun buildPlaylist(): SavedPlaylist? {
        val name = binding.etPlaylistName.text.toString().trim().ifEmpty { "Ma playlist" }
        return if (binding.rbXtream.isChecked) {
            val server = binding.etXtreamServer.text.toString().trim().trimEnd('/')
            val username = binding.etXtreamUsername.text.toString().trim()
            val password = binding.etXtreamPassword.text.toString().trim()
            if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
                null
            } else {
                SavedPlaylist(
                    id = editingId ?: java.util.UUID.randomUUID().toString(),
                    name = name,
                    mode = PlaylistMode.XTREAM,
                    xtreamServer = server,
                    xtreamUsername = username,
                    xtreamPassword = password
                )
            }
        } else {
            val url = binding.etPlaylistUrl.text.toString().trim()
            if (url.isEmpty()) {
                null
            } else {
                SavedPlaylist(
                    id = editingId ?: java.util.UUID.randomUUID().toString(),
                    name = name,
                    mode = PlaylistMode.M3U,
                    m3uUrl = url
                )
            }
        }
    }

    private fun loadPlaylist(playlist: SavedPlaylist) {
        binding.progressBar.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            try {
                val channels = withContext(Dispatchers.IO) { M3uParser.fetchAndParse(playlist.buildUrl()) }
                binding.progressBar.visibility = android.view.View.GONE
                if (channels.isEmpty()) {
                    Toast.makeText(this@PlaylistActivity, "Aucune chaîne trouvée. Vérifiez vos identifiants/lien.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                // On enregistre/actualise la playlist dans la liste locale et on la
                // marque comme "active", puis on stocke les chaînes en mémoire
                // (ChannelRepository) au lieu de les faire passer par l'Intent.
                PlaylistStore.save(this@PlaylistActivity, playlist)
                PlaylistStore.setActiveId(this@PlaylistActivity, playlist.id)
                ChannelRepository.setChannels(channels)
                startActivity(Intent(this@PlaylistActivity, HomeActivity::class.java))
            } catch (e: PlaylistLoadException) {
                // Message déjà clair et destiné à l'utilisateur (timeout, serveur, réseau...).
                binding.progressBar.visibility = android.view.View.GONE
                Toast.makeText(this@PlaylistActivity, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                binding.progressBar.visibility = android.view.View.GONE
                Toast.makeText(this@PlaylistActivity, "Erreur de chargement : ${e.message ?: "inconnue"}.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
