package com.opensource.i2pradio.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.opensource.i2pradio.R
import com.opensource.i2pradio.data.RadioStation

/**
 * Bottom sheet that surfaces the three-layer queue:
 *   - Skip Previous / Next walk the manual queue + context list.
 *   - The Discover toggle is the user-facing opt-in for the third layer.
 *   - The "Up next" list shows manual queue entries in the order they'll
 *     play, and lets the user remove or jump to one directly.
 */
class QueueBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: RadioViewModel by activityViewModels()

    private lateinit var subtitle: TextView
    private lateinit var skipNextButton: MaterialButton
    private lateinit var skipPreviousButton: MaterialButton
    private lateinit var discoverSwitch: MaterialSwitch
    private lateinit var clearButton: MaterialButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: QueueAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.bottom_sheet_queue, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        subtitle = view.findViewById(R.id.queueSubtitle)
        skipNextButton = view.findViewById(R.id.skipNextButton)
        skipPreviousButton = view.findViewById(R.id.skipPreviousButton)
        discoverSwitch = view.findViewById(R.id.queueDiscoverSwitch)
        clearButton = view.findViewById(R.id.clearQueueButton)
        recyclerView = view.findViewById(R.id.queueRecyclerView)
        emptyText = view.findViewById(R.id.queueEmptyText)

        adapter = QueueAdapter(
            onPlay = { station ->
                viewModel.playFromQueue(station)
                dismiss()
            },
            onRemove = { index ->
                viewModel.removeFromManualQueueAt(index)
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        skipNextButton.setOnClickListener {
            viewModel.skipNext()
            dismiss()
        }

        skipPreviousButton.setOnClickListener {
            viewModel.skipPrevious()
            dismiss()
        }

        clearButton.setOnClickListener {
            viewModel.clearManualQueue()
        }

        discoverSwitch.isChecked = PreferencesHelper.isDiscoverEnabled(requireContext())
        discoverSwitch.setOnCheckedChangeListener { _, isChecked ->
            PreferencesHelper.setDiscoverEnabled(requireContext(), isChecked)
            updateSubtitle()
        }

        viewModel.manualQueue.observe(viewLifecycleOwner) { queue ->
            adapter.submit(queue)
            val empty = queue.isEmpty()
            recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
            emptyText.visibility = if (empty) View.VISIBLE else View.GONE
            clearButton.visibility = if (empty) View.GONE else View.VISIBLE
            updateSubtitle()
        }
    }

    private fun updateSubtitle() {
        val manualSize = viewModel.manualQueue.value?.size ?: 0
        val discoverOn = PreferencesHelper.isDiscoverEnabled(requireContext())
        val parts = mutableListOf<String>()
        if (manualSize > 0) {
            parts += resources.getQuantityString(
                R.plurals.queue_subtitle_count,
                manualSize,
                manualSize
            )
        }
        if (discoverOn) parts += getString(R.string.queue_subtitle_discover_on)
        subtitle.text = parts.joinToString(" • ")
        subtitle.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    private class QueueAdapter(
        private val onPlay: (RadioStation) -> Unit,
        private val onRemove: (Int) -> Unit
    ) : RecyclerView.Adapter<QueueAdapter.ViewHolder>() {

        private var items: List<RadioStation> = emptyList()

        fun submit(newItems: List<RadioStation>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_queue_station, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val name: TextView = itemView.findViewById(R.id.queueItemName)
            private val genre: TextView = itemView.findViewById(R.id.queueItemGenre)
            private val remove: MaterialButton = itemView.findViewById(R.id.queueItemRemove)

            fun bind(station: RadioStation) {
                name.text = station.name
                genre.text = station.genre
                itemView.setOnClickListener { onPlay(station) }
                remove.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onRemove(pos)
                }
            }
        }
    }
}
