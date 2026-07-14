package com.solplay.iptv

import android.content.Context
import com.jakewharton.picasso.OkHttp3Downloader
import com.squareup.picasso.Picasso
import okhttp3.OkHttpClient

/**
 * Instance Picasso partagée, configurée pour envoyer un en-tête User-Agent
 * "navigateur" sur chaque requête d'image.
 *
 * Pourquoi : beaucoup de fournisseurs IPTV protègent leurs logos de chaîne
 * contre le hotlinking et rejettent silencieusement (ou renvoient une image
 * vide/erreur) toute requête sans User-Agent "légitime". Picasso, par défaut,
 * n'envoie aucun en-tête particulier : c'est la cause la plus fréquente des
 * icônes de chaîne qui ne s'affichent jamais.
 */
object ImageLoader {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; SM-A205U) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"

    @Volatile
    private var instance: Picasso? = null

    fun get(context: Context): Picasso {
        return instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(context: Context): Picasso {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .build()

        return Picasso.Builder(context)
            .downloader(OkHttp3Downloader(client))
            .build()
    }
}
