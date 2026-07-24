package com.solplay.iptv

import android.content.Context

/**
 * Détecte les chaînes/bouquets "adultes" (à partir de leur nom de catégorie
 * ou de chaîne) et verrouille leur accès derrière un code parental à 4
 * chiffres, par défaut "0000".
 *
 * Détection par mots-clés : la quasi-totalité des playlists IPTV (M3U ou
 * Xtream) rangent ce type de contenu dans des catégories explicitement
 * nommées ("XXX", "ADULT", "+18"...) plutôt que de le mélanger discrètement
 * au reste - une simple recherche de mots-clés dans groupTitle (et en
 * repli le nom de la chaîne elle-même) couvre donc la grande majorité des
 * cas réels, sans avoir besoin d'analyser le flux vidéo.
 *
 * Le déverrouillage est volontairement gardé UNIQUEMENT en mémoire
 * (variable d'objet, pas de SharedPreferences) : il ne survit pas à la
 * fermeture de l'app. Un code correct déverrouille tout le contenu adulte
 * pour le reste de la session en cours, mais le prochain lancement de
 * l'app redemande le code - comportement volontaire pour un contrôle
 * parental (on ne veut pas qu'un déverrouillage devienne permanent à
 * l'insu de la personne qui gère le code).
 */
object ParentalControl {

    private const val PREFS = "solplay_prefs"
    private const val KEY_PIN = "parental_pin"
    const val DEFAULT_PIN = "0000"

    private val ADULT_KEYWORDS = listOf(
        "XXX", "XX", "ADULT", "ADULTE", "ADULTES", "ADULTS",
        "FILM POUR ADULTES", "FILMS POUR ADULTES",
        "PORN", "PORNO", "PORNOGRAPHIE", "PORNOGRAPHIQUE",
        "EROTIC", "EROTIQUE", "EROTIK", "SEXE", "SEX",
        "18+", "+18", "PLAYBOY", "BRAZZERS", "HUSTLER", "REDLIGHT"
    )

    /** Vrai si ce nom de catégorie/bouquet/chaîne correspond à du contenu adulte. */
    fun isAdultLabel(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        val upper = label.uppercase()
        return ADULT_KEYWORDS.any { keyword -> matches(upper, keyword) }
    }

    /**
     * Pour un mot-clé purement alphabétique (ex: "XX", "SEXE"), exige une
     * correspondance de MOT ENTIER (bordures \b) plutôt qu'une simple
     * sous-chaîne : "XX" ne doit pas verrouiller une chaîne nommée "XXL Sport"
     * ou "TAXX", par exemple. Les mots-clés contenant un symbole (ex: "+18")
     * restent en simple sous-chaîne, le symbole jouant déjà naturellement le
     * rôle de séparateur dans la plupart des noms de catégories réels.
     */
    private fun matches(upperLabel: String, keyword: String): Boolean {
        val isPureAlpha = keyword.all { it.isLetter() || it == ' ' }
        return if (isPureAlpha) {
            Regex("\\b${Regex.escape(keyword)}\\b").containsMatchIn(upperLabel)
        } else {
            upperLabel.contains(keyword)
        }
    }

    /** Vrai si cette chaîne est adulte, d'après sa catégorie (priorité) ou à défaut son propre nom. */
    fun isAdultChannel(channel: Channel): Boolean =
        isAdultLabel(channel.groupTitle) || isAdultLabel(channel.name)

    // --- Code PIN (persisté, modifiable depuis l'app) ---

    fun getPin(context: Context): String =
        prefs(context).getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN

    fun setPin(context: Context, newPin: String) {
        prefs(context).edit().putString(KEY_PIN, newPin).apply()
    }

    fun verifyPin(context: Context, input: String): Boolean = input == getPin(context)

    // --- Déverrouillage en mémoire, valable pour la session en cours ---

    @Volatile
    private var unlockedForSession: Boolean = false

    fun isUnlocked(): Boolean = unlockedForSession

    fun unlock() {
        unlockedForSession = true
    }

    /** Reverrouille explicitement (ex: bouton "Verrouiller" dans les réglages). */
    fun lock() {
        unlockedForSession = false
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
