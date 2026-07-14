package com.solplay.iptv

import android.app.Activity
import android.app.AlertDialog
import android.view.LayoutInflater

/**
 * Popup d'avertissement légal affiché à chaque lancement de l'application.
 * Se ferme uniquement via la croix (aucune fermeture automatique).
 */
object DisclaimerDialog {

    fun showIfNeeded(activity: Activity) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_disclaimer, null)

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(false)
            .create()

        view.findViewById<android.widget.ImageButton>(R.id.btnCloseDisclaimer)
            .setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
}
