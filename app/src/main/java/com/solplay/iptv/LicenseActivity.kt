package com.solplay.iptv

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.solplay.iptv.databinding.ActivityLicenseBinding
import kotlinx.coroutines.launch

class LicenseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicenseBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicenseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DisclaimerDialog.showIfNeeded(this)

        val deviceKey = DeviceKeyManager.getDeviceKey(this)
        binding.tvDeviceKey.text = getString(R.string.device_key_format, deviceKey)

        refreshUiState()

        binding.btnContinueTrial.setOnClickListener {
            goToApp()
        }

        binding.btnCopyDeviceKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Clé appareil SolPlay", deviceKey))
            Toast.makeText(this, "Clé copiée !", Toast.LENGTH_SHORT).show()
        }

        binding.btnVerifyActivation.setOnClickListener {
            binding.progressBarLicense.visibility = android.view.View.VISIBLE
            lifecycleScope.launch {
                val active = TrialManager.checkOnlineLicense(this@LicenseActivity)
                binding.progressBarLicense.visibility = android.view.View.GONE
                refreshUiState()
                if (active) {
                    Toast.makeText(this@LicenseActivity, R.string.license_success, Toast.LENGTH_LONG).show()
                    goToApp()
                } else {
                    Toast.makeText(this@LicenseActivity, "Pas encore activée. Contactez votre revendeur avec votre clé appareil.", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnContactUs.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:" + getString(R.string.contact_email))
                putExtra(Intent.EXTRA_SUBJECT, "Achat SolPlay Pro - Clé appareil : $deviceKey")
                putExtra(Intent.EXTRA_TEXT, "Bonjour, je souhaite activer la version Pro de SolPlay.\n\nMa clé appareil : $deviceKey")
            }
            startActivity(Intent.createChooser(intent, "Contacter SolPlay"))
        }

        binding.btnWhatsApp.setOnClickListener {
            openWhatsAppContact(deviceKey)
        }
    }

    /**
     * Met à jour l'affichage selon l'état actuel :
     * - Licence Pro active -> date/heure d'expiration + temps restant
     * - Essai gratuit actif -> temps restant (heures/minutes)
     * - Ni l'un ni l'autre -> écran bloqué avec message + bouton WhatsApp
     */
    private fun refreshUiState() {
        val licensed = TrialManager.isLicensed(this)
        val trialActive = TrialManager.isTrialActive(this)

        when {
            licensed -> {
                val expiresAt = TrialManager.getLicenseExpiresAt(this)
                binding.tvStatus.text = if (expiresAt == 0L) {
                    getString(R.string.license_active_unlimited)
                } else {
                    val remaining = TrialManager.getRemainingLicenseMillis(this)
                    getString(
                        R.string.license_active_format,
                        TrialManager.formatDate(expiresAt),
                        TrialManager.formatDuration(remaining)
                    )
                }
                binding.btnContinueTrial.visibility = android.view.View.VISIBLE
                binding.groupBlocked.visibility = android.view.View.GONE
            }
            trialActive -> {
                val remaining = TrialManager.getRemainingTrialMillis(this)
                binding.tvStatus.text = getString(
                    R.string.trial_active_format,
                    TrialManager.formatDuration(remaining)
                )
                binding.btnContinueTrial.visibility = android.view.View.VISIBLE
                binding.groupBlocked.visibility = android.view.View.GONE
            }
            else -> {
                // Essai (24h) ET licence expirés : on bloque l'accès à l'application.
                binding.tvStatus.text = getString(R.string.trial_expired_title)
                binding.btnContinueTrial.visibility = android.view.View.GONE
                binding.groupBlocked.visibility = android.view.View.VISIBLE
            }
        }
    }

    /**
     * Ouvre une conversation WhatsApp avec le revendeur. Le numéro n'est
     * jamais affiché comme texte à l'écran : il n'existe que dans ce lien.
     */
    private fun openWhatsAppContact(deviceKey: String) {
        val phone = getString(R.string.whatsapp_phone_international)
        val message = Uri.encode(
            "Bonjour, je souhaite souscrire à un abonnement SolPlay Pro.\n\nMa clé appareil : $deviceKey"
        )
        val uri = Uri.parse("https://wa.me/$phone?text=$message")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp n'est pas installé sur cet appareil.", Toast.LENGTH_LONG).show()
        }
    }

    private fun goToApp() {
        startActivity(Intent(this, PlaylistActivity::class.java))
        finish()
    }
}
