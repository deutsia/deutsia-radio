package com.opensource.i2pradio.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.opensource.i2pradio.R
import com.opensource.i2pradio.data.RadioRepository
import com.opensource.i2pradio.data.RadioStation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AddEditRadioDialog : DialogFragment() {
    private lateinit var repository: RadioRepository
    private var stationToEdit: RadioStation? = null
    private var onSaveCallback: ((RadioStation) -> Unit)? = null

    companion object {
        fun newInstance(station: RadioStation? = null): AddEditRadioDialog {
            val dialog = AddEditRadioDialog()
            val args = Bundle()
            station?.let { args.putLong("station_id", it.id) }
            dialog.arguments = args
            return dialog
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        repository = RadioRepository(requireContext())

        val view = layoutInflater.inflate(R.layout.dialog_add_edit_radio, null)

        val nameInput = view.findViewById<TextInputEditText>(R.id.nameInput)
        val urlInput = view.findViewById<TextInputEditText>(R.id.urlInput)
        val genreInput = view.findViewById<AutoCompleteTextView>(R.id.genreInput)
        val useProxyCheckbox = view.findViewById<MaterialCheckBox>(R.id.useProxyCheckbox)
        val proxySettingsContainer = view.findViewById<View>(R.id.proxySettingsContainer)
        val proxyHostInput = view.findViewById<TextInputEditText>(R.id.proxyHostInput)
        val proxyPortInput = view.findViewById<TextInputEditText>(R.id.proxyPortInput)
        val coverArtInput = view.findViewById<TextInputEditText>(R.id.coverArtInput)

        // Setup genre dropdown
        val genres = arrayOf(
            "News", "Music", "Rock", "Pop", "Jazz", "Classical",
            "Electronic", "Hip Hop", "Talk", "Sports", "Other"
        )
        val genreAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, genres)
        genreInput.setAdapter(genreAdapter)
        genreInput.setText("Other", false)

        // Toggle proxy settings visibility
        useProxyCheckbox.setOnCheckedChangeListener { _, isChecked ->
            proxySettingsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Load station data if editing
        val stationId = arguments?.getLong("station_id", -1L) ?: -1L
        if (stationId != -1L) {
            CoroutineScope(Dispatchers.Main).launch {
                val station = repository.getStationById(stationId)
                station?.let {
                    stationToEdit = it
                    nameInput.setText(it.name)
                    urlInput.setText(it.streamUrl)
                    genreInput.setText(it.genre, false)
                    useProxyCheckbox.isChecked = it.useProxy
                    proxyHostInput.setText(it.proxyHost)
                    proxyPortInput.setText(it.proxyPort.toString())
                    coverArtInput.setText(it.coverArtUri ?: "")
                }
            }
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(if (stationToEdit == null) "Add Radio Station" else "Edit Radio Station")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString()
                val url = urlInput.text.toString()
                val genre = genreInput.text.toString().ifEmpty { "Other" }
                val useProxy = useProxyCheckbox.isChecked
                val proxyHost = if (useProxy) proxyHostInput.text.toString() else ""
                val proxyPort = if (useProxy) proxyPortInput.text.toString().toIntOrNull() ?: 4444 else 4444
                val coverArt = coverArtInput.text.toString().ifEmpty { null }

                if (name.isNotEmpty() && url.isNotEmpty()) {
                    val station = RadioStation(
                        id = stationToEdit?.id ?: 0,
                        name = name,
                        streamUrl = url,
                        genre = genre,
                        useProxy = useProxy,
                        proxyHost = proxyHost,
                        proxyPort = proxyPort,
                        coverArtUri = coverArt,
                        isPreset = stationToEdit?.isPreset ?: false
                    )

                    CoroutineScope(Dispatchers.IO).launch {
                        if (stationToEdit == null) {
                            repository.insertStation(station)
                        } else {
                            repository.updateStation(station)
                        }
                    }

                    onSaveCallback?.invoke(station)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    fun setOnSaveCallback(callback: (RadioStation) -> Unit) {
        onSaveCallback = callback
    }
}