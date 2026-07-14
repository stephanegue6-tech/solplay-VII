package com.solplay.iptv

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.solplay.iptv.databinding.ActivityAboutBinding
import kotlinx.coroutines.launch

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1) Affiche d'abord le statut connu localement (rapide, fonctionne hors-ligne).
        updateStatusText(binding, TrialManager.isLicensed(this))

        // 2) Si pas encore licencié localement, vérifie en ligne (Firebase) au cas où
        //    l'admin aurait activé la clé depuis le dernier lancement de l'app.
        if (!TrialManager.isLicensed(this)) {
            binding.tvLicenseStatus.text = "Statut : Vérification de la licence..."
            lifecycleScope.launch {
                val active = TrialManager.checkOnlineLicense(this@AboutActivity)
                updateStatusText(binding, active)
            }
        }

        val deviceKey = DeviceKeyManager.getDeviceKey(this)
        binding.tvDeviceKeyAbout.text = deviceKey

        binding.btnCopyDeviceKeyAbout.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Clé appareil SolPlay", deviceKey))
            Toast.makeText(this, "Clé copiée !", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatusText(binding: ActivityAboutBinding, licensed: Boolean) {
        if (licensed) {
            binding.tvLicenseStatus.text = "Statut : Version Pro activée ✅"
            val expiresAt = TrialManager.getLicenseExpiresAt(this)
            binding.tvLicenseExpiry.text = if (expiresAt == 0L) {
                "Abonnement sans date d'expiration"
            } else {
                val remaining = TrialManager.getRemainingLicenseMillis(this)
                "Expire le ${TrialManager.formatDate(expiresAt)} (${TrialManager.formatDuration(remaining)} restant)"
            }
        } else {
            val remaining = TrialManager.getRemainingTrialMillis(this)
            binding.tvLicenseStatus.text = if (remaining > 0) {
                "Statut : Essai gratuit (${TrialManager.formatDuration(remaining)} restant)"
            } else {
                "Statut : Essai gratuit terminé — abonnement requis"
            }
            binding.tvLicenseExpiry.text = ""
        }
    }
}
