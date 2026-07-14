package com.solplay.iptv

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Fait clignoter/rafraîchir un texte de compte à rebours (essai gratuit,
 * licence...) toutes les minutes tant que l'écran est visible, au lieu de
 * l'afficher une seule fois (fige la valeur calculée à l'ouverture de
 * l'écran). C'est ce qui causait l'incohérence entre écrans : certains
 * (ex. "À propos") étaient rouverts souvent et semblaient à jour, alors
 * que d'autres (ex. l'écran playlist) restaient affichés longtemps et
 * paraissaient "bloqués".
 *
 * Utilisation dans une Activity :
 * LiveCountdown.attach(this) { updateMyCountdownText() }
 */
object LiveCountdown {

    private const val INTERVAL_MS = 60_000L

    fun attach(owner: androidx.appcompat.app.AppCompatActivity, intervalMs: Long = INTERVAL_MS, onTick: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                onTick()
                handler.postDelayed(this, intervalMs)
            }
        }

        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(lifecycleOwner: LifecycleOwner) {
                // Rafraîchit immédiatement puis toutes les `intervalMs`.
                handler.post(runnable)
            }

            override fun onPause(lifecycleOwner: LifecycleOwner) {
                handler.removeCallbacks(runnable)
            }
        })
    }
}
