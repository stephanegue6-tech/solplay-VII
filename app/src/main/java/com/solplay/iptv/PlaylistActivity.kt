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

        // Playlist fournie par l'admin (code saisi ou assignation automatique) :
        // le client peut ouvrir "Modifier" pour renommer sa playlist, mais le
        // lien M3U / les identifiants Xtream fournis par l'admin doivent rester
        // masqués (points, comme un mot de passe) et non modifiables.
        if (playlist.fromCode != null) {
            maskSensitiveField(binding.etPlaylistUrl)
            maskSensitiveField(binding.etXtreamServer)
            maskSensitiveField(binding.etXtreamUsername)
            maskSensitiveField(binding.etXtreamPassword)
            binding.rbM3u.isEnabled = false
            binding.rbXtream.isEnabled = false
        }
    }

    /** Masque visuellement le contenu d'un champ (comme un mot de passe) et empêche sa modification. */
    private fun maskSensitiveField(editText: android.widget.EditText) {
        editText.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        editText.keyListener = null
        editText.isFocusable = false
        editText.isFocusableInTouchMode = false
        editText.isCursorVisible = false
    }

    /**
     * Construit la playlist saisie dans le formulaire selon le mode choisi.
     * - Mode M3U : utilise directement le lien saisi.
     * - Mode Xtream Codes : construit l'URL standard de l'API Xtream
     *   (utilisée par la plupart des fournisseurs IPTV légaux avec ce protocole).
     */
    private fun buildPlaylist(): SavedPlaylist? {
        val name = binding.etPlaylistName.text.toString().trim().ifEmpty { "Ma playlist" }
        // En mode édition, on conserve le tag d'origine (fromCode) pour ne pas
        // perdre le marquage "fournie par l'admin" (et donc le masquage) après
        // un simple rechargement de la playlist.
        val existingFromCode = editingId?.let { id ->
            PlaylistStore.getAll(this).firstOrNull { it.id == id }?.fromCode
        }
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
                    xtreamPassword = password,
                    fromCode = existingFromCode
                )
            }
        } else {
            val url = binding.etPlaylistUrl.text.toString().trim()
            if (url.isEmpty()) {
                null
            } else {
                // Si le lien collé est en réalité un lien Xtream déguisé
                // (get.php?username=...&password=...), on bascule automatiquement
                // en mode Xtream pour bénéficier des enrichissements (catégories,
                // logos manquants, EPG "en cours", détection d'expiration) -
                // au lieu de rester en mode M3U "brut" qui ne les active jamais.
                val detected = SavedPlaylist.detectXtreamCredentials(url)
                if (detected != null) {
                    val (server, username, password) = detected
                    SavedPlaylist(
                        id = editingId ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        mode = PlaylistMode.XTREAM,
                        xtreamServer = server,
                        xtreamUsername = username,
                        xtreamPassword = password,
                        fromCode = existingFromCode
                    )
                } else {
                    SavedPlaylist(
                        id = editingId ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        mode = PlaylistMode.M3U,
                        m3uUrl = url,
                        fromCode = existingFromCode
                    )
                }
            }
        }
    }

    private fun loadPlaylist(playlist: SavedPlaylist) {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.tvLoadingStatus.visibility = android.view.View.VISIBLE
        binding.btnLoadPlaylist.isEnabled = false
        binding.tvLoadingStatus.text =
            "Connexion au serveur…\nCela peut prendre du temps sur les grosses playlists, merci de patienter."
        lifecycleScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    M3uParser.fetchAndParse(playlist.buildUrl()) { attempt, maxAttempts ->
                        runOnUiThread {
                            binding.tvLoadingStatus.text =
                                "Connexion interrompue, nouvelle tentative ($attempt/$maxAttempts)…"
                        }
                    }
                }
                binding.tvLoadingStatus.text = "${parsed.size} chaînes trouvées, classement en catégories…"
                val channels = XtreamApiClient.enrichChannelsWithCategories(playlist, parsed)
                binding.progressBar.visibility = android.view.View.GONE
                binding.tvLoadingStatus.visibility = android.view.View.GONE
                binding.btnLoadPlaylist.isEnabled = true
                if (channels.isEmpty()) {
                    Toast.makeText(this@PlaylistActivity, "Aucune chaîne trouvée. Vérifiez vos identifiants/lien.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                // On enregistre/actualise la playlist dans la liste locale et on la
                // marque comme "active", puis on stocke les chaînes en mémoire
                // (ChannelRepository) au lieu de les faire passer par l'Intent.
                PlaylistStore.save(this@PlaylistActivity, playlist)
                PlaylistStore.setActiveId(this@PlaylistActivity, playlist.id)
                ChannelCacheStore.save(this@PlaylistActivity, playlist.id, channels)
                ChannelRepository.setChannels(channels)
                startActivity(Intent(this@PlaylistActivity, HomeActivity::class.java))
            } catch (e: PlaylistLoadException) {
                // Message déjà clair et destiné à l'utilisateur (timeout, serveur, réseau...).
                binding.progressBar.visibility = android.view.View.GONE
                binding.tvLoadingStatus.visibility = android.view.View.GONE
                binding.btnLoadPlaylist.isEnabled = true
                Toast.makeText(this@PlaylistActivity, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                binding.progressBar.visibility = android.view.View.GONE
                binding.tvLoadingStatus.visibility = android.view.View.GONE
                binding.btnLoadPlaylist.isEnabled = true
                Toast.makeText(this@PlaylistActivity, "Erreur de chargement : ${e.message ?: "inconnue"}.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
