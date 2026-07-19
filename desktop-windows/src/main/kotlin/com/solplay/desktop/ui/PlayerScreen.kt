package com.solplay.desktop.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.solplay.desktop.core.AsyncImage
import com.solplay.desktop.core.VideoDecoder
import com.solplay.iptv.Channel
import com.solplay.iptv.ChannelRepository
import com.solplay.iptv.DevicePlaylistSync
import com.solplay.iptv.PlaylistStore
import com.solplay.iptv.SavedPlaylist
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CONTROLS_HIDE_DELAY_MS = 5000L

/**
 * Lecteur vidéo desktop, remplace ExoPlayer/Media3 (PlayerActivity côté
 * Android).
 *
 * v18 - changement de moteur : VLC/vlcj remplacé par un décodage FFmpeg
 * "maison" (voir VideoDecoder.kt). VLC était un composant natif "lourd"
 * (SwingPanel) sur lequel Compose ne pouvait pas dessiner par-dessus - d'où
 * les contournements des versions précédentes (panneau "Changer de chaîne"
 * en voisin de mise en page plutôt qu'en calque, barres qui rétrécissaient
 * au lieu de flotter). Le décodeur FFmpeg fournit directement chaque image
 * comme un ImageBitmap Compose classique : l'écran ci-dessous est donc
 * redevenu un écran Compose "normal", avec de VRAIS calques flottants
 * (Box + align) pour les contrôles et le panneau de chaînes, exactement
 * comme n'importe quel autre écran de l'app.
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
    val decoder = remember { VideoDecoder() }
    val scope = rememberCoroutineScope()

    var revokedMessage by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isBuffering by remember { mutableStateOf(true) }

    LaunchedEffect(playlist.id) {
        val tag = playlist.fromCode ?: return@LaunchedEffect
        while (true) {
            if (!DevicePlaylistSync.checkStillAssigned(context, tag)) {
                decoder.stop()
                PlaylistStore.delete(context, playlist.id)
                revokedMessage = "L'accès à cette playlist a été retiré par l'administrateur."
                break
            }
            delay(30_000L)
        }
    }

    LaunchedEffect(revokedMessage) {
        if (revokedMessage != null) {
            delay(3000L)
            onRevoked()
        }
    }

    // Relance le décodage à chaque changement de flux (nouvelle chaîne). Le
    // dernier frame affiché reste celui de l'ancienne chaîne jusqu'à ce que
    // le premier frame du nouveau flux arrive, évitant un flash noir.
    LaunchedEffect(streamUrl) {
        errorMessage = null
        isBuffering = true
        isPlaying = true
        decoder.onFrame = { bitmap ->
            currentFrame = bitmap
            isBuffering = false
        }
        decoder.onError = { msg ->
            errorMessage = msg
            isBuffering = false
        }
        decoder.play(streamUrl, scope)
    }

    DisposableEffect(Unit) {
        onDispose { decoder.stop() }
    }

    fun togglePlayPause() {
        isPlaying = !isPlaying
        decoder.paused = !isPlaying
        // Contrairement à VLC, pas besoin de capturer/convertir un
        // "pausedFrame" séparé : le décodeur s'arrête juste de fournir de
        // nouveaux frames pendant la pause, donc `currentFrame` (déjà à
        // l'écran) reste affiché tel quel automatiquement.
    }

    // Capturée une seule fois à l'entrée sur cet écran (même bouquet/recherche
    // que ce qui était affiché sur HomeScreen au moment du clic).
    val playingList = remember { ChannelRepository.playingList }

    var showChannelPanel by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // Auto-hide des contrôles après 5s d'inactivité (clavier ou souris) -
    // maintenant de VRAIS calques flottants par-dessus la vidéo (Box+align),
    // la vidéo occupe TOUJOURS tout l'écran, contrôles visibles ou non.
    var lastActivity by remember { mutableStateOf(System.currentTimeMillis()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val controlsVisible = !isPlaying || showChannelPanel || (now - lastActivity < CONTROLS_HIDE_DELAY_MS)

    fun pingActivity() {
        lastActivity = System.currentTimeMillis()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(300L)
            now = System.currentTimeMillis()
        }
    }

    val playerFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { playerFocusRequester.requestFocus() }

    fun selectChannel(channel: Channel) {
        onSwitchChannel(channel.streamUrl, channel.name)
        showChannelPanel = false
        query = ""
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerFocusRequester)
            .focusTarget()
            .onPointerEvent(PointerEventType.Move) { pingActivity() }
            .onPointerEvent(PointerEventType.Press) { pingActivity() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                pingActivity()
                when {
                    !showChannelPanel && event.key == Key.Spacebar -> { togglePlayPause(); true }
                    !showChannelPanel && event.key == Key.Escape -> { onBack(); true }
                    else -> false
                }
            }
    ) {
        // Vidéo : occupe TOUJOURS tout l'écran, quels que soient les
        // contrôles/panneaux affichés par-dessus - fini le rétrécissement
        // en voisin de mise en page qu'imposait VLC/SwingPanel.
        currentFrame?.let { frame ->
            Image(
                bitmap = frame,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        if (isBuffering && errorMessage == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(48.dp),
                color = SolPlayColors.Orange
            )
        }

        errorMessage?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("⚠️ Lecture impossible", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(msg, color = SolPlayColors.White60, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    errorMessage = null
                    isBuffering = true
                    decoder.play(streamUrl, scope)
                }) { Text("Réessayer") }
            }
        }

        revokedMessage?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
            ) {
                Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
        }

        // Barre du haut : VRAI calque flottant par-dessus la vidéo (avant :
        // rétrécissait la zone vidéo faute de pouvoir superposer sur VLC).
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(SolPlayColors.SurfaceDark.copy(alpha = 0.85f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Retour", tint = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))

                if (playingList.size > 1) {
                    Surface(
                        color = SolPlayColors.Orange,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable { showChannelPanel = !showChannelPanel; pingActivity() }
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
        }

        // Barre du bas (play/pause) : idem, VRAI calque flottant.
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(SolPlayColors.SurfaceDark.copy(alpha = 0.85f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { togglePlayPause(); pingActivity() }) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Lecture",
                        tint = SolPlayColors.Orange,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Panneau "Changer de chaîne" : VRAI calque flottant à droite,
        // par-dessus la vidéo (avant : voisin de Row qui rétrécissait la
        // vidéo). Glisse depuis la droite pour un rendu plus soigné.
        AnimatedVisibility(
            visible = showChannelPanel,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            ChannelSwitchPanel(
                playingList = playingList,
                currentStreamUrl = streamUrl,
                query = query,
                onQueryChange = { query = it; pingActivity() },
                onSelect = ::selectChannel
            )
        }
    }
}

/** Panneau "Changer de chaîne" - maintenant un vrai calque flottant par-dessus la vidéo (voir PlayerScreen). */
@Composable
private fun ChannelSwitchPanel(
    playingList: List<Channel>,
    currentStreamUrl: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (Channel) -> Unit
) {
    val filtered = remember(playingList, query) {
        if (query.isBlank()) playingList else playingList.filter { it.name.contains(query, ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    val searchFocusRequester = remember { FocusRequester() }
    val listFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { searchFocusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            // Largeur PROPORTIONNELLE à la fenêtre (28%), plafonnée entre
            // 260dp et 380dp : reste cohérente à toute taille d'écran.
            .fillMaxWidth(0.28f)
            .widthIn(min = 260.dp, max = 380.dp)
            .background(SolPlayColors.SurfaceDark)
    ) {
        Surface(color = SolPlayColors.OrangeDark, modifier = Modifier.fillMaxWidth()) {
            Text("Changer de chaîne", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Rechercher une chaîne...", color = SolPlayColors.White60) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = SolPlayColors.Gray,
                unfocusedContainerColor = SolPlayColors.Gray,
                focusedBorderColor = SolPlayColors.White60,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .focusRequester(searchFocusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            scope.launch { listState.scrollToItem(0) }
                            listFocusRequester.requestFocus()
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            filtered.getOrNull(0)?.let { onSelect(it) }
                            true
                        }
                        else -> false
                    }
                }
        )

        if (filtered.isEmpty()) {
            Text("Aucune chaîne trouvée.", color = SolPlayColors.White60, modifier = Modifier.padding(16.dp))
        } else {
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
                            Key.Enter, Key.NumPadEnter -> {
                                filtered.getOrNull(index)?.let { onSelect(it) }
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                items(filtered, key = { it.streamUrl }) { channel ->
                    val isActive = channel.streamUrl == currentStreamUrl
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(channel) }
                            .background(if (isActive) SolPlayColors.Gray else Color.Transparent)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(SolPlayColors.Gray)) {
                            AsyncImage(channel.logoUrl, channel.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
