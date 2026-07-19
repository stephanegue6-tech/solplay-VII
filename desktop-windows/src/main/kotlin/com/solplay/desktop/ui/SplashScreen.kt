package com.solplay.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * v18 : la vérification "VLC installé ?" a été retirée - le lecteur vidéo
 * décode maintenant lui-même les flux via FFmpeg embarqué (voir
 * VideoDecoder.kt), sans dépendre d'une installation externe.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1200)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(SolPlayColors.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SolPlay", color = SolPlayColors.Orange, fontSize = 42.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Votre lecteur IPTV nouvelle génération", color = SolPlayColors.White.copy(alpha = 0.7f), fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            Box(Modifier.width(60.dp).height(4.dp).background(SolPlayColors.Green))
        }
    }
}
