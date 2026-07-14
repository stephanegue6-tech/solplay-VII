package com.solplay.iptv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Colonne de gauche : liste des bouquets (catégories). Un seul est sélectionné à la fois,
 * ce qui met à jour la colonne de droite (chaînes de ce bouquet) via [onClick].
 */
class BouquetAdapter(
    private var bouquets: List<Bouquet>,
    private val onClick: (Bouquet) -> Unit
) : RecyclerView.Adapter<BouquetAdapter.BouquetViewHolder>() {

    private var selectedName: String? = null

    class BouquetViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvBouquetName)
        val count: TextView = view.findViewById(R.id.tvBouquetCount)
    }

    fun updateData(newBouquets: List<Bouquet>, selected: String?) {
        bouquets = newBouquets
        selectedName = selected
        notifyDataSetChanged()
    }

    fun setSelected(name: String) {
        selectedName = name
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BouquetViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bouquet, parent, false)
        return BouquetViewHolder(view)
    }

    override fun onBindViewHolder(holder: BouquetViewHolder, position: Int) {
        val bouquet = bouquets[position]
        holder.name.text = bouquet.name
        holder.count.text = bouquet.channelCount.toString()
        holder.itemView.isSelected = bouquet.name == selectedName
        holder.itemView.setOnClickListener { onClick(bouquet) }
    }

    override fun getItemCount(): Int = bouquets.size
}
