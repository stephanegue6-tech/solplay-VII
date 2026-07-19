package com.solplay.desktop.core

import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64

/**
 * Chiffrement au repos des données sensibles (identifiants Xtream) via
 * l'API Windows DPAPI (Data Protection API) — utilisée nativement par
 * Windows pour protéger, entre autres, les mots de passe Wi-Fi enregistrés
 * ou les certificats utilisateur.
 *
 * Pourquoi DPAPI plutôt qu'une clé "maison" gérée par l'app : DPAPI chiffre
 * avec une clé dérivée du compte Windows de l'utilisateur, gérée entièrement
 * par le système d'exploitation — cette clé n'est JAMAIS stockée dans nos
 * propres fichiers. Un fichier `saved_playlists` chiffré copié sur une autre
 * machine, ou lu par un autre utilisateur Windows de la même machine (autre
 * session), est illisible : ni nous ni un attaquant qui récupère juste le
 * fichier n'avons besoin (ni la possibilité) de gérer un mot de passe maître.
 * C'est l'équivalent desktop le plus proche de l'Android Keystore utilisé
 * côté mobile (EncryptedSharedPreferences) — voir la limite documentée dans
 * PlaylistStore.kt et le README, à laquelle ce fichier répond.
 *
 * Portée "CurrentUser" (comportement par défaut de Crypt32Util, le plus
 * restrictif des deux scopes DPAPI) : déchiffrable uniquement par la même
 * session utilisateur Windows qui a chiffré, pas par les autres comptes de
 * la machine.
 */
object SecureStorage {

    // "Entropie" additionnelle DPAPI (secret applicatif, PAS une clé de
    // chiffrement à elle seule) : empêche qu'une donnée chiffrée par
    // SolPlay soit déchiffrable par une autre application qui utiliserait
    // aussi DPAPI pour le même utilisateur Windows, sans changer le niveau
    // de protection global (qui reste porté par la clé DPAPI liée au
    // compte Windows).
    private val ENTROPY = "SolPlayDesktop-v1".toByteArray(Charsets.UTF_8)

    /**
     * Chiffre [plainText] et renvoie le résultat encodé en Base64 (format
     * texte, prêt à être stocké tel quel dans le JSON de PlaylistStore).
     * Chaîne vide -> chaîne vide (évite un aller-retour DPAPI inutile pour
     * les champs optionnels non renseignés).
     */
    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val encryptedBytes = Crypt32Util.cryptProtectData(
            plainText.toByteArray(Charsets.UTF_8), ENTROPY, 0, "SolPlayDesktop", null
        )
        return Base64.getEncoder().encodeToString(encryptedBytes)
    }

    /**
     * Déchiffre une valeur produite par [encrypt]. Renvoie une chaîne vide
     * en cas d'échec (donnée corrompue, ou fichier copié depuis une autre
     * machine/session Windows — DPAPI refuse alors le déchiffrement, par
     * design) plutôt que de lever une exception : l'appelant (PlaylistStore)
     * traite ce cas comme "à ressaisir par l'utilisateur", jamais comme un
     * crash de l'application.
     */
    fun decrypt(cipherTextBase64: String): String {
        if (cipherTextBase64.isEmpty()) return ""
        return try {
            val encryptedBytes = Base64.getDecoder().decode(cipherTextBase64)
            val decryptedBytes = Crypt32Util.cryptUnprotectData(encryptedBytes, ENTROPY, 0, null)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
