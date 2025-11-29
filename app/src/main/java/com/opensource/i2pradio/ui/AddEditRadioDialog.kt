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
import com.opensource.i2pradio.util.loadSecure
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.opensource.i2pradio.R
import com.opensource.i2pradio.data.ProxyType
import com.opensource.i2pradio.data.ProxyProtocol
import com.opensource.i2pradio.data.ProxyAuthType
import com.opensource.i2pradio.data.RadioRepository
import com.opensource.i2pradio.data.RadioStation
import androidx.lifecycle.lifecycleScope
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

    // Custom proxy fields
    private var customProxyContainer: View? = null
    private var customProxyProtocolInput: AutoCompleteTextView? = null
    private var proxyUsernameInput: TextInputEditText? = null
    private var proxyPasswordInput: TextInputEditText? = null
    private var proxyAuthTypeInput: AutoCompleteTextView? = null
    private var advancedProxyContainer: View? = null
    private var proxyConnectionTimeoutInput: TextInputEditText? = null

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
        val proxyTypeInput = view.findViewById<AutoCompleteTextView>(R.id.proxyTypeInput)
        val proxySettingsContainer = view.findViewById<View>(R.id.proxySettingsContainer)
        val embeddedTorInfoContainer = view.findViewById<View>(R.id.embeddedTorInfoContainer)
        val proxyHostInput = view.findViewById<TextInputEditText>(R.id.proxyHostInput)
        val proxyPortInput = view.findViewById<TextInputEditText>(R.id.proxyPortInput)
        coverArtInput = view.findViewById(R.id.coverArtInput)
        val pickImageButton = view.findViewById<MaterialButton>(R.id.pickImageButton)
        clearImageButton = view.findViewById(R.id.clearImageButton)
        coverArtPreview = view.findViewById(R.id.coverArtPreview)
        coverArtPreviewCard = view.findViewById(R.id.coverArtPreviewCard)

        // Check if embedded Tor is enabled
        val isEmbeddedTorEnabled = PreferencesHelper.isEmbeddedTorEnabled(requireContext())

        // Setup genre dropdown - expanded list sorted alphabetically
        val genres = arrayOf(
            "Alternative", "Ambient", "Blues", "Christian", "Classical",
            "Comedy", "Country", "Dance", "EDM", "Electronic", "Folk",
            "Funk", "Gospel", "Hip Hop", "Indie", "Jazz", "K-Pop",
            "Latin", "Lo-Fi", "Metal", "News", "Oldies", "Pop", "Punk",
            "R&B", "Reggae", "Rock", "Soul", "Sports", "Talk", "World", "Other"
        )
        val genreAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, genres)
        genreInput.setAdapter(genreAdapter)
        genreInput.setText("Other", false)

        // Setup proxy type dropdown
        val proxyTypes = arrayOf("None", "I2P", "Tor", "Custom")
        val proxyTypeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, proxyTypes)
        proxyTypeInput.setAdapter(proxyTypeAdapter)
        proxyTypeInput.setText("None", false)

        // Initialize custom proxy fields
        customProxyContainer = view.findViewById(R.id.customProxyContainer)
        customProxyProtocolInput = view.findViewById(R.id.customProxyProtocolInput)
        proxyUsernameInput = view.findViewById(R.id.proxyUsernameInput)
        proxyPasswordInput = view.findViewById(R.id.proxyPasswordInput)
        proxyAuthTypeInput = view.findViewById(R.id.proxyAuthTypeInput)
        advancedProxyContainer = view.findViewById(R.id.advancedProxyContainer)
        proxyConnectionTimeoutInput = view.findViewById(R.id.proxyConnectionTimeoutInput)

        // Setup custom proxy protocol dropdown
        val protocols = arrayOf("HTTP", "HTTPS", "SOCKS4", "SOCKS5")
        val protocolAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, protocols)
        customProxyProtocolInput?.setAdapter(protocolAdapter)
        customProxyProtocolInput?.setText("HTTP", false)

        // Setup auth type dropdown
        val authTypes = arrayOf("None", "Basic", "Digest")
        val authTypeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, authTypes)
        proxyAuthTypeInput?.setAdapter(authTypeAdapter)
        proxyAuthTypeInput?.setText("None", false)

        // Toggle proxy settings visibility based on proxy type selection
        proxyTypeInput.setOnItemClickListener { _, _, position, _ ->
            val selectedType = proxyTypes[position]
            when {
                selectedType == "None" -> {
                    proxySettingsContainer.visibility = View.GONE
                    embeddedTorInfoContainer?.visibility = View.GONE
                    customProxyContainer?.visibility = View.GONE
                }
                selectedType == "Tor" && isEmbeddedTorEnabled -> {
                    // Show embedded Tor info, hide manual proxy settings
                    embeddedTorInfoContainer?.visibility = View.VISIBLE
                    proxySettingsContainer.visibility = View.GONE
                    customProxyContainer?.visibility = View.GONE
                }
                selectedType == "Custom" -> {
                    // Show both basic and custom proxy settings
                    embeddedTorInfoContainer?.visibility = View.GONE
                    proxySettingsContainer.visibility = View.VISIBLE
                    customProxyContainer?.visibility = View.VISIBLE
                    // Set default values for custom proxy
                    proxyHostInput.setText("")
                    proxyPortInput.setText("8080")
                    customProxyProtocolInput?.setText("HTTP", false)
                    proxyAuthTypeInput?.setText("None", false)
                    proxyConnectionTimeoutInput?.setText("30")
                }
                else -> {
                    // Show manual proxy settings for I2P or when embedded Tor is disabled
                    embeddedTorInfoContainer?.visibility = View.GONE
                    proxySettingsContainer.visibility = View.VISIBLE
                    customProxyContainer?.visibility = View.GONE
                    val proxyType = ProxyType.fromString(selectedType)
                    proxyHostInput.setText(proxyType.getDefaultHost())
                    proxyPortInput.setText(proxyType.getDefaultPort().toString())
                }
            }
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
            lifecycleScope.launch(Dispatchers.Main) {
                val station = repository.getStationById(stationId)
                station?.let {
                    stationToEdit = it
                    nameInput.setText(it.name)
                    urlInput.setText(it.streamUrl)
                    genreInput.setText(it.genre, false)

                    // Set proxy type dropdown
                    val proxyType = it.getProxyTypeEnum()
                    val proxyTypeDisplay = when (proxyType) {
                        ProxyType.I2P -> "I2P"
                        ProxyType.TOR -> "Tor"
                        ProxyType.CUSTOM -> "Custom"
                        ProxyType.NONE -> "None"
                    }
                    proxyTypeInput.setText(proxyTypeDisplay, false)

                    // Show proxy settings if using a proxy
                    if (it.useProxy && proxyType != ProxyType.NONE) {
                        if (proxyType == ProxyType.TOR && isEmbeddedTorEnabled) {
                            // Show embedded Tor info for Tor stations when embedded Tor is enabled
                            embeddedTorInfoContainer?.visibility = View.VISIBLE
                            proxySettingsContainer.visibility = View.GONE
                            customProxyContainer?.visibility = View.GONE
                        } else if (proxyType == ProxyType.CUSTOM) {
                            // Show custom proxy settings
                            proxySettingsContainer.visibility = View.VISIBLE
                            customProxyContainer?.visibility = View.VISIBLE
                            embeddedTorInfoContainer?.visibility = View.GONE
                            proxyHostInput.setText(it.proxyHost)
                            proxyPortInput.setText(it.proxyPort.toString())

                            // Load custom proxy fields
                            customProxyProtocolInput?.setText(it.customProxyProtocol, false)
                            proxyUsernameInput?.setText(it.proxyUsername)
                            proxyPasswordInput?.setText(it.proxyPassword)
                            proxyAuthTypeInput?.setText(it.proxyAuthType, false)
                            proxyConnectionTimeoutInput?.setText(it.proxyConnectionTimeout.toString())
                        } else {
                            // Show basic proxy settings for I2P or manual Tor
                            proxySettingsContainer.visibility = View.VISIBLE
                            customProxyContainer?.visibility = View.GONE
                            embeddedTorInfoContainer?.visibility = View.GONE
                            proxyHostInput.setText(it.proxyHost)
                            proxyPortInput.setText(it.proxyPort.toString())
                        }
                    }

                    it.coverArtUri?.let { uri ->
                        coverArtInput?.setText(uri)
                        updateImagePreview(uri)
                    }
                }
            }
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(if (stationId == -1L) getString(R.string.dialog_add_station) else getString(R.string.dialog_edit_station))
            .setView(view)
            .setPositiveButton(getString(R.string.button_save)) { _, _ ->
                val name = nameInput.text.toString()
                val url = urlInput.text.toString()
                val genre = genreInput.text.toString().ifEmpty { "Other" }

                // Get proxy type from dropdown
                val proxyTypeText = proxyTypeInput.text.toString()
                val proxyType = when (proxyTypeText) {
                    "I2P" -> ProxyType.I2P
                    "Tor" -> ProxyType.TOR
                    "Custom" -> ProxyType.CUSTOM
                    else -> ProxyType.NONE
                }
                val useProxy = proxyType != ProxyType.NONE
                val proxyHost = if (useProxy) proxyHostInput.text.toString() else ""
                val proxyPort = if (useProxy) proxyPortInput.text.toString().toIntOrNull() ?: proxyType.getDefaultPort() else proxyType.getDefaultPort()

                // Get custom proxy fields
                val customProxyProtocol = if (proxyType == ProxyType.CUSTOM) {
                    customProxyProtocolInput?.text.toString().uppercase()
                } else {
                    ProxyProtocol.HTTP.name
                }
                val proxyUsername = if (proxyType == ProxyType.CUSTOM) {
                    proxyUsernameInput?.text.toString()
                } else {
                    ""
                }
                val proxyPassword = if (proxyType == ProxyType.CUSTOM) {
                    proxyPasswordInput?.text.toString()
                } else {
                    ""
                }
                val proxyAuthType = if (proxyType == ProxyType.CUSTOM) {
                    proxyAuthTypeInput?.text.toString().uppercase()
                } else {
                    ProxyAuthType.NONE.name
                }
                val proxyConnectionTimeout = if (proxyType == ProxyType.CUSTOM) {
                    proxyConnectionTimeoutInput?.text.toString().toIntOrNull() ?: 30
                } else {
                    30
                }

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
                        proxyType = proxyType.name,
                        proxyHost = proxyHost,
                        proxyPort = proxyPort,
                        coverArtUri = coverArt,
                        isPreset = stationToEdit?.isPreset ?: false,
                        // Preserve existing fields for RadioBrowser stations
                        source = stationToEdit?.source ?: com.opensource.i2pradio.data.StationSource.USER.name,
                        radioBrowserUuid = stationToEdit?.radioBrowserUuid,
                        lastVerified = stationToEdit?.lastVerified ?: 0L,
                        cachedAt = stationToEdit?.cachedAt ?: 0L,
                        bitrate = stationToEdit?.bitrate ?: 0,
                        codec = stationToEdit?.codec ?: "",
                        country = stationToEdit?.country ?: "",
                        countryCode = stationToEdit?.countryCode ?: "",
                        homepage = stationToEdit?.homepage ?: "",
                        // Custom proxy fields
                        customProxyProtocol = customProxyProtocol,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword,
                        proxyAuthType = proxyAuthType,
                        proxyConnectionTimeout = proxyConnectionTimeout
                    )

                    lifecycleScope.launch(Dispatchers.IO) {
                        if (stationToEdit == null) {
                            repository.insertStation(station)
                        } else {
                            repository.updateStation(station)
                        }
                    }

                    onSaveCallback?.invoke(station)
                }
            }
            .setNegativeButton(getString(R.string.button_cancel), null)
            .create()
    }

    private fun updateImagePreview(uri: String) {
        // Use loadSecure to route remote URLs through Tor when Force Tor is enabled
        // Local content URIs (file://, content://) bypass the proxy automatically
        coverArtPreview?.loadSecure(uri) {
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
