package com.solplay.iptv

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.solplay.iptv.databinding.ActivityChannelsBinding

class ChannelsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNELS = "extra_channels"
        const val EXTRA_INITIAL_TYPE = "extra_initial_type"
        private const val ALL_BOUQUETS = "Tous"
    }

    private lateinit var binding: ActivityChannelsBinding
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var bouquetAdapter: BouquetAdapter

    private var allChannels: List<Channel> = emptyList()
    private var currentType: ContentType = ContentType.LIVE
    private var currentBouquet: String = ALL_BOUQUETS
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        allChannels = ChannelRepository.channels

        channelAdapter = ChannelAdapter(emptyList()) { channel -> openPlayer(channel) }
        binding.recyclerChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerChannels.adapter = channelAdapter

        bouquetAdapter = BouquetAdapter(emptyList()) { bouquet ->
            currentBouquet = bouquet.name
            bouquetAdapter.setSelected(bouquet.name)
            applyFilters()
        }
        binding.recyclerBouquets.layoutManager = LinearLayoutManager(this)
        binding.recyclerBouquets.adapter = bouquetAdapter

        setupTabs()
        setupSearch()

        when (intent.getStringExtra(EXTRA_INITIAL_TYPE)) {
            ContentType.MOVIE.name -> binding.tabLayout.getTabAt(1)?.select()
            ContentType.SERIES.name -> binding.tabLayout.getTabAt(2)?.select()
            else -> binding.tabLayout.getTabAt(0)?.select()
        }

        refreshBouquets()
        applyFilters()
    }

    private fun openPlayer(channel: Channel) {
        ChannelRepository.setPlayingList(channelsForCurrentType())
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
        intent.putExtra(PlayerActivity.EXTRA_STREAM_NAME, channel.name)
        startActivity(intent)
    }

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
                currentBouquet = ALL_BOUQUETS
                refreshBouquets()
                applyFilters()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun channelsForCurrentType(): List<Channel> =
        allChannels.filter { it.contentType() == currentType }

    private fun refreshBouquets() {
        val channels = channelsForCurrentType()
        val bouquets = mutableListOf(Bouquet(ALL_BOUQUETS, channels.size))

        bouquets += channels
            .mapNotNull { it.groupTitle?.trim()?.takeIf { g -> g.isNotEmpty() } }
            .groupingBy { it }
            .eachCount()
            .toSortedMap()
            .map { (name, count) -> Bouquet(name, count) }

        bouquetAdapter.updateData(bouquets, currentBouquet)
    }

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

    private fun applyFilters() {
        var list = channelsForCurrentType()

        if (currentBouquet != ALL_BOUQUETS) {
            list = list.filter { it.groupTitle?.trim() == currentBouquet }
        }
        if (currentQuery.isNotEmpty()) {
            list = list.filter { it.name.contains(currentQuery, ignoreCase = true) }
        }

        channelAdapter.updateData(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }
}
