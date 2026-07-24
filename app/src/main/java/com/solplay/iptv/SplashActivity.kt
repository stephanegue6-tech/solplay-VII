package com.solplay.iptv

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplashActivity : AppCompatActivity() {

    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        TrialManager.ensureFirstLaunchRecorded(this)
        requestNotificationPermissionIfNeeded()
        // Best effort, ne bloque jamais le démarrage : permet au panel admin
        // de cibler CET appareil précis pour une notification (voir
        // FcmTokenSync / SolPlayFirebaseMessagingService).
        FcmTokenSync.syncTokenIfNeeded(this)

        // Lancée en parallèle du délai d'affichage du splash : ne retarde
        // jamais le démarrage de l'app si le réseau est lent ou absent.
        checkForUpdate()

        Handler(Looper.getMainLooper()).postDelayed({
            goToNextScreen()
        }, 1500)
    }

    /**
     * Depuis Android 13 (API 33), afficher une notification nécessite une
     * permission explicitement accordée par l'utilisateur (comme pour la
     * caméra ou la localisation) - sans quoi ni le rappel horaire de temps
     * restant, ni les messages envoyés par l'admin ne s'afficheraient jamais,
     * silencieusement. Demandée une seule fois, dès le premier lancement.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }
    }

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val update = UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
            if (update != null && !isFinishing) {
                showUpdateDialog(update)
            }
        }
    }

    private fun showUpdateDialog(update: UpdateChecker.UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title))
            .setMessage(getString(R.string.update_available_message, update.versionName))
            .setCancelable(false)
            .setPositiveButton(R.string.update_download) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl))
                startActivity(intent)
                goToNextScreen()
            }
            .setNegativeButton(R.string.update_later) { _, _ ->
                goToNextScreen()
            }
            .show()
    }

    private fun goToNextScreen() {
        if (navigated) return
        navigated = true

        if (!TrialManager.canAccessApp(this)) {
            startActivity(Intent(this, LicenseActivity::class.java))
            finish()
            return
        }

        // ChannelCacheStore.load() est une fonction suspend qui bascule sur
        // Dispatchers.IO : sur une grosse playlist (10 000+ entrées), lire et
        // parser ce cache peut prendre plusieurs centaines de ms à quelques
        // secondes, largement de quoi geler l'appli (voire déclencher un ANR)
        // si jamais fait sur le thread principal - d'où le lifecycleScope.launch.
        lifecycleScope.launch {
            // Connexion automatique pour un utilisateur qui ne sait pas/ne
            // peut pas se connecter lui-même : dès l'OUVERTURE de l'app (pas
            // besoin d'aller chercher l'écran "Mes playlists"), on vérifie si
            // l'admin a assigné/changé un compte pour cette clé appareil
            // (Firebase "device_playlists") et on s'y connecte directement -
            // téléchargement des chaînes + activation + ouverture de
            // l'accueil - sans qu'aucune action ne soit requise de sa part.
            // Se déclenche uniquement quand il y a une assignation NOUVELLE
            // ou DIFFÉRENTE de celle déjà active (jamais à chaque lancement
            // une fois connecté, pour ne pas retélécharger inutilement).
            DevicePlaylistSync.sync(this@SplashActivity)

            val activeId = PlaylistStore.getActiveId(this@SplashActivity)
            val allPlaylists = PlaylistStore.getAll(this@SplashActivity)
            val deviceAssigned = allPlaylists.filter { it.fromCode?.startsWith("device:") == true }
            val needsAutoConnect = deviceAssigned.size == 1 && deviceAssigned.first().id != activeId

            if (needsAutoConnect) {
                findViewById<android.widget.TextView>(R.id.tvSplashStatus)?.visibility = android.view.View.VISIBLE
                val playlist = deviceAssigned.first()
                val channels = try {
                    if (playlist.extractXtreamCredentials() != null) {
                        XtreamApiClient.fetchAllChannelsDirect(playlist).channels
                    } else {
                        val parsed = withContext(Dispatchers.IO) { M3uParser.fetchAndParse(playlist.buildUrl()) }
                        XtreamApiClient.enrichChannelsWithCategories(playlist, parsed)
                    }
                } catch (e: Exception) {
                    emptyList()
                }

                if (channels.isNotEmpty()) {
                    PlaylistStore.setActiveId(this@SplashActivity, playlist.id)
                    ChannelCacheStore.save(this@SplashActivity, playlist.id, channels)
                    ChannelRepository.setChannels(channels)
                    startActivity(Intent(this@SplashActivity, HomeActivity::class.java))
                    finish()
                    return@launch
                }
                // Échec réseau/serveur sur cette tentative de connexion
                // automatique : on retombe simplement sur le comportement
                // habituel ci-dessous (cache existant s'il y en a un), sans
                // jamais bloquer l'utilisateur sur une erreur ici. La
                // prochaine ouverture de l'app retentera automatiquement.
            }

            // Comme les autres lecteurs IPTV, on "reste connecté" : si une
            // playlist active a déjà été chargée avec succès, on rouvre
            // directement sur l'accueil avec les chaînes en cache (instantané),
            // au lieu de retélécharger toute la playlist et de repasser par
            // l'écran de connexion à chaque lancement. HomeActivity se charge
            // ensuite de rafraîchir en arrière-plan si le cache commence à dater.
            val activePlaylist = activeId?.let { id -> allPlaylists.firstOrNull { it.id == id } }
            val cached = activePlaylist?.let { ChannelCacheStore.load(this@SplashActivity, it.id) }

            val next = if (activePlaylist != null && cached != null) {
                ChannelRepository.setChannels(cached)
                HomeActivity::class.java
            } else {
                PlaylistActivity::class.java
            }
            startActivity(Intent(this@SplashActivity, next))
            finish()
        }
    }
}
