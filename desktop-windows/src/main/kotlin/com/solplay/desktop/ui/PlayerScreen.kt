package com.solplay.desktop.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import com.solplay.iptv.DevicePlaylistSync
import com.solplay.iptv.PlaylistStore
import com.solplay.iptv.SavedPlaylist
import kotlinx.coroutines.delay
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

/**
 * Lecteur vidéo desktop, remplace ExoPlayer/Media3 (PlayerActivity côté
 * Android) - ExoPlayer est une librairie Android uniquement, indisponible
 * sur JVM desktop. VLC (via vlcj) est utilisé à la place : mêmes formats
 * pris en charge (HLS, TS, MP4...), donc compatible avec exactement les
 * mêmes flux IPTV.
 *
 * PRÉREQUIS IMPORTANT : contrairement à ExoPlayer qui est autonome, vlcj
 * pilote une installation LOCALE de VLC Media Player. L'utilisateur final
 * doit avoir VLC installé sur son PC Windows (gratuit :
 * https://www.videolan.org/vlc/download-windows.html) - sinon cet écran
 * affichera une erreur au lancement de la lecture. C'est la même exigence
 * que la quasi-totalité des lecteurs IPTV desktop basés sur VLC.
 *
 * Revérification pendant la lecture : équivalent du contrôle périodique de
 * PlayerActivity côté Android. Nécessaire séparément de celui de
 * HomeScreen, car cet écran remplace HomeScreen dans la fenêtre pendant la
 * lecture (HomeScreen n'est alors plus composé, donc sa propre boucle est
 * suspendue) - sans ceci, un flux déjà lancé continuerait indéfiniment même
 * après une désactivation par l'admin, tant que l'utilisateur ne revient
 * pas manuellement sur l'écran d'accueil.
 */
@Composable
fun PlayerScreen(
    context: Context,
    playlist: SavedPlaylist,
    streamUrl: String,
    title: String,
    onBack: () -> Unit,
    onRevoked: () -> Unit
) {
    val mediaPlayerComponent = remember { EmbeddedMediaPlayerComponent() }
    var revokedMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playlist.id) {
        val tag = playlist.fromCode ?: return@LaunchedEffect
        while (true) {
            delay(120_000L) // même intervalle que PlayerActivity côté Android
            if (!DevicePlaylistSync.checkStillAssigned(context, tag)) {
                mediaPlayerComponent.mediaPlayer().controls().stop()
                PlaylistStore.delete(context, playlist.id)
                revokedMessage = "L'accès à cette playlist a été retiré par l'administrateur."
                break
            }
        }
    }

    LaunchedEffect(revokedMessage) {
        if (revokedMessage != null) {
            delay(3000L)
            onRevoked()
        }
    }

    // IMPORTANT : ne pas appeler .play() dès la composition. SwingPanel
    // rattache le composant vlcj à la fenêtre native de façon asynchrone
    // (après la passe de composition Compose) - si .play() est appelé
    // avant que ce rattachement soit terminé, vlcj lève l'erreur "the
    // video surface component must be displayable" car il a besoin d'un
    // handle de fenêtre natif (HWND côté Windows) déjà valide pour
    // attacher la sortie vidéo. On attend donc que le composant soit
    // effectivement "displayable" avant de démarrer la lecture.
    LaunchedEffect(streamUrl) {
        var attempts = 0
        while (!mediaPlayerComponent.isDisplayable && attempts < 150) { // ~3s max
            delay(20L)
            attempts++
        }
        mediaPlayerComponent.mediaPlayer().media().play(streamUrl)
    }

    DisposableEffect(streamUrl) {
        onDispose {
            mediaPlayerComponent.mediaPlayer().controls().stop()
        }
    }

    Column(Modifier.fillMaxSize()) {
        revokedMessage?.let { msg ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
            }
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        SwingPanel(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { mediaPlayerComponent }
        )
    }
}
