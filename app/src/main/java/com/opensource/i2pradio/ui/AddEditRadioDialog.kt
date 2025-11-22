package com.opensource.i2pradio.ui

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
    private var selectedImageUri: Uri? = null

    private var coverArtPreview: ImageView? = null
    private var coverArtPreviewCard: MaterialCardView? = null
    private var clearImageButton: MaterialButton? = null
    private var coverArtInput: TextInputEditText? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            // Take persistable permission to keep access after app restart
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    selectedUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Permission might not be available for all content providers
            }

            selectedImageUri = selectedUri
            updateImagePreview(selectedUri.toString())
        }
    }

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
        coverArtInput = view.findViewById(R.id.coverArtInput)
        val pickImageButton = view.findViewById<MaterialButton>(R.id.pickImageButton)
        clearImageButton = view.findViewById(R.id.clearImageButton)
        coverArtPreview = view.findViewById(R.id.coverArtPreview)
        coverArtPreviewCard = view.findViewById(R.id.coverArtPreviewCard)

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

        // Image picker button
        pickImageButton.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // Clear image button
        clearImageButton?.setOnClickListener {
            selectedImageUri = null
            coverArtInput?.setText("")
            coverArtPreviewCard?.visibility = View.GONE
            clearImageButton?.visibility = View.GONE
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
                    it.coverArtUri?.let { uri ->
                        coverArtInput?.setText(uri)
                        updateImagePreview(uri)
                    }
                }
            }
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(if (stationId == -1L) "Add Radio Station" else "Edit Radio Station")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString()
                val url = urlInput.text.toString()
                val genre = genreInput.text.toString().ifEmpty { "Other" }
                val useProxy = useProxyCheckbox.isChecked
                val proxyHost = if (useProxy) proxyHostInput.text.toString() else ""
                val proxyPort = if (useProxy) proxyPortInput.text.toString().toIntOrNull() ?: 4444 else 4444

                // Prefer local image over URL
                val coverArt = selectedImageUri?.toString()
                    ?: coverArtInput?.text.toString().ifEmpty { null }

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

    private fun updateImagePreview(uri: String) {
        coverArtPreview?.load(uri) {
            crossfade(true)
            error(R.drawable.ic_radio)
        }
        coverArtPreviewCard?.visibility = View.VISIBLE
        clearImageButton?.visibility = View.VISIBLE
    }

    fun setOnSaveCallback(callback: (RadioStation) -> Unit) {
        onSaveCallback = callback
    }
}
