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

        val remaining = TrialManager.getRemainingTrialDays(this)
        val trialActive = TrialManager.isTrialActive(this)
        val deviceKey = DeviceKeyManager.getDeviceKey(this)

        binding.tvDeviceKey.text = getString(R.string.device_key_format, deviceKey)

        if (trialActive) {
            binding.tvStatus.text = getString(R.string.trial_active_format, remaining)
            binding.btnContinueTrial.visibility = android.view.View.VISIBLE
        } else {
            binding.tvStatus.text = getString(R.string.trial_expired_title)
            binding.btnContinueTrial.visibility = android.view.View.GONE
        }

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
                if (active) {
                    Toast.makeText(this@LicenseActivity, R.string.license_success, Toast.LENGTH_LONG).show()
                    goToApp()
                } else {
                    Toast.makeText(this@LicenseActivity, "Pas encore activée. Contactez SolPlay avec votre clé appareil.", Toast.LENGTH_LONG).show()
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
    }

    private fun goToApp() {
        startActivity(Intent(this, PlaylistActivity::class.java))
        finish()
    }
}
