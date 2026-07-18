package com.solplay.desktop.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.solplay.desktop.core.AsyncImage
import com.solplay.iptv.Bouquet
import com.solplay.iptv.Channel
import com.solplay.iptv.ContentType
import com.solplay.iptv.DevicePlaylistSync
import com.solplay.iptv.PlaylistStore
import com.solplay.iptv.SavedPlaylist
import com.solplay.iptv.TrialManager
import com.solplay.iptv.XtreamApiClient
import com.solplay.iptv.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ALL_BOUQUETS = "Tous"

/**
 * Équivalent desktop de ChannelsActivity : liste des chaînes de la playlist
 * active, avec onglets Live/Films/Séries, filtre par bouquet, recherche,
 * fiche TMDB pour films/séries, grille EPG pour le Live, et détection
 * d'abonnement expiré - parité avec l'app Android, voir le détail de chaque
 * bloc ci-dessous.
 *
 * Revérification d'accès : contrairement à Android (ChannelsActivity.onResume,
 * PlayerActivity périodique), une fenêtre desktop n'a pas de cycle de vie
 * "pause/resume" à exploiter - l'écran peut rester affiché des heures sans
 * jamais être quitté. On revérifie donc ici nous-mêmes toutes les 2 minutes,
 * tant que cet écran est affiché, si l'admin a désactivé/supprimé
 * l'assignation ou le code ayant servi à obtenir cette playlist
 * (DevicePlaylistSync.checkStillAssigned couvre les deux cas). Si l'accès a
 * été retiré, on supprime la playlist locale et on renvoie vers l'écran de
 * connexion, comme sur Android.
 */
@Composable
fun HomeScreen(
    context: Context,
    playlist: SavedPlaylist,
    onPlay: (streamUrl: String, title: String) -> Unit,
    onOpenEpgGrid: (List<Channel>) -> Unit,
    onDisconnect: () -> Unit
) {
    var revokedMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playlist.id) {
        val tag = playlist.fromCode ?: return@LaunchedEffect
        while (true) {
            delay(120_000L) // 2 min, même intervalle que PlayerActivity côté Android
            if (!DevicePlaylistSync.checkStillAssigned(context, tag)) {
                PlaylistStore.delete(context, playlist.id)
                revokedMessage = "L'accès à cette playlist a été retiré par l'administrateur."
                break
            }
        }
    }

    LaunchedEffect(revokedMessage) {
        if (revokedMessage != null) {
            delay(3000L) // laisse le message visible un instant avant de renvoyer à Connect
            onDisconnect()
        }
    }

    // Vérifie si l'abonnement (code M3U/Xtream) est expiré côté panel, même
    // quand la liste de chaînes continue de se charger normalement (fréquent
    // avec les panels IPTV : seuls les flux cessent de fonctionner à la
    // lecture, sans message explicite). Équivalent de
    // ChannelsActivity.checkSubscriptionExpiration.
    var expiryMessage by remember(playlist.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(playlist.id) {
        val status = XtreamApiClient.checkAccountStatus(playlist) ?: return@LaunchedEffect
        if (status.expired) {
            val expiryText = status.expiresAtMillis?.let { TrialManager.formatDate(it) }
            expiryMessage = buildString {
                append("Votre abonnement IPTV (")
                append(playlist.name)
                append(") est arrivé à expiration")
                if (expiryText != null) append(" le $expiryText")
                append(".\n\nLes chaînes affichées ne pourront plus être lues. ")
                append("Contactez votre fournisseur pour renouveler votre code.")
            }
        }
    }

    val scope = rememberCoroutineScope()
    val allChannels = com.solplay.iptv.ChannelRepository.channels

    var currentType by remember { mutableStateOf(ContentType.LIVE) }
    var currentBouquet by remember { mutableStateOf(ALL_BOUQUETS) }
    var query by remember { mutableStateOf("") }

    var episodesDialogFor by remember { mutableStateOf<Channel?>(null) }
    var episodesLoading by remember { mutableStateOf(false) }
    var episodes by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var ficheChannel by remember { mutableStateOf<Channel?>(null) }

    val channelsForType = remember(allChannels, currentType) {
        allChannels.filter { it.contentType() == currentType }
    }
    val bouquets = remember(channelsForType) {
        listOf(Bouquet(ALL_BOUQUETS, channelsForType.size)) +
            channelsForType
                .mapNotNull { it.groupTitle?.trim()?.takeIf { g -> g.isNotEmpty() } }
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
                .map { (name, count) -> Bouquet(name, count) }
    }
    val filtered = remember(channelsForType, currentBouquet, query) {
        channelsForType
            .filter { currentBouquet == ALL_BOUQUETS || it.groupTitle?.trim() == currentBouquet }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }

    fun openSeriesEpisodes(seriesChannel: Channel) {
        episodesDialogFor = seriesChannel
        episodesLoading = true
        episodes = emptyList()
        scope.launch {
            episodes = XtreamApiClient.fetchSeriesEpisodes(playlist, seriesChannel)
            episodesLoading = false
        }
    }

    fun handleClick(channel: Channel) {
        when (currentType) {
            ContentType.LIVE -> onPlay(channel.streamUrl, channel.name)
            ContentType.MOVIE -> ficheChannel = channel
            ContentType.SERIES -> {
                if (XtreamApiClient.isSeriesShell(channel)) openSeriesEpisodes(channel) else ficheChannel = channel
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        revokedMessage?.let { msg ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(playlist.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (currentType == ContentType.LIVE && playlist.extractXtreamCredentials() != null) {
                TextButton(onClick = {
                    val liveChannels = channelsForType
                    if (liveChannels.isNotEmpty()) onOpenEpgGrid(liveChannels)
                }) {
                    Icon(Icons.Filled.DateRange, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Guide EPG")
                }
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = {
                PlaylistStore.setActiveId(context, null)
                onDisconnect()
            }) { Text("Changer de compte") }
        }
        Spacer(Modifier.height(12.dp))

        TabRow(selectedTabIndex = currentType.ordinal) {
            Tab(selected = currentType == ContentType.LIVE, onClick = { currentType = ContentType.LIVE; currentBouquet = ALL_BOUQUETS }, text = { Text("Live") })
            Tab(selected = currentType == ContentType.MOVIE, onClick = { currentType = ContentType.MOVIE; currentBouquet = ALL_BOUQUETS }, text = { Text("Films") })
            Tab(selected = currentType == ContentType.SERIES, onClick = { currentType = ContentType.SERIES; currentBouquet = ALL_BOUQUETS }, text = { Text("Séries") })
        }
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Rechercher") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        Row(Modifier.horizontalScroll(rememberScrollState())) {
            bouquets.forEach { b ->
                FilterChip(
                    selected = currentBouquet == b.name,
                    onClick = { currentBouquet = b.name },
                    label = { Text("${b.name} (${b.channelCount})") }
                )
                Spacer(Modifier.width(6.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        Text("${filtered.size} élément(s)", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucun résultat.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn {
                items(filtered, key = { it.streamUrl }) { channel: Channel ->
                    ListItem(
                        leadingContent = {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))) {
                                AsyncImage(channel.logoUrl, channel.name, Modifier.fillMaxSize())
                            }
                        },
                        headlineContent = { Text(channel.name) },
                        supportingContent = channel.groupTitle?.let { { Text(it) } },
                        trailingContent = {
                            IconButton(onClick = { handleClick(channel) }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Ouvrir")
                            }
                        },
                        modifier = Modifier.clickable { handleClick(channel) }
                    )
                    Divider()
                }
            }
        }
    }

    // Fiche TMDB (films et épisodes/séries "feuille") : affiche/synopsis/année
    // avant de lancer la lecture.
    ficheChannel?.let { channel ->
        TmdbFicheDialog(
            channel = channel,
            contentType = currentType,
            onDismiss = { ficheChannel = null },
            onPlay = {
                ficheChannel = null
                onPlay(channel.streamUrl, channel.name)
            }
        )
    }

    // Liste des épisodes d'une série "coquille" (chargés à la demande, voir
    // XtreamApiClient.fetchSeriesEpisodes) - équivalent du AlertDialog.setItems
    // côté Android (ChannelsActivity.openSeriesEpisodes).
    episodesDialogFor?.let { series ->
        AlertDialog(
            onDismissRequest = { episodesDialogFor = null },
            title = { Text(series.name) },
            text = {
                when {
                    episodesLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Chargement des épisodes…")
                    }
                    episodes.isEmpty() -> Text("Aucun épisode trouvé pour cette série.")
                    else -> LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(episodes, key = { it.streamUrl }) { episode ->
                            ListItem(
                                headlineContent = { Text(episode.name) },
                                modifier = Modifier.clickable {
                                    episodesDialogFor = null
                                    onPlay(episode.streamUrl, episode.name)
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { episodesDialogFor = null }) { Text("Fermer") }
            }
        )
    }

    // Alerte d'abonnement expiré, équivalent de ChannelsActivity.checkSubscriptionExpiration.
    expiryMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { expiryMessage = null },
            title = { Text("⚠️ Abonnement expiré") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { expiryMessage = null }) { Text("OK") } }
        )
    }
}
