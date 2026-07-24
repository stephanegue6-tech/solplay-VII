package com.solplay.iptv

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Génère un QR Code sous forme de Bitmap à partir d'un texte.
 *
 * Utilisé dans AboutActivity pour afficher la clé appareil sous forme de QR,
 * afin que l'administrateur puisse la scanner depuis le panel web au lieu de
 * la saisir manuellement caractère par caractère.
 *
 * Le format du contenu encodé est "SOLPLAY:<clé>" (ex: "SOLPLAY:A1B2C3D4E5F6G7H8")
 * pour que le scanner du panel web puisse le distinguer d'un QR quelconque et
 * extraire uniquement la clé proprement.
 */
object QrCodeGenerator {

    /**
     * @param deviceKey  La clé appareil brute (16 caractères, ex: "A1B2C3D4E5F6G7H8").
     * @param sizePx     Taille du Bitmap carré en pixels (par défaut 512).
     * @return           Un Bitmap noir sur fond blanc, ou null en cas d'erreur.
     */
    fun generateForDeviceKey(deviceKey: String, sizePx: Int = 512): Bitmap? {
        return try {
            val content = "SOLPLAY:$deviceKey"
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1          // marge minimale (quiet zone)
            )
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
