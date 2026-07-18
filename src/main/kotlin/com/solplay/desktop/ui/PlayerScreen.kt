package com.solplay.desktop.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.solplay.desktop.core.AsyncImage
import com.solplay.iptv.Channel
import com.solplay.iptv.ChannelRepository
import com.solplay.iptv.DevicePlaylistSync
import com.solplay.iptv.PlaylistStore
import com.solplay.iptv.SavedPlaylist
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 *
 * Panneau "☰ Chaînes" : équivalent du panneau latéral de PlayerActivity
 * côté Android (channelListPanel + etSideSearch + recyclerSideChannels).
 * Utilise ChannelRepository.playingList, déposée par HomeScreen juste avant
 * d'ouvrir cet écran (déjà prévu dans channelRepository.kt mais jamais
 * alimentée côté desktop jusqu'ici) - reste TRANSPARENT/semi-sombre par
 * dessus la vidéo (pas un fond blanc opaque), avec les mêmes couleurs que
 * l'app Android (voir SolPlayColors dans Theme.kt), et une liste navigable
 * aux 4 flèches comme partout ailleurs dans l'app.
 */
@Composable
fun PlayerScreen(
    context: Context,
    playlist: SavedPlaylist,
    streamUrl: String,
    title: String,
    onBack: () -> Unit,
    onSwitchChannel: (streamUrl: String, title: String) -> Unit,
    onRevoked: () -> Unit
) {
    val mediaPlayerComponent = remember { EmbeddedMediaPlayerComponent() }
    var revokedMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playlist.id) {
        val tag = playlist.fromCode ?: return@LaunchedEffect
        while (true) {
            // Vérification IMMÉDIATE à chaque tour (pas de delay avant le
            // premier contrôle) : aligné sur le correctif appliqué côté
            // Android (PlayerActivity), qui vérifiait auparavant seulement
            // après 2 minutes, laissant passer sans coupure toute lecture
            // démarrée juste après une désactivation/suppression admin.
            if (!DevicePlaylistSync.checkStillAssigned(context, tag)) {
                mediaPlayerComponent.mediaPlayer().controls().stop()
                PlaylistStore.delete(context, playlist.id)
                revokedMessage = "L'accès à cette playlist a été retiré par l'administrateur."
                break
            }
            delay(30_000L) // même intervalle que PlayerActivity côté Android
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
    //
    // setScale(0f) juste après : force VLC à recalculer lui-même le
    // cadrage/zoom de la vidéo par rapport à la taille RÉELLE du panneau
    // une fois affiché, au lieu de garder la géométrie calculée au tout
    // premier instant (souvent 0x0 ou une taille provisoire avant que
    // SwingPanel ait fini de se dimensionner) - c'est ce qui causait
    // l'image décalée/rognée en bas d'écran signalée.
    var isPlaying by remember { mutableStateOf(true) }
    LaunchedEffect(streamUrl) {
        var attempts = 0
        while (!mediaPlayerComponent.isDisplayable && attempts < 150) { // ~3s max
            delay(20L)
            attempts++
        }
        mediaPlayerComponent.mediaPlayer().media().play(streamUrl)
        isPlaying = true
        delay(300L) // laisse VLC ouvrir le flux avant de fixer le cadrage
        mediaPlayerComponent.mediaPlayer().video().setScale(0f) // 0 = ajustement automatique
        mediaPlayerComponent.mediaPlayer().video().setAspectRatio(null)
    }

    DisposableEffect(streamUrl) {
        onDispose {
            mediaPlayerComponent.mediaPlayer().controls().stop()
        }
    }

    // Capturée une seule fois à l'entrée sur cet écran (même bouquet/recherche
    // que ce qui était affiché sur HomeScreen au moment du clic) : ne doit pas
    // changer pendant qu'on regarde, y compris quand on change de chaîne
    // depuis ce panneau (onSwitchChannel ne recrée pas cet écran, voir
    // Main.kt : replaceWith au lieu de navigateTo, pour ne pas empiler le
    // back-stack à chaque changement de chaîne).
    val playingList = remember { ChannelRepository.playingList }

    var showChannelPanel by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val playerFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { playerFocusRequester.requestFocus() }

    fun togglePlayPause() {
        val mp = mediaPlayerComponent.mediaPlayer()
        if (isPlaying) mp.controls().pause() else mp.controls().play()
        isPlaying = !isPlaying
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(playerFocusRequester)
            .focusTarget()
            .onPreviewKeyEvent { event ->
                // Espace = lecture/pause, comme la quasi-totalité des lecteurs
                // vidéo (VLC, YouTube...) - raccourci demandé en plus du
                // bouton, pour ne pas dépendre uniquement de la souris.
                // IMPORTANT : désactivé tant que le panneau "Changer de
                // chaîne" est ouvert, sinon taper un espace dans la
                // recherche (ex: "TF1 HD") coupait la lecture au lieu
                // d'écrire le caractère.
                if (!showChannelPanel && event.type == KeyEventType.KeyDown && event.key == Key.Spacebar) {
                    togglePlayPause()
                    true
                } else {
                    false
                }
            }
    ) {
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
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))

                // Bouton "☰ Chaînes" : ouvre/ferme le panneau de changement de
                // chaîne sans quitter le lecteur. Masqué s'il n'y a rien
                // d'autre à proposer (même règle que côté Android :
                // sideChannels.size > 1).
                if (playingList.size > 1) {
                    Surface(
                        color = SolPlayColors.Orange,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable { showChannelPanel = !showChannelPanel }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Filled.Menu, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Chaînes", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            SwingPanel(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { mediaPlayerComponent }
            )

            // Barre de contrôle : toujours dans le flux normal de la Column
            // (jamais superposée à la vidéo ni dépendante d'une taille fixe
            // calculée à l'avance), donc jamais coupée/masquée par le
            // redimensionnement de la fenêtre ou de la vidéo elle-même -
            // c'est ce qui manquait pour pouvoir mettre en pause.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(SolPlayColors.SurfaceDark)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { togglePlayPause() }) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Lecture",
                        tint = SolPlayColors.Orange,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isPlaying) "Lecture en cours — Espace ou clic pour mettre en pause"
                    else "En pause — Espace ou clic pour reprendre",
                    color = SolPlayColors.White60,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        // Panneau latéral "Changer de chaîne" : reste TRANSPARENT/semi-sombre
        // (pas un fond blanc opaque) puisqu'il se superpose à la vidéo en
        // cours - mêmes couleurs que le panneau équivalent de l'app Android
        // (solplay_panel_overlay / solplay_panel_header_overlay /
        // solplay_search_bg_overlay / solplay_white_60, voir Theme.kt).
        if (showChannelPanel) {
            val filtered = remember(playingList, query) {
                if (query.isBlank()) playingList
                else playingList.filter { it.name.contains(query, ignoreCase = true) }
            }
            val listState = rememberLazyListState()
            val searchFocusRequester = remember { FocusRequester() }
            val listFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { searchFocusRequester.requestFocus() }

            fun selectChannel(channel: Channel) {
                onSwitchChannel(channel.streamUrl, channel.name)
                showChannelPanel = false
                query = ""
            }

            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(320.dp)
                    .background(SolPlayColors.PanelOverlay)
            ) {
                Surface(color = SolPlayColors.PanelHeaderOverlay, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Changer de chaîne",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Rechercher une chaîne...", color = SolPlayColors.White60) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = SolPlayColors.SearchOverlayBg,
                        unfocusedContainerColor = SolPlayColors.SearchOverlayBg,
                        focusedBorderColor = SolPlayColors.White60,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                        .focusRequester(searchFocusRequester)
                        .onPreviewKeyEvent { event ->
                            // Depuis le champ de recherche : Bas descend dans
                            // la liste des résultats, Entrée ouvre directement
                            // le premier résultat filtré - pas besoin de sortir
                            // du clavier pour naviguer vers la liste.
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    scope.launch { listState.scrollToItem(0) }
                                    listFocusRequester.requestFocus()
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    filtered.getOrNull(0)?.let { selectChannel(it) }
                                    true
                                }
                                else -> false
                            }
                        }
                )

                if (filtered.isEmpty()) {
                    Text(
                        "Aucune chaîne trouvée.",
                        color = SolPlayColors.White60,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    // Liste navigable aux 4 flèches, comme toutes les autres
                    // listes de l'app (même mécanique que HomeScreen/EpgGridScreen :
                    // FocusRequester + onPreviewKeyEvent). Gauche/droite sautent
                    // de 10 éléments (liste à une seule colonne, pas de voisin
                    // latéral évident) au lieu de rester sans effet.
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .focusRequester(listFocusRequester)
                            .focusTarget()
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val index = listState.firstVisibleItemIndex
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        scope.launch { listState.animateScrollToItem((index + 1).coerceAtMost(filtered.lastIndex)) }
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        scope.launch { listState.animateScrollToItem((index - 1).coerceAtLeast(0)) }
                                        true
                                    }
                                    Key.DirectionRight, Key.PageDown -> {
                                        scope.launch { listState.animateScrollToItem((index + 10).coerceAtMost(filtered.lastIndex)) }
                                        true
                                    }
                                    Key.DirectionLeft, Key.PageUp -> {
                                        scope.launch { listState.animateScrollToItem((index - 10).coerceAtLeast(0)) }
                                        true
                                    }
                                    Key.Enter, Key.NumPadEnter -> {
                                        filtered.getOrNull(index)?.let { selectChannel(it) }
                                        true
                                    }
                                    else -> false
                                }
                            }
                    ) {
                        items(filtered, key = { it.streamUrl }) { channel ->
                            val isActive = channel.streamUrl == streamUrl
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectChannel(channel) }
                                    .background(if (isActive) SolPlayColors.SearchOverlayBg else Color.Transparent)
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(SolPlayColors.SearchOverlayBg)) {
                                    AsyncImage(
                                        channel.logoUrl,
                                        channel.name,
                                        Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        channel.name,
                                        color = if (isActive) SolPlayColors.Orange else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    channel.groupTitle?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, color = SolPlayColors.White60, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
