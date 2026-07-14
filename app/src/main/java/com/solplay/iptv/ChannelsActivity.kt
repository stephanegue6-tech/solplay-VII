package com.solplay.iptv

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.solplay.iptv.databinding.ActivityChannelsBinding

class ChannelsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNELS = "extra_channels"
        const val EXTRA_INITIAL_TYPE = "extra_initial_type"
        private const val ALL_CATEGORIES = "Toutes les catégories"
    }

    private lateinit var binding: ActivityChannelsBinding
    private lateinit var adapter: ChannelAdapter

    private var allChannels: List<Channel> = emptyList()
    private var currentType: ContentType = ContentType.LIVE
    private var currentCategory: String = ALL_CATEGORIES
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        allChannels = ChannelRepository.channels

        adapter = ChannelAdapter(emptyList()) { channel ->
            // On mémorise la liste actuellement filtrée (onglet + catégorie + recherche)
            // pour que le lecteur puisse proposer les autres chaînes sans qu'on ait à sortir.
            ChannelRepository.setPlayingList(adapter.currentList())
            val intent = Intent(this, PlayerActivity::class.java)
            intent.putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
            intent.putExtra(PlayerActivity.EXTRA_STREAM_NAME, channel.name)
            startActivity(intent)
        }
        binding.recyclerChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerChannels.adapter = adapter

        setupTabs()
        setupCategorySpinner()
        setupSearch()

        // Si on arrive depuis l'écran d'accueil avec un choix précis (Live TV / Films
        // / Séries), on présélectionne directement l'onglet correspondant.
        when (intent.getStringExtra(EXTRA_INITIAL_TYPE)) {
            ContentType.MOVIE.name -> binding.tabLayout.getTabAt(1)?.select()
            ContentType.SERIES.name -> binding.tabLayout.getTabAt(2)?.select()
            else -> binding.tabLayout.getTabAt(0)?.select()
        }

        applyFilters()
    }

    /** Les 3 onglets Live / Films / Séries. */
    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Live"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Films"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Séries"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentType = when (tab.position) {
                    1 -> ContentType.MOVIE
                    2 -> ContentType.SERIES
                    else -> ContentType.LIVE
                }
                currentCategory = ALL_CATEGORIES
                updateCategorySpinner()
                applyFilters()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun channelsForCurrentType(): List<Channel> =
        allChannels.filter { it.contentType() == currentType }

    /** Liste déroulante des catégories (group-title), propre à l'onglet sélectionné. */
    private fun setupCategorySpinner() {
        updateCategorySpinner()
        binding.spinnerCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                @Suppress("UNCHECKED_CAST")
                val items = parent?.adapter as? ArrayAdapter<String> ?: return
                currentCategory = items.getItem(position) ?: ALL_CATEGORIES
                applyFilters()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateCategorySpinner() {
        val categories = mutableListOf(ALL_CATEGORIES)
        categories += channelsForCurrentType()
            .mapNotNull { it.groupTitle?.trim()?.takeIf { g -> g.isNotEmpty() } }
            .distinct()
            .sorted()

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCategory.adapter = spinnerAdapter
    }

    /** Barre de recherche par nom de chaîne, dans l'onglet/catégorie courants. */
    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString()?.trim() ?: ""
                applyFilters()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    /** Combine onglet + catégorie + recherche, puis rafraîchit la liste affichée. */
    private fun applyFilters() {
        var list = channelsForCurrentType()

        if (currentCategory != ALL_CATEGORIES) {
            list = list.filter { it.groupTitle == currentCategory }
        }
        if (currentQuery.isNotEmpty()) {
            list = list.filter { it.name.contains(currentQuery, ignoreCase = true) }
        }

        adapter.updateData(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }
}
