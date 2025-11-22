package com.opensource.i2pradio.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.opensource.i2pradio.R
import com.opensource.i2pradio.RadioService
import com.opensource.i2pradio.data.ProxyType
import com.opensource.i2pradio.data.RadioStation
import com.opensource.i2pradio.data.RadioRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RadiosFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateContainer: View
    private lateinit var fabAddRadio: FloatingActionButton
    private lateinit var adapter: RadioStationAdapter
    private lateinit var repository: RadioRepository
    private val viewModel: RadioViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_radios, container, false)

        repository = RadioRepository(requireContext())

        recyclerView = view.findViewById(R.id.radiosRecyclerView)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
        fabAddRadio = view.findViewById(R.id.fabAddRadio)

        adapter = RadioStationAdapter(
            onStationClick = { station -> playStation(station) },
            onMenuClick = { station, anchor -> showStationMenu(station, anchor) }
        )
        recyclerView.adapter = adapter

        // Observe radio stations from database
        repository.allStations.observe(viewLifecycleOwner) { stations ->
            if (stations.isEmpty()) {
                recyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.VISIBLE
            } else {
                recyclerView.visibility = View.VISIBLE
                emptyStateContainer.visibility = View.GONE
                adapter.submitList(stations)
            }
        }

        fabAddRadio.setOnClickListener {
            showAddRadioDialog()
        }

        view.findViewById<MaterialButton>(R.id.emptyStateAddButton).setOnClickListener {
            showAddRadioDialog()
        }

        return view
    }

    private fun playStation(station: RadioStation) {
        viewModel.setCurrentStation(station)
        viewModel.setPlaying(true)

        val proxyType = station.getProxyTypeEnum()
        val intent = Intent(requireContext(), RadioService::class.java).apply {
            action = RadioService.ACTION_PLAY
            putExtra("stream_url", station.streamUrl)
            putExtra("station_name", station.name)
            putExtra("proxy_host", if (station.useProxy) station.proxyHost else "")
            putExtra("proxy_port", station.proxyPort)
            putExtra("proxy_type", proxyType.name)
            putExtra("cover_art_uri", station.coverArtUri)
        }
        requireContext().startService(intent)
    }

    private fun showAddRadioDialog() {
        val dialog = AddEditRadioDialog.newInstance()
        dialog.show(parentFragmentManager, "AddEditRadioDialog")
    }

    private fun showStationMenu(station: RadioStation, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.station_menu, popup.menu)

        // Remove this block - allow deleting all stations
        // if (station.isPreset) {
        //     popup.menu.findItem(R.id.action_delete)?.isVisible = false
        // }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit -> {
                    val dialog = AddEditRadioDialog.newInstance(station)
                    dialog.show(parentFragmentManager, "AddEditRadioDialog")
                    true
                }
                R.id.action_delete -> {
                    CoroutineScope(Dispatchers.IO).launch {
                        repository.deleteStation(station)
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
}

// Updated Adapter
class RadioStationAdapter(
    private val onStationClick: (RadioStation) -> Unit,
    private val onMenuClick: (RadioStation, View) -> Unit
) : RecyclerView.Adapter<RadioStationAdapter.ViewHolder>() {

    private var stations = listOf<RadioStation>()

    fun submitList(newStations: List<RadioStation>) {
        stations = newStations
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_radio_station, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(stations[position])
    }

    override fun getItemCount() = stations.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val coverArt: ImageView = itemView.findViewById(R.id.coverArtImage)
        private val stationName: TextView = itemView.findViewById(R.id.stationNameText)
        private val genreText: TextView = itemView.findViewById(R.id.genreText)
        private val menuButton: MaterialButton = itemView.findViewById(R.id.menuButton)

        fun bind(station: RadioStation) {
            stationName.text = station.name

            // Show proxy type indicator (I2P or Tor)
            val proxyIndicator = if (station.useProxy) {
                when (station.getProxyTypeEnum()) {
                    ProxyType.I2P -> " • I2P"
                    ProxyType.TOR -> " • Tor"
                    ProxyType.NONE -> ""
                }
            } else ""
            genreText.text = "${station.genre}$proxyIndicator"

            if (station.coverArtUri != null) {
                coverArt.load(station.coverArtUri) {
                    crossfade(true)
                    placeholder(R.drawable.ic_radio)
                    error(R.drawable.ic_radio)
                }
            } else {
                coverArt.setImageResource(R.drawable.ic_radio)
            }

            itemView.setOnClickListener {
                onStationClick(station)
            }

            menuButton.setOnClickListener {
                onMenuClick(station, it)
            }
        }
    }
}