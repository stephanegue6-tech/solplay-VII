package com.solplay.iptv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

class ChannelAdapter(
    private var channels: List<Channel>,
    private val itemLayoutRes: Int = R.layout.item_channel,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    // Scope propre à l'adapter pour les recherches TMDB en tâche de fond ;
    // annulé quand la RecyclerView qui l'utilise est détruite (voir
    // onDetachedFromRecyclerView), ce qui annule aussi toutes les recherches
    // encore en vol pour des vues alors recyclées.
    private val adapterScope = CoroutineScope(Dispatchers.Main)

    class ChannelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.ivChannelLogo)
        val name: TextView = view.findViewById(R.id.tvChannelName)
        val group: TextView = view.findViewById(R.id.tvChannelGroup)
    }

    /** Remplace la liste affichée (utilisé par les filtres onglet/catégorie/recherche). */
    fun updateData(newChannels: List<Channel>) {
        channels = newChannels
        notifyDataSetChanged()
    }

    /** Liste actuellement affichée (utilisé pour transmettre le contexte au lecteur). */
    fun currentList(): List<Channel> = channels

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(itemLayoutRes, parent, false)
        return ChannelViewHolder(view)
    }

    // ⚠️ MODE DEBUG TEMPORAIRE : affiche le statut TMDB (OK / erreur / etc.)
    // à la place du group-title, pour diagnostiquer sans accès à Logcat/adb.
    // Remettre à false une fois le problème identifié et corrigé.
    private val showTmdbDebug = true

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = channels[position]
        holder.name.text = channel.name
        holder.group.text = channel.groupTitle ?: ""

        // Tag utilisé pour vérifier, à la fin de la recherche TMDB asynchrone,
        // que ce ViewHolder affiche toujours le même élément (il a pu être
        // recyclé pour une autre position pendant l'attente réseau).
        holder.itemView.tag = channel

        if (!channel.logoUrl.isNullOrEmpty()) {
            // Logo fourni par le M3U : on l'essaie d'abord, mais beaucoup de
            // playlists IPTV mettent un tvg-logo générique ou cassé sur les
            // films/séries. Si le chargement échoue (onError), on bascule
            // sur TMDB au lieu de rester sur l'icône par défaut.
            ImageLoader.get(holder.itemView.context).load(channel.logoUrl)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .into(holder.logo, object : com.squareup.picasso.Callback {
                    override fun onSuccess() {}
                    override fun onError(e: Exception?) {
                        loadTmdbFallback(holder, channel)
                    }
                })
        } else {
            holder.logo.setImageResource(R.drawable.ic_channel_placeholder)
            loadTmdbFallback(holder, channel)
        }

        holder.itemView.setOnClickListener { onClick(channel) }
    }

    /** Recherche une affiche TMDB pour les films/séries et l'applique si le ViewHolder affiche toujours ce channel. */
    private fun loadTmdbFallback(holder: ChannelViewHolder, channel: Channel) {
        val type = channel.contentType()
        if (type != ContentType.MOVIE && type != ContentType.SERIES) return

        if (showTmdbDebug) holder.group.text = "TMDB: recherche…"

        adapterScope.launch {
            val result = if (type == ContentType.MOVIE) {
                TmdbClient.searchMovie(channel.name)
            } else {
                TmdbClient.searchTv(channel.name)
            }

            // La vue a pu être recyclée pendant l'appel réseau : on
            // n'applique le résultat que si elle affiche toujours ce channel.
            if (holder.itemView.tag != channel) return@launch

            if (showTmdbDebug) holder.group.text = result.debugMessage

            val posterUrl = result.info?.posterUrl
            if (!posterUrl.isNullOrEmpty()) {
                ImageLoader.get(holder.itemView.context).load(posterUrl)
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .error(R.drawable.ic_channel_placeholder)
                    .into(holder.logo)
            }
        }
    }

    override fun getItemCount(): Int = channels.size

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.coroutineContext.cancelChildren()
    }
}
