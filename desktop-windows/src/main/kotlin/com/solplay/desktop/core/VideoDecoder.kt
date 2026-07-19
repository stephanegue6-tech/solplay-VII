package com.solplay.desktop.core

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * Décodeur vidéo/audio "maison" via FFmpeg (javacv), qui remplace VLC/vlcj.
 *
 * Pourquoi ce changement : VLC (comme n'importe quel moteur vidéo natif
 * embarqué via SwingPanel) est un composant "lourd" sur lequel Compose ne
 * peut PAS dessiner par-dessus proprement - impossible d'avoir un vrai
 * calque (overlay) ou un centrage 100% piloté par Compose, obligeant à
 * contourner (voisins de mise en page au lieu de superposition, capture
 * d'image figée pour la pause...). En décodant nous-mêmes chaque image et
 * en l'affichant comme un simple Image() Compose, ce composant devient un
 * écran Compose comme un autre : overlays, animations, centrage - tout
 * fonctionne normalement, sans aucun contournement.
 *
 * Bonus : javacv embarque les binaires FFmpeg nécessaires (voir
 * build.gradle.kts) - l'utilisateur n'a plus besoin d'installer VLC
 * séparément sur son PC.
 *
 * Limite assumée de cette v1 (à savoir) : la synchronisation audio/vidéo se
 * fait "au fil de l'eau" (on pousse les images et le son dès qu'ils sont
 * décodés, sans recaler sur les timestamps précis du flux) plutôt qu'une
 * synchronisation par timestamps comme le ferait un lecteur professionnel
 * (VLC, ExoPlayer). Pour un flux IPTV live, l'écart reste généralement
 * imperceptible en pratique, mais peut légèrement dériver sur de très
 * longues sessions de lecture continue.
 *
 * --- Correctif (audit) : isolation par "session" ---
 * Avant, `grabber`/`audioLine` étaient des champs de classe partagés entre
 * plusieurs appels successifs de play() (ex: zapping rapide de chaînes).
 * stop() annulait le job SANS attendre sa fin réelle (grabFrame() est un
 * appel bloquant JNI qui ignore l'annulation coopérative), donc l'ancienne
 * coroutine pouvait terminer APRÈS que la nouvelle ait démarré, et son
 * `finally` libérait alors le `grabber`/`audioLine` de la NOUVELLE lecture
 * (crash / écran noir / fuite native selon le cas).
 *
 * Chaque appel à play() crée maintenant une [Session] dédiée : le grabber et
 * la ligne audio lui sont strictement locaux (capturés dans la coroutine,
 * plus de champ de classe partagé), donc deux sessions qui se chevauchent
 * brièvement pendant la transition ne peuvent plus se marcher dessus. En
 * plus, stop() interrompt activement le grabFrame() bloquant en cours en
 * appelant grabber.stop() depuis le thread appelant (plutôt que d'attendre
 * son retour naturel, qui peut prendre plusieurs secondes sur un flux
 * réseau lent), et les callbacks (onReady/onFrame/onError) d'une session
 * devenue obsolète sont ignorés.
 */
class VideoDecoder {

    var onFrame: ((ImageBitmap) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onReady: (() -> Unit)? = null

    @Volatile var paused: Boolean = false

    /** État propre à un appel de play() : plus aucun champ partagé entre deux lectures qui se chevauchent. */
    private class Session {
        val running = AtomicBoolean(true)
        var grabber: FFmpegFrameGrabber? = null
        var audioLine: SourceDataLine? = null
    }

    private val sessionIdGenerator = AtomicLong(0)
    private val currentSession = AtomicReference<Session?>(null)
    private var job: Job? = null

    fun play(url: String, scope: CoroutineScope) {
        stop()

        val session = Session()
        currentSession.set(session)
        sessionIdGenerator.incrementAndGet()

        job = scope.launch(Dispatchers.IO) {
            val converter = Java2DFrameConverter()
            try {
                val g = FFmpegFrameGrabber(url)
                // Robustesse spécifique aux flux IPTV live (coupures réseau
                // fréquentes, serveurs qui mettent du temps à répondre) :
                // reconnexion automatique + délai de tolérance raisonnable
                // avant d'abandonner, plutôt que d'échouer immédiatement.
                g.setOption("reconnect", "1")
                g.setOption("reconnect_streamed", "1")
                g.setOption("reconnect_delay_max", "5")
                g.setOption("timeout", "15000000") // 15s, en microsecondes (unité attendue par FFmpeg)
                // Comme pour M3uParser.kt / AsyncImage.kt : de nombreux
                // panels/CDN IPTV bloquent ou dégradent le flux lui-même
                // (pas seulement le fichier M3U ou les logos) face au
                // User-Agent par défaut de FFmpeg. Sans ce réglage, une
                // playlist qui se charge très bien pouvait néanmoins
                // échouer à la lecture sur ces serveurs-là.
                g.setOption(
                    "user_agent",
                    "Mozilla/5.0 (Linux; Android 10; SM-A205U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
                )
                g.start()
                session.grabber = g
                setupAudio(g, session)

                if (isCurrent(session)) {
                    withContext(Dispatchers.Main) { onReady?.invoke() }
                }

                while (session.running.get() && isActive) {
                    if (paused) {
                        delay(100)
                        continue
                    }
                    val frame: Frame? = g.grabFrame()

                    // grabFrame() est un appel bloquant : on revérifie juste après qu'on
                    // n'a pas été remplacé par une session plus récente pendant l'attente,
                    // pour éviter d'afficher une image d'un flux qu'on est censé avoir quitté
                    // (ou de signaler une erreur pour un arrêt volontaire par l'utilisateur).
                    if (!session.running.get() || !isCurrent(session)) break

                    if (frame == null) {
                        // Flux terminé côté FFmpeg (fin de la vidéo/VOD, chaîne coupée,
                        // connexion perdue...). Avant, ce cas se contentait d'un `break`
                        // silencieux : l'écran restait figé sur la dernière image sans
                        // aucune indication pour l'utilisateur, qui ne pouvait pas savoir
                        // si c'était voulu, un bug, ou une coupure réseau.
                        withContext(Dispatchers.Main) {
                            onError?.invoke("La lecture s'est arrêtée (fin du flux ou connexion perdue).")
                        }
                        break
                    }

                    when {
                        frame.image != null -> {
                            val bufferedImage = converter.convert(frame)
                            if (bufferedImage != null) {
                                val bitmap = bufferedImage.toComposeImageBitmap()
                                if (isCurrent(session)) {
                                    withContext(Dispatchers.Main) { onFrame?.invoke(bitmap) }
                                }
                            }
                        }
                        frame.samples != null -> playAudioSamples(frame, session)
                    }
                }
            } catch (e: Exception) {
                if (session.running.get() && isCurrent(session)) {
                    withContext(Dispatchers.Main) {
                        onError?.invoke("Impossible de lire ce flux : ${e.message ?: "erreur inconnue"}.")
                    }
                }
            } finally {
                cleanupAudio(session)
                try {
                    session.grabber?.stop()
                    session.grabber?.release()
                } catch (e: Exception) {
                    // Best effort : on ne bloque jamais l'arrêt sur une erreur de nettoyage.
                }
                session.grabber = null
                try {
                    converter.close()
                } catch (e: Exception) {
                    // Best effort, idem.
                }
            }
        }
    }

    private fun isCurrent(session: Session): Boolean = currentSession.get() === session

    fun stop() {
        val session = currentSession.getAndSet(null) ?: run {
            job?.cancel()
            job = null
            return
        }
        session.running.set(false)
        // Débloque activement un éventuel grabFrame() en cours (appel bloquant
        // JNI qui ignore l'annulation coopérative de la coroutine) au lieu
        // d'attendre son retour naturel, qui peut prendre plusieurs secondes
        // sur un flux réseau lent. javacv autorise l'appel de stop() depuis un
        // autre thread que celui qui exécute grabFrame() dans ce but précis.
        try {
            session.grabber?.stop()
        } catch (e: Exception) {
            // Best effort : le nettoyage complet (release) reste fait par le
            // `finally` de la coroutine elle-même, dans tous les cas.
        }
        job?.cancel()
        job = null
    }

    private fun setupAudio(g: FFmpegFrameGrabber, session: Session) {
        try {
            if (g.audioChannels <= 0) return // flux sans piste audio
            val format = AudioFormat(g.sampleRate.toFloat(), 16, g.audioChannels, true, true)
            val info = DataLine.Info(SourceDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) return
            val line = AudioSystem.getLine(info) as SourceDataLine
            line.open(format)
            line.start()
            session.audioLine = line
        } catch (e: Exception) {
            // La vidéo continue même si l'audio ne peut pas s'initialiser
            // (ex: aucun périphérique de sortie disponible) - mieux vaut une
            // vidéo muette qu'un écran qui refuse de démarrer.
            session.audioLine = null
        }
    }

    private fun playAudioSamples(frame: Frame, session: Session) {
        val line = session.audioLine ?: return
        val samplesArray = frame.samples ?: return
        val shortBuffer = samplesArray.getOrNull(0) as? ShortBuffer ?: return
        shortBuffer.rewind()
        val bytes = ByteArray(shortBuffer.remaining() * 2)
        var i = 0
        while (shortBuffer.hasRemaining()) {
            val sample = shortBuffer.get().toInt()
            // Gros-boutiste (bigEndian = true), pour correspondre exactement
            // au AudioFormat déclaré dans setupAudio().
            bytes[i++] = (sample shr 8 and 0xFF).toByte()
            bytes[i++] = (sample and 0xFF).toByte()
        }
        line.write(bytes, 0, bytes.size)
    }

    private fun cleanupAudio(session: Session) {
        try {
            session.audioLine?.stop()
            session.audioLine?.close()
        } catch (e: Exception) {
            // Ignoré : on ferme au mieux, sans bloquer l'arrêt du lecteur.
        }
        session.audioLine = null
    }
}
