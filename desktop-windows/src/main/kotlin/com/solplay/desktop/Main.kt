package com.solplay.desktop

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import com.solplay.desktop.ui.*
import com.solplay.iptv.Channel
import com.solplay.iptv.SavedPlaylist
import com.solplay.iptv.TrialManager

/** Écran actuellement affiché - équivalent desktop de la navigation entre Activities Android. */
sealed class Screen {
    object Splash : Screen()
    object VlcMissing : Screen()
    object License : Screen()
    object Connect : Screen()
    data class Home(val playlist: SavedPlaylist) : Screen()
    data class Player(val playlist: SavedPlaylist, val streamUrl: String, val title: String) : Screen()
    data class EpgGrid(val playlist: SavedPlaylist, val channels: List<Channel>) : Screen()
}

fun main() = application {
    val ctx = Context.APP // stockage local (%APPDATA%\SolPlay), voir ContextShim.kt
    var screen by remember { mutableStateOf<Screen>(Screen.Splash) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "SolPlay",
        state = WindowState(size = DpSize(1280.dp, 800.dp))
    ) {
        when (val s = screen) {
            is Screen.Splash -> SplashScreen(
                onDone = { vlcAvailable ->
                    screen = if (!vlcAvailable) {
                        Screen.VlcMissing
                    } else if (TrialManager.canAccessApp(ctx)) {
                        Screen.Connect
                    } else {
                        Screen.License
                    }
                }
            )
            is Screen.VlcMissing -> VlcMissingScreen(
                onVlcFound = {
                    screen = if (TrialManager.canAccessApp(ctx)) Screen.Connect else Screen.License
                }
            )
            is Screen.License -> LicenseScreen(
                context = ctx,
                onLicensed = { screen = Screen.Connect }
            )
            is Screen.Connect -> ConnectScreen(
                context = ctx,
                onConnected = { playlist -> screen = Screen.Home(playlist) }
            )
            is Screen.Home -> HomeScreen(
                context = ctx,
                playlist = s.playlist,
                onPlay = { url, title -> screen = Screen.Player(s.playlist, url, title) },
                onOpenEpgGrid = { liveChannels -> screen = Screen.EpgGrid(s.playlist, liveChannels) },
                onDisconnect = { screen = Screen.Connect }
            )
            is Screen.Player -> PlayerScreen(
                context = ctx,
                playlist = s.playlist,
                streamUrl = s.streamUrl,
                title = s.title,
                onBack = { screen = Screen.Home(s.playlist) },
                onRevoked = { screen = Screen.Connect }
            )
            is Screen.EpgGrid -> EpgGridScreen(
                channels = s.channels,
                playlist = s.playlist,
                onBack = { screen = Screen.Home(s.playlist) }
            )
        }
    }
}
