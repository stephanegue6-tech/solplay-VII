package com.solplay.desktop.core

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Équivalent minimal de Coil (utilisé côté Android via ImageLoader.kt, une
 * librairie Android-only indisponible sur JVM desktop) : charge une image
 * depuis une URL en tâche de fond et l'affiche, avec un cache mémoire pour
 * éviter de retélécharger la même affiche/logo à chaque recomposition
 * (scroll, changement d'onglet...).
 *
 * Utilisé pour les logos de chaînes et les affiches TMDB (voir
 * TmdbFicheDialog et HomeScreen).
 */
private object ImageCache {
    val cache = ConcurrentHashMap<String, ImageBitmap>()
}

private suspend fun fetchImageBitmap(url: String): ImageBitmap? {
    ImageCache.cache[url]?.let { return it }
    return withContext(Dispatchers.IO) {
        try {
            val bytes = URI(url).toURL().openStream().use { it.readBytes() }
            val bitmap = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
            ImageCache.cache[url] = bitmap
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}

@Composable
fun AsyncImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    var bitmap by remember(url) { mutableStateOf(ImageCache.cache[url]) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            failed = true
            return@LaunchedEffect
        }
        val result = fetchImageBitmap(url)
        if (result != null) bitmap = result else failed = true
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bitmap
        when {
            b != null -> Image(
                bitmap = b,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
            failed -> Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
            else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
