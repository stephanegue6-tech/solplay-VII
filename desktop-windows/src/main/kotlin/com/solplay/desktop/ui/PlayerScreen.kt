package com.solplay.desktop.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
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
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import com.solplay.iptv.Channel
import com.solplay.iptv.ChannelRepository
import com.solplay.iptv.DevicePlaylistSync
import com.solplay.iptv.PlaylistStore
import com.solplay.iptv.SavedPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

private const val CONTROLS_HIDE_DELAY_MS = 5000L

/**
 * Lecteur vidéo desktop, remplace ExoPlayer/Media3 (PlayerActivity côté
 * Android). VLC (via vlcj) pilote une installation LOCALE de VLC Media
 * Player - voir VlcCheck.kt/VlcMissingScreen.kt pour la vérification faite
 * avant d'arriver ici.
 *
 * IMPORTANT - limite de Compose Desktop qui façonne tout cet écran :
 * EmbeddedMediaPlayerComponent est un composant "lourd" (natif, fenêtre
 * système), inséré via SwingPanel. Compose Desktop ne sait PAS empiler du
 * contenu Compose "léger" par-dessus un composant lourd - tout composant
 * Compose positionné en superposition (Box.align par-dessus un SwingPanel)
 * finit invisible, car le composant lourd est toujours peint au-dessus,
 * quel que soit l'ordre déclaré. C'est ce qui causait le panneau "Changer
 * de chaîne" invisible et l'écran figé en pause qui ne montrait rien.
 *
 * Cet écran évite donc TOUTE superposition sur la vidéo :
 * - Le panneau "Changer de chaîne" n'est plus un calque flottant par-dessus
 *   la vidéo : c'est un vrai voisin de mise en page (Row), la vidéo
 *   rétrécit pour lui faire de la place au lieu d'être recouverte.
 * - En pause, le composant vidéo natif est totalement retiré et remplacé
 *   par une image Compose classique (capture du dernier finaqe via
 *   mediaPlayer().snapshots()) - plus de dépendance au rendu natif de VLC
 *   en pause, qui pouvait afficher un écran noir sur certains flux HLS.
 * - Les barres haut/bas "auto-hide" ne flottent pas non plus par-dessus la
 *   vidéo : elles rétrécissent à hauteur 0 (au lieu de disparaître en
 *   transparence par-dessus), et la vidéo grandit pour occuper l'espace
 *   libéré. Le résultat visuel est proche (plein écran après 5s
 *   d'inactivité) sans dépendre d'un empilement non supporté.
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
    var isPlaying by remember { mutableStateOf(true) }
    var pausedFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(playlist.id) {
        val tag = playlist.fromCode ?: return@LaunchedEffect
        while (true) {
            if (!DevicePlaylistSync.checkStillAssigned(context, tag)) {
                mediaPlayerComponent.mediaPlayer().controls().stop()
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

    fun applyAutoFit() {
        mediaPlayerComponent.mediaPlayer().video().setScale(0f) // 0 = ajustement automatique
        mediaPlayerComponent.mediaPlayer().video().setAspectRatio(null)
    }

    // Recalcule le cadrage/zoom à CHAQUE redimensionnement du panneau vidéo,
    // pas une seule fois au démarrage : un calcul unique restait basé sur la
    // taille du panneau au moment précis où il a été fait, qui ne
    // correspond plus à la taille réelle dès que la fenêtre est redimensionnée
    // ou que les barres haut/bas apparaissent/disparaissent (elles changent
    // la hauteur disponible pour la vidéo) - c'était la cause de l'image
    // avec trop d'espace vide en haut et poussée vers le bas.
    DisposableEffect(Unit) {
        val listener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                if (mediaPlayerComponent.isDisplayable) applyAutoFit()
            }
        }
        mediaPlayerComponent.addComponentListener(listener)
        onDispose { mediaPlayerComponent.removeComponentListener(listener) }
    }

    LaunchedEffect(streamUrl) {
        var attempts = 0
        while (!mediaPlayerComponent.isDisplayable && attempts < 150) { // ~3s max
            delay(20L)
            attempts++
        }
        pausedFrame = null
        mediaPlayerComponent.mediaPlayer().media().play(streamUrl)
        isPlaying = true
        delay(300L) // laisse VLC ouvrir le flux avant de fixer le cadrage
        applyAutoFit()
    }

    DisposableEffect(streamUrl) {
        onDispose {
            mediaPlayerComponent.mediaPlayer().controls().stop()
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayerComponent.mediaPlayer()
        if (isPlaying) {
            // Capture le dernier finage AVANT de mettre en pause, pour
            // l'afficher en repli à la place du composant vidéo natif -
            // voir la note en tête de fichier : plus fiable que de compter
            // sur le rendu natif de VLC en pause (qui pouvait afficher du
            // noir sur certains flux HLS/live).
            scope.launch {
                val snapshot = try {
                    withContext(Dispatchers.IO) { mp.snapshots().get() }
                } catch (e: Exception) {
                    null
                }
                pausedFrame = snapshot?.let { bufferedImage ->
                    try {
                        val bytes = ByteArrayOutputStream().use { out ->
                            ImageIO.write(bufferedImage, "png", out)
                            out.toByteArray()
                        }
                        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            mp.controls().pause()
        } else {
            pausedFrame = null
            mp.controls().play()
        }
        isPlaying = !isPlaying
    }

    // Capturée une seule fois à l'entrée sur cet écran (même bouquet/recherche
    // que ce qui était affiché sur HomeScreen au moment du clic).
    val playingList = remember { ChannelRepository.playingList }

    var showChannelPanel by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // --- Auto-hide des barres haut/bas après 5s d'inactivité (clavier ou
    // souris), voir la note en tête de fichier sur pourquoi ce n'est pas un
    // calque flottant par-dessus la vidéo. Toujours visible pendant la
    // pause ou quand le panneau "Changer de chaîne" est ouvert.
    var lastActivity by remember { mutableStateOf(System.currentTimeMillis()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val controlsVisible = !isPlaying || showChannelPanel || (now - lastActivity < CONTROLS_HIDE_DELAY_MS)

    fun pingActivity() {
        lastActivity = System.currentTimeMillis()
    }

    // Coche l'horloge "now" toutes les 300ms, en continu, tant que l'écran
    // est affiché - c'est cette mise à jour d'état qui force Compose à
    // recalculer "controlsVisible" ci-dessus au fil du temps (comparer
    // System.currentTimeMillis() dans un simple "val" ne suffit pas : sans
    // état qui change, Compose n'a aucune raison de recomposer).
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
            .focusRequester(playerFocusRequester)
            .focusTarget()
            .onPointerEvent(PointerEventType.Move) { pingActivity() }
            .onPointerEvent(PointerEventType.Press) { pingActivity() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                pingActivity()
                if (!showChannelPanel && event.key == Key.Spacebar) {
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

            AnimatedVisibility(visible = controlsVisible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(
                    Modifier.fillMaxWidth().background(SolPlayColors.SurfaceDark).padding(8.dp),
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

            // Vidéo + panneau "Changer de chaîne" comme deux VRAIS voisins
            // dans la même Row (jamais empilés) - voir la note en tête de
            // fichier.
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (pausedFrame != null) {
                        Image(
                            bitmap = pausedFrame!!,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().background(Color.Black),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        SwingPanel(modifier = Modifier.fillMaxSize(), factory = { mediaPlayerComponent })
                    }
                }

                if (showChannelPanel) {
                    ChannelSwitchPanel(
                        playingList = playingList,
                        currentStreamUrl = streamUrl,
                        query = query,
                        onQueryChange = { query = it; pingActivity() },
                        onSelect = ::selectChannel
                    )
                }
            }

            AnimatedVisibility(visible = controlsVisible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(SolPlayColors.SurfaceDark)
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
        }
    }
}

/** Panneau "Changer de chaîne" - toujours un vrai voisin de mise en page, jamais un calque sur la vidéo (voir PlayerScreen). */
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
            .width(320.dp)
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

