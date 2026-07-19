package com.solplay.desktop

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.Notification
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
import com.solplay.desktop.ui.*
import com.solplay.iptv.Channel
import com.solplay.iptv.SavedPlaylist
import com.solplay.iptv.TrialManager
import kotlinx.coroutines.delay

/** Écran actuellement affiché - équivalent desktop de la navigation entre Activities Android. */
sealed class Screen {
    object Splash : Screen()
    object License : Screen()
    object Connect : Screen()
    data class Home(val playlist: SavedPlaylist) : Screen()
    data class Player(val playlist: SavedPlaylist, val streamUrl: String, val title: String) : Screen()
    data class EpgGrid(val playlist: SavedPlaylist, val channels: List<Channel>) : Screen()
}

fun main() {
    // Le moteur de rendu Compose (Skia/Skiko) utilise Direct3D par défaut
    // sur Windows, qui peut entrer en conflit avec le rendu vidéo GPU natif
    // de VLC (deux composants distincts se disputant le même pipeline
    // graphique) - un symptôme documenté de ce conflit est un écran vidéo
    // qui clignote noir/blanc ou affiche une image corrompue (voir aussi
    // PlayerScreen.kt, qui désactive côté VLC le décodage matériel pour la
    // même raison). Basculer Skiko sur OpenGL, un pipeline plus permissif
    // vis-à-vis du partage GPU avec d'autres composants natifs, résout ce
    // conflit dans la majorité des cas connus. DOIT être défini avant tout
    // appel à du code Compose, d'où sa présence tout en haut de main().
    System.setProperty("skiko.renderApi", "OPENGL")

    application {
    val ctx = Context.APP // stockage local (%APPDATA%\SolPlay), voir ContextShim.kt

    // Pile de navigation façon back-stack Android, au lieu d'un simple
    // "screen courant" écrasé à chaque navigation : permet un retour
    // fiable depuis n'importe quel écran (bouton ← ET raccourcis clavier
    // Retour arrière / Alt+Gauche ci-dessous), plutôt que de dépendre d'un
    // onBack codé en dur écran par écran.
    val backStack = remember { mutableStateListOf<Screen>(Screen.Splash) }
    val screen by remember { derivedStateOf { backStack.last() } }

    fun navigateTo(next: Screen) {
        backStack.add(next)
    }

    /** Remplace l'écran courant au lieu d'empiler (utilisé après Splash/License/Connect : on ne veut pas y revenir avec "Retour"). */
    fun replaceWith(next: Screen) {
        backStack[backStack.lastIndex] = next
    }

    fun goBack(): Boolean {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
            return true
        }
        return false
    }

    val focusRequester = remember { FocusRequester() }

    // ------------------------------------------------------------------
    // Icône système (system tray) + rappel du temps restant.
    //
    // Équivalent desktop de RemainingTimeReminderWorker côté Android (WorkManager,
    // notification toutes les heures rappelant le temps restant sur l'essai/la
    // licence) : jusqu'ici entièrement absent côté desktop, malgré la présence
    // d'une icône .ico/.png dans les ressources. On reproduit le même
    // comportement, adapté à Windows :
    // - une icône reste dans la zone de notification système tant que l'app tourne,
    // - toutes les heures (même intervalle que le Worker Android), une notification
    //   Windows rappelle le temps restant sur l'essai gratuit ou la licence Pro,
    // - le message et les conditions d'affichage sont identiques (rien à rappeler
    //   si ni licencié ni en essai : l'utilisateur est de toute façon bloqué sur
    //   l'écran d'expiration à ce moment-là).
    val trayState = rememberTrayState()

    LaunchedEffect(Unit) {
        while (true) {
            delay(60 * 60 * 1000L) // toutes les heures, comme le Worker Android

            // Se resynchronise avec Firebase si possible (au cas où l'admin
            // aurait changé/renouvelé la licence depuis le dernier lancement).
            try {
                TrialManager.checkOnlineLicense(ctx)
            } catch (_: Exception) {
                // Pas de réseau : on continue avec les données locales connues.
            }

            val licensed = TrialManager.isLicensed(ctx)
            val trialActive = TrialManager.isTrialActive(ctx)
            if (!licensed && !trialActive) continue // rien à rappeler

            val message = if (licensed) {
                val remaining = TrialManager.getRemainingLicenseMillis(ctx)
                if (remaining == Long.MAX_VALUE) {
                    "Votre abonnement SolPlay Pro est actif (sans expiration)."
                } else {
                    "Il vous reste ${TrialManager.formatDuration(remaining)} sur votre abonnement SolPlay Pro."
                }
            } else {
                val remaining = TrialManager.getRemainingTrialMillis(ctx)
                "Il vous reste ${TrialManager.formatDuration(remaining)} sur votre essai gratuit SolPlay."
            }

            trayState.sendNotification(Notification("SolPlay — Temps restant", message, Notification.Type.Info))
        }
    }

    Tray(
        icon = painterResource("solplay_icon.png"),
        state = trayState,
        tooltip = "SolPlay",
        menu = {
            Item(
                "Vérifier le temps restant",
                onClick = {
                    val licensed = TrialManager.isLicensed(ctx)
                    val trialActive = TrialManager.isTrialActive(ctx)
                    val message = when {
                        licensed -> {
                            val remaining = TrialManager.getRemainingLicenseMillis(ctx)
                            if (remaining == Long.MAX_VALUE) "Abonnement SolPlay Pro actif (sans expiration)."
                            else "Il vous reste ${TrialManager.formatDuration(remaining)} sur votre abonnement SolPlay Pro."
                        }
                        trialActive -> "Il vous reste ${TrialManager.formatDuration(TrialManager.getRemainingTrialMillis(ctx))} sur votre essai gratuit."
                        else -> "Essai et licence expirés."
                    }
                    trayState.sendNotification(Notification("SolPlay — Temps restant", message, Notification.Type.Info))
                }
            )
            Item("Quitter SolPlay", onClick = ::exitApplication)
        }
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "SolPlay",
        // Démarre en plein écran (maximisé) plutôt qu'à une taille fixe de
        // 1280x800 : s'adapte automatiquement à N'IMPORTE QUELLE résolution
        // d'écran (petit portable 1366x768, moniteur 1080p, 4K...) au lieu
        // d'imposer une fenêtre qui peut déborder ou laisser trop d'espace
        // vide selon l'écran de l'utilisateur. DpSize(1280,800) reste
        // utilisé comme taille de repli si jamais l'utilisateur démaximise
        // la fenêtre.
        state = WindowState(size = DpSize(1280.dp, 800.dp), placement = WindowPlacement.Maximized),
        icon = painterResource("solplay_icon.png")
    ) {
        // Taille minimale de la fenêtre : protège la mise en page (grille
        // films/séries, panneau "Changer de chaîne", etc.) si l'utilisateur
        // démaximise puis rétrécit manuellement la fenêtre en dessous d'une
        // taille utilisable.
        LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(960, 600) }

        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusTarget()
                .onPreviewKeyEvent { event ->
                    // Raccourcis clavier standard pour "revenir en arrière" sur
                    // desktop (il n'existe pas de bouton back physique comme sur
                    // Android) : touche "Retour arrière" seule, ou Alt+Gauche
                    // (convention Windows classique, ex. Explorateur de fichiers).
                    if (event.type == KeyEventType.KeyUp &&
                        (event.key == Key.Backspace || (event.key == Key.DirectionLeft && event.isAltPressed))
                    ) {
                        goBack()
                    } else {
                        false
                    }
                }
        ) {
            SolPlayTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                when (val s = screen) {
                    is Screen.Splash -> SplashScreen(
                        onDone = {
                            replaceWith(if (TrialManager.canAccessApp(ctx)) Screen.Connect else Screen.License)
                        }
                    )
                    is Screen.License -> LicenseScreen(
                        context = ctx,
                        onLicensed = { replaceWith(Screen.Connect) }
                    )
                    is Screen.Connect -> ConnectScreen(
                        context = ctx,
                        onConnected = { playlist -> navigateTo(Screen.Home(playlist)) }
                    )
                    is Screen.Home -> HomeScreen(
                        context = ctx,
                        playlist = s.playlist,
                        onPlay = { url, title -> navigateTo(Screen.Player(s.playlist, url, title)) },
                        onOpenEpgGrid = { liveChannels -> navigateTo(Screen.EpgGrid(s.playlist, liveChannels)) },
                        onDisconnect = {
                            // Retour à Connect : on vide toute la pile au-delà pour
                            // qu'un "Retour" ultérieur ne ramène pas vers une
                            // session de playlist qui vient d'être quittée.
                            while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                            replaceWith(Screen.Connect)
                        }
                    )
                    is Screen.Player -> PlayerScreen(
                        context = ctx,
                        playlist = s.playlist,
                        streamUrl = s.streamUrl,
                        title = s.title,
                        onBack = { goBack() },
                        onSwitchChannel = { url, name ->
                            // Remplace l'écran courant au lieu d'empiler : changer de
                            // chaîne depuis le panneau "☰ Chaînes" ne doit pas faire
                            // grandir le back-stack (sinon "Retour" ferait défiler
                            // l'historique des chaînes une par une au lieu de
                            // renvoyer directement à l'accueil).
                            replaceWith(Screen.Player(s.playlist, url, name))
                        },
                        onRevoked = {
                            while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                            replaceWith(Screen.Connect)
                        }
                    )
                    is Screen.EpgGrid -> EpgGridScreen(
                        channels = s.channels,
                        playlist = s.playlist,
                        onBack = { goBack() }
                    )
                }
                }
            }
        }
    }
}
}
