package com.solplay.desktop.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.solplay.desktop.core.AsyncImage
import com.solplay.iptv.Bouquet
import com.solplay.iptv.Channel
import com.solplay.iptv.ContentType
import com.solplay.iptv.DeviceKeyManager
import com.solplay.iptv.DevicePlaylistSync
import com.solplay.iptv.ParentalControl
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
 * jamais être quitté. On revérifie donc ici nous-mêmes immédiatement puis
 * toutes les 30 secondes, tant que cet écran est affiché, si l'admin a
 * désactivé/supprimé l'assignation ou le code ayant servi à obtenir cette
 * playlist (DevicePlaylistSync.checkStillAssigned couvre les deux cas). Si
 * l'accès a été retiré, on supprime la playlist locale et on renvoie vers
 * l'écran de connexion, comme sur Android.
 */
@Composable
fun HomeScreen(
    context: Context,
    playlist: SavedPlaylist,
    onPlay: (streamUrl: String, title: String) -> Unit,
    onOpenEpgGrid: (List<Channel>) -> Unit,
    onDisconnect: () -> Unit
) {
    val density = LocalDensity.current
    var revokedMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playlist.id) {
        val tag = playlist.fromCode ?: return@LaunchedEffect
        while (true) {
            // Vérification immédiate à chaque tour, puis 30s (au lieu de 2min) -
            // aligné sur le même correctif que PlayerActivity côté Android.
            if (!DevicePlaylistSync.checkStillAssigned(context, tag)) {
                PlaylistStore.delete(context, playlist.id)
                revokedMessage = "L'accès à cette playlist a été retiré par l'administrateur."
                break
            }
            delay(30_000L)
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

    // Contrôle parental : action mise en attente tant que le code n'a pas
    // été validé (ouverture d'une chaîne adulte, ou sélection d'un bouquet
    // adulte) - voir ParentalControl.kt. `pinError` affiche un message si le
    // dernier code saisi était incorrect.
    var pendingUnlock by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    // Miroir Compose-réactif de ParentalControl.isUnlocked() : l'objet lui-même
    // n'est qu'une variable @Volatile, donc la lire directement dans un Text()
    // ne provoquerait aucune recomposition au déverrouillage - ce state si.
    var sessionUnlocked by remember { mutableStateOf(ParentalControl.isUnlocked()) }

    fun requireParentalPinIfNeeded(isAdult: Boolean, action: () -> Unit) {
        if (!isAdult || ParentalControl.isUnlocked()) {
            action()
        } else {
            pinInput = ""
            pinError = false
            pendingUnlock = action
        }
    }

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
        if (ParentalControl.isAdultChannel(channel) && !ParentalControl.isUnlocked()) {
            requireParentalPinIfNeeded(true) { handleClick(channel) }
            return
        }
        when (currentType) {
            ContentType.LIVE -> {
                // Alimente ChannelRepository.playingList (liste actuellement
                // affichée - filtrée par bouquet/recherche) AVANT d'ouvrir le
                // lecteur : c'est cette liste que PlayerScreen utilise pour
                // son panneau "☰ Chaînes" (changer de chaîne sans revenir en
                // arrière). Même principe que ChannelsActivity côté Android.
                com.solplay.iptv.ChannelRepository.updatePlayingList(filtered)
                onPlay(channel.streamUrl, channel.name)
            }
            ContentType.MOVIE -> ficheChannel = channel
            ContentType.SERIES -> {
                if (XtreamApiClient.isSeriesShell(channel)) openSeriesEpisodes(channel) else ficheChannel = channel
            }
        }
    }

    var showAbout by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        revokedMessage?.let { msg ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(playlist.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
            IconButton(onClick = { showAbout = true }) {
                Icon(Icons.Filled.Info, contentDescription = "À propos")
            }
            TextButton(onClick = {
                PlaylistStore.setActiveId(context, null)
                onDisconnect()
            }) { Text("Changer de compte") }
        }
        Spacer(Modifier.height(6.dp))

        // Petit bandeau permanent (demandé) : clé appareil de ce PC + temps
        // restant sur la licence/l'essai, toujours visible en haut de
        // l'écran d'accueil - pas seulement dans "À propos" - pour que
        // l'utilisateur (ou l'admin en support à distance) puisse lire ces
        // deux infos d'un coup d'œil sans naviguer dans un sous-écran.
        Surface(
            color = SolPlayColors.Gray,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Clé appareil : ${DeviceKeyManager.getDeviceKey(context)}",
                    color = SolPlayColors.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                val remainingText = remember {
                    if (TrialManager.isLicensed(context)) {
                        val remaining = TrialManager.getRemainingLicenseMillis(context)
                        if (remaining == Long.MAX_VALUE) "Licence illimitée"
                        else "Licence : ${TrialManager.formatDuration(remaining)} restant"
                    } else {
                        "Essai : ${TrialManager.formatDuration(TrialManager.getRemainingTrialMillis(context))} restant"
                    }
                }
                Text(
                    remainingText,
                    color = SolPlayColors.Orange,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        if (showAbout) {
            AboutDialog(context = context, onDismiss = { showAbout = false })
        }

        TabRow(selectedTabIndex = currentType.ordinal, modifier = Modifier.height(36.dp)) {
            Tab(selected = currentType == ContentType.LIVE, onClick = { currentType = ContentType.LIVE; currentBouquet = ALL_BOUQUETS }, text = { Text("Live", style = MaterialTheme.typography.labelMedium) })
            Tab(selected = currentType == ContentType.MOVIE, onClick = { currentType = ContentType.MOVIE; currentBouquet = ALL_BOUQUETS }, text = { Text("Films", style = MaterialTheme.typography.labelMedium) })
            Tab(selected = currentType == ContentType.SERIES, onClick = { currentType = ContentType.SERIES; currentBouquet = ALL_BOUQUETS }, text = { Text("Séries", style = MaterialTheme.typography.labelMedium) })
        }
        Spacer(Modifier.height(6.dp))

        // Recherche compacte : "placeholder" au lieu de "label" flottant -
        // un label Material3 réserve de la place en plus pour l'animation
        // (label qui monte au-dessus de la bordure au focus), ce qui rendait
        // ce champ plus haut que nécessaire pour une simple barre de
        // recherche. Avec un placeholder, la hauteur redescend à un champ
        // de recherche classique, sans perdre l'information "Rechercher".
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Rechercher...") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        )
        Spacer(Modifier.height(8.dp))

        // Rangée des bouquets : navigable au clavier (flèches gauche/droite),
        // ce qui n'existait pas du tout avant - seul le clic souris changeait
        // le bouquet sélectionné. Même principe que la LazyColumn des
        // chaînes juste en dessous (FocusRequester + onPreviewKeyEvent).
        val bouquetListState = rememberLazyListState()
        val bouquetFocusRequester = remember { FocusRequester() }
        val bouquetIndex = remember(bouquets, currentBouquet) {
            bouquets.indexOfFirst { it.name == currentBouquet }.coerceAtLeast(0)
        }

        LazyRow(
            state = bouquetListState,
            modifier = Modifier
                .focusRequester(bouquetFocusRequester)
                .focusTarget()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionRight -> {
                            val next = (bouquetIndex + 1).coerceAtMost(bouquets.lastIndex)
                            currentBouquet = bouquets[next].name
                            scope.launch { bouquetListState.animateScrollToItem(next) }
                            true
                        }
                        Key.DirectionLeft -> {
                            val prev = (bouquetIndex - 1).coerceAtLeast(0)
                            currentBouquet = bouquets[prev].name
                            scope.launch { bouquetListState.animateScrollToItem(prev) }
                            true
                        }
                        else -> false
                    }
                }
        ) {
            itemsIndexed(bouquets) { _, b ->
                FilterChip(
                    selected = currentBouquet == b.name,
                    onClick = {
                        requireParentalPinIfNeeded(ParentalControl.isAdultLabel(b.name)) {
                            currentBouquet = b.name
                            bouquetFocusRequester.requestFocus()
                        }
                    },
                    label = {
                        val locked = ParentalControl.isAdultLabel(b.name) && !sessionUnlocked
                        Text(if (locked) "🔒 ${b.name} (${b.channelCount})" else "${b.name} (${b.channelCount})")
                    },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        Text("${filtered.size} élément(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(4.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucun résultat.", color = MaterialTheme.colorScheme.outline)
            }
        } else if (currentType == ContentType.LIVE) {
            val listState = rememberLazyListState()
            val listFocusRequester = remember { FocusRequester() }

            // Focus automatique au changement d'onglet (Live/Films/Séries)
            // uniquement - PAS à chaque changement de bouquet, sinon le focus
            // clavier repartait vers cette liste après une seule flèche
            // gauche/droite sur la rangée des bouquets, empêchant d'enchaîner
            // plusieurs appuis flèche de suite pour naviguer entre eux.
            LaunchedEffect(currentType) {
                listFocusRequester.requestFocus()
            }

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
                            // Liste à une seule colonne : gauche/droite n'ont pas de
                            // cible latérale évidente, donc on les fait sauter d'une
                            // "page" (comme Page Haut/Bas) plutôt que de les laisser
                            // sans effet - toutes les listes doivent réagir aux 4 flèches.
                            Key.DirectionRight, Key.PageDown -> {
                                scope.launch { listState.animateScrollToItem((index + 10).coerceAtMost(filtered.lastIndex)) }
                                true
                            }
                            Key.DirectionLeft, Key.PageUp -> {
                                scope.launch { listState.animateScrollToItem((index - 10).coerceAtLeast(0)) }
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                items(filtered, key = { it.streamUrl }) { channel: Channel ->
                    ListItem(
                        leadingContent = {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))) {
                                AsyncImage(channel.logoUrl, channel.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
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
        } else {
            // Films / Séries : grille d'affiches classée (colonnes), plutôt
            // que la liste brute utilisée pour le Live - beaucoup plus
            // lisible pour parcourir un catalogue VOD, et cohérent avec ce
            // qu'affichent la plupart des apps IPTV pour ce type de contenu.
            //
            // Colonnes ADAPTATIVES (Adaptive) plutôt qu'un nombre FIXE de 5 :
            // un nombre fixe donnait des colonnes minuscules et serrées sur
            // un petit écran/fenêtre (ex: portable 1366px de large) et,
            // inversement, des colonnes exagérément larges sur un grand
            // moniteur ou en 4K. GridCells.Adaptive(minSize) calcule tout
            // seul le nombre de colonnes qui tient réellement dans la
            // largeur disponible, avec ~150dp minimum par affiche - ce qui
            // donne un nombre de colonnes cohérent quelle que soit la
            // taille d'écran/fenêtre (démaximisée, agrandie, tout écran...).
            val gridState = rememberLazyGridState()
            val gridFocusRequester = remember { FocusRequester() }
            // Nombre de colonnes réellement affichées à cet instant, recalculé
            // dynamiquement par Compose selon la largeur du conteneur -
            // utilisé ci-dessous uniquement pour la navigation au clavier
            // (savoir de combien d'éléments sauter avec Haut/Bas), donc une
            // estimation raisonnable suffit ici (elle n'affecte pas l'affichage).
            var visibleGridColumns by remember { mutableStateOf(5) }

            LaunchedEffect(currentType) {
                gridFocusRequester.requestFocus()
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                state = gridState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        val estimatedColumns = (size.width / with(density) { 158.dp.toPx() }).toInt().coerceAtLeast(1)
                        visibleGridColumns = estimatedColumns
                    }
                    .focusRequester(gridFocusRequester)
                    .focusTarget()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val index = gridState.firstVisibleItemIndex
                        when (event.key) {
                            Key.DirectionRight -> {
                                scope.launch { gridState.animateScrollToItem((index + 1).coerceAtMost(filtered.lastIndex)) }
                                true
                            }
                            Key.DirectionLeft -> {
                                scope.launch { gridState.animateScrollToItem((index - 1).coerceAtLeast(0)) }
                                true
                            }
                            Key.DirectionDown -> {
                                scope.launch { gridState.animateScrollToItem((index + visibleGridColumns).coerceAtMost(filtered.lastIndex)) }
                                true
                            }
                            Key.DirectionUp -> {
                                scope.launch { gridState.animateScrollToItem((index - visibleGridColumns).coerceAtLeast(0)) }
                                true
                            }
                            else -> false
                        }
                    },
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gridItems(filtered, key = { it.streamUrl }) { channel: Channel ->
                    Column(
                        modifier = Modifier
                            .clickable { handleClick(channel) }
                            .fillMaxWidth()
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                // Hauteur FIXE (au lieu d'un aspectRatio qui
                                // remplissait toute la largeur de colonne) :
                                // dans une fenêtre large (ex: 1280px / 5
                                // colonnes ≈ 240dp de large par colonne), un
                                // aspectRatio(2f/3f) donnait des affiches
                                // ~360dp de haut CHACUNE - la 1ère ligne à
                                // elle seule remplissait presque tout l'écran
                                // visible, donnant l'impression qu'"une seule
                                // ligne de 5 colonnes" s'affichait (la 2e
                                // ligne existait bien mais coupée en bas,
                                // sans indice visuel de défilement). Avec une
                                // hauteur fixe de 180dp, ContentScale.Crop
                                // recadre proprement l'affiche pour remplir
                                // cette boîte quelle que soit sa largeur - on
                                // voit confortablement 2 lignes (10 affiches)
                                // d'un coup dans une fenêtre standard.
                                .height(180.dp)
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            AsyncImage(
                                channel.logoUrl,
                                channel.name,
                                Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            channel.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
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
                    else -> {
                        // Liste d'épisodes elle aussi navigable aux 4 flèches, comme
                        // toutes les autres listes de l'app (même principe que la
                        // LazyColumn des chaînes et la LazyVerticalGrid films/séries
                        // ci-dessus : FocusRequester + onPreviewKeyEvent).
                        val episodesListState = rememberLazyListState()
                        val episodesFocusRequester = remember { FocusRequester() }
                        LaunchedEffect(series) { episodesFocusRequester.requestFocus() }

                        LazyColumn(
                            state = episodesListState,
                            modifier = Modifier
                                .heightIn(max = 400.dp)
                                .focusRequester(episodesFocusRequester)
                                .focusTarget()
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    val index = episodesListState.firstVisibleItemIndex
                                    when (event.key) {
                                        Key.DirectionDown -> {
                                            scope.launch { episodesListState.animateScrollToItem((index + 1).coerceAtMost(episodes.lastIndex)) }
                                            true
                                        }
                                        Key.DirectionUp -> {
                                            scope.launch { episodesListState.animateScrollToItem((index - 1).coerceAtLeast(0)) }
                                            true
                                        }
                                        Key.DirectionRight, Key.PageDown -> {
                                            scope.launch { episodesListState.animateScrollToItem((index + 10).coerceAtMost(episodes.lastIndex)) }
                                            true
                                        }
                                        Key.DirectionLeft, Key.PageUp -> {
                                            scope.launch { episodesListState.animateScrollToItem((index - 10).coerceAtLeast(0)) }
                                            true
                                        }
                                        else -> false
                                    }
                                }
                        ) {
                            items(episodes, key = { it.streamUrl }) { episode ->
                                ListItem(
                                    headlineContent = { Text(episode.name) },
                                    modifier = Modifier.clickable {
                                        com.solplay.iptv.ChannelRepository.updatePlayingList(episodes)
                                        episodesDialogFor = null
                                        onPlay(episode.streamUrl, episode.name)
                                    }
                                )
                            }
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

    // Code parental : bloque l'accès à une chaîne/un bouquet détecté comme
    // contenu adulte (voir ParentalControl.kt) tant que le bon code n'a pas
    // été saisi. Équivalent desktop de showParentalPinDialog côté Android.
    pendingUnlock?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingUnlock = null },
            title = { Text("🔒 Contenu réservé aux adultes") },
            text = {
                Column {
                    Text("Cette catégorie est protégée. Entrez le code parental pour continuer.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            if (it.length <= 4 && it.all(Char::isDigit)) {
                                pinInput = it
                                pinError = false
                            }
                        },
                        label = { Text("Code (4 chiffres)") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                        ),
                        isError = pinError,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pinError) {
                        Text("Code incorrect.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (ParentalControl.verifyPin(context, pinInput)) {
                        ParentalControl.unlock()
                        sessionUnlocked = true
                        val toRun = action
                        pendingUnlock = null
                        toRun()
                    } else {
                        pinError = true
                    }
                }) { Text("Valider") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnlock = null }) { Text("Annuler") }
            }
        )
    }
}
