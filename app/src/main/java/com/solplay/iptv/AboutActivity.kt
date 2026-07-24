package com.solplay.iptv

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.solplay.iptv.databinding.ActivityAboutBinding
import kotlinx.coroutines.launch

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1) Affiche d'abord le statut connu localement (rapide, fonctionne hors-ligne).
        updateStatusText(binding, TrialManager.isLicensed(this))
        binding.tvDebugOffset.text = TrialManager.getDebugOffsetInfo(this)

        // 2) Vérifie systématiquement en ligne (Firebase), même si l'app se
        //    croit déjà licenciée localement : sinon, si l'admin change la
        //    durée (renouvellement, correction, etc.) après une première
        //    activation, l'app reste bloquée sur l'ancienne valeur mise en
        //    cache jusqu'à son expiration locale et ne revoit jamais la
        //    nouvelle durée tant qu'elle n'a pas expiré côté téléphone.
        binding.tvLicenseStatus.text = "Statut : Vérification de la licence..."
        lifecycleScope.launch {
            val active = TrialManager.checkOnlineLicense(this@AboutActivity)
            updateStatusText(binding, active)
            binding.tvDebugOffset.text = TrialManager.getDebugOffsetInfo(this@AboutActivity)
        }

        // Se met à jour chaque minute tant que l'écran est affiché, pour rester
        // cohérent avec les autres écrans (essai/licence) au lieu de rester figé.
        LiveCountdown.attach(this) { updateStatusText(binding, TrialManager.isLicensed(this)) }

        val deviceKey = DeviceKeyManager.getDeviceKey(this)
        binding.tvDeviceKeyAbout.text = deviceKey

        // Copier la clé dans le presse-papiers
        binding.btnCopyDeviceKeyAbout.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Clé appareil SolPlay", deviceKey))
            Toast.makeText(this, "Clé copiée !", Toast.LENGTH_SHORT).show()
        }

        // Afficher le QR Code de la clé pour que l'admin le scanne
        binding.btnShowQrDeviceKey.setOnClickListener {
            showDeviceKeyQrDialog(deviceKey)
        }

        // ── Renouvellement automatique ──
        binding.btnRequestRenewal.setOnClickListener {
            showRenewalDialog(deviceKey)
        }
    }

    private fun showRenewalDialog(deviceKey: String) {
        val plans = arrayOf("1 mois", "3 mois", "6 mois", "12 mois")
        var selectedPlan = "3 mois"
        android.app.AlertDialog.Builder(this)
            .setTitle("🔄 Demander un renouvellement")
            .setSingleChoiceItems(plans, 1) { _, which -> selectedPlan = plans[which] }
            .setPositiveButton("Envoyer la demande") { _, _ ->
                lifecycleScope.launch {
                    val btn = binding.btnRequestRenewal
                    btn.isEnabled = false
                    btn.text = "Envoi en cours…"
                    val result = RenewalRequester.sendRequest(
                        context       = this@AboutActivity,
                        requestedPlan = selectedPlan,
                        customerName  = ""
                    )
                    btn.isEnabled = true
                    btn.text = "🔄 Demander un renouvellement"
                    android.app.AlertDialog.Builder(this@AboutActivity)
                        .setTitle(if (result.success) "✅ Demande envoyée" else "❌ Erreur")
                        .setMessage(result.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()

    /**
     * Affiche un dialogue plein-écran avec le QR Code de la clé appareil.
     * L'administrateur n'a qu'à pointer la caméra de son écran (panel web)
     * sur ce QR pour remplir automatiquement le champ clé — sans saisie manuelle.
     */
    private fun showDeviceKeyQrDialog(deviceKey: String) {
        val qrBitmap = QrCodeGenerator.generateForDeviceKey(deviceKey)
        if (qrBitmap == null) {
            Toast.makeText(this, "Impossible de générer le QR Code.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Construction du layout programmatiquement (pas besoin d'un XML dédié)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "📱 QR Code — Clé appareil"
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            val mb = (4 * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, mb)
        }

        val subtitle = TextView(this).apply {
            text = "Montrez ce QR à votre administrateur.\nIl le scannera depuis le panel web."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            val mb = (16 * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, mb)
        }

        val qrView = ImageView(this).apply {
            setImageBitmap(qrBitmap)
            val size = (260 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

        val keyLabel = TextView(this).apply {
            text = deviceKey
            textSize = 15f
            letterSpacing = 0.15f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            val mt = (14 * resources.displayMetrics.density).toInt()
            setPadding(0, mt, 0, 0)
        }

        val closeBtn = android.widget.Button(this).apply {
            text = "Fermer"
            val mt = (16 * resources.displayMetrics.density).toInt()
            setPadding(0, mt, 0, 0)
            setOnClickListener { dialog.dismiss() }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(qrView)
        root.addView(keyLabel)
        root.addView(closeBtn)

        dialog.setContentView(root)
        dialog.window?.setLayout(
            (300 * resources.displayMetrics.density).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
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

