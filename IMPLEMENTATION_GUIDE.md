# Custom Proxy & Bandwidth Tracking - Implementation Guide

## Summary of Changes

This guide documents the comprehensive implementation of:
1. Global Custom Proxy Configuration
2. Proxy Status Indicators
3. Proxy Connection Testing
4. Force Custom Proxy Mode
5. Bandwidth Usage Tracking

## Files Added

### Drawable Resources
- `ic_proxy_direct.xml` - Icon for direct (no proxy) connections
- `ic_proxy_i2p.xml` - Icon for I2P proxy connections
- `ic_proxy_custom.xml` - Icon for custom proxy connections
- `ic_bandwidth.xml` - Icon for bandwidth usage display

### Layout Files
- `dialog_configure_proxy.xml` - Dialog for configuring global custom proxy settings

### Kotlin Files
- `BandwidthTrackingInterceptor.kt` - OkHttp interceptor for tracking network usage

## Files Modified

### 1. PreferencesHelper.kt
**Added:**
- `KEY_FORCE_CUSTOM_PROXY` - Preference key
- `KEY_BANDWIDTH_USAGE_TOTAL` - Total lifetime bandwidth
- `KEY_BANDWIDTH_USAGE_SESSION` - Current session bandwidth
- `KEY_BANDWIDTH_USAGE_LAST_RESET` - Last reset timestamp
- `setForceCustomProxy()` / `isForceCustomProxy()` - Force custom proxy mode
- `addBandwidthUsage()` - Track bandwidth
- `getTotalBandwidthUsage()` - Get total usage
- `getSessionBandwidthUsage()` - Get session usage
- `resetSessionBandwidthUsage()` - Reset session counter
- `formatBandwidth()` - Format bytes as human-readable string

**Modified:**
- `setForceTorAll()` - Now disables Force Custom Proxy when enabled
- `setForceTorExceptI2P()` - Now disables Force Custom Proxy when enabled

### 2. fragment_settings.xml
**Added two new MaterialCardView sections:**
1. **Custom Proxy Configuration Section** (after Tor Network)
   - Configure Global Proxy button
   - Apply to All Stations button
   - Force Custom Proxy switch

2. **Bandwidth Usage Section** (after Custom Proxy)
   - Total Usage (Lifetime) display
   - Session Usage display with Reset button

### 3. item_radio_station.xml
**Added:**
- `proxyStatusBadge` ImageView - Shows proxy type icon next to station name

## Code to Add to SettingsFragment.kt

### 1. Add UI Element References (in class declaration)
```kotlin
// Custom Proxy UI elements
private var configureProxyButton: MaterialButton? = null
private var applyProxyToAllButton: MaterialButton? = null
private var forceCustomProxySwitch: MaterialSwitch? = null

// Bandwidth UI elements
private var bandwidthTotalText: TextView? = null
private var bandwidthSessionText: TextView? = null
private var resetBandwidthButton: MaterialButton? = null
```

### 2. Add in onCreateView() (find similar view assignments)
```kotlin
// Custom Proxy elements
configureProxyButton = view.findViewById(R.id.configureProxyButton)
applyProxyToAllButton = view.findViewById(R.id.applyProxyToAllButton)
forceCustomProxySwitch = view.findViewById(R.id.forceCustomProxySwitch)

// Bandwidth elements
bandwidthTotalText = view.findViewById(R.id.bandwidthTotalText)
bandwidthSessionText = view.findViewById(R.id.bandwidthSessionText)
resetBandwidthButton = view.findViewById(R.id.resetBandwidthButton)

// Set up custom proxy controls
setupCustomProxyControls()

// Set up bandwidth display
setupBandwidthDisplay()
```

### 3. Add New Methods to SettingsFragment

```kotlin
private fun setupCustomProxyControls() {
    // Configure Proxy button
    configureProxyButton?.setOnClickListener {
        showConfigureProxyDialog()
    }

    // Apply to All Stations button
    applyProxyToAllButton?.setOnClickListener {
        showApplyProxyToAllDialog()
    }

    // Force Custom Proxy switch
    val forceCustomProxyEnabled = PreferencesHelper.isForceCustomProxy(requireContext())
    forceCustomProxySwitch?.isChecked = forceCustomProxyEnabled

    forceCustomProxySwitch?.setOnCheckedChangeListener { switch, isChecked ->
        // Animate the switch
        switch.animate()
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(100)
            .setInterpolator(OvershootInterpolator(2f))
            .withEndAction {
                switch.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .setInterpolator(OvershootInterpolator(1.5f))
                    .start()
            }
            .start()

        PreferencesHelper.setForceCustomProxy(requireContext(), isChecked)

        // Stop current stream when proxy settings change
        stopCurrentStream()

        // Show warning if enabling and custom proxy is not configured
        if (isChecked) {
            val host = PreferencesHelper.getCustomProxyHost(requireContext())
            if (host.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "⚠️ Custom proxy not configured! Configure proxy first.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

private fun setupBandwidthDisplay() {
    updateBandwidthDisplay()

    resetBandwidthButton?.setOnClickListener {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset Session Bandwidth")
            .setMessage("Reset the session bandwidth counter to zero?")
            .setPositiveButton("Reset") { _, _ ->
                PreferencesHelper.resetSessionBandwidthUsage(requireContext())
                updateBandwidthDisplay()
                Toast.makeText(requireContext(), "Session bandwidth reset", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

private fun updateBandwidthDisplay() {
    val total = PreferencesHelper.getTotalBandwidthUsage(requireContext())
    val session = PreferencesHelper.getSessionBandwidthUsage(requireContext())

    bandwidthTotalText?.text = PreferencesHelper.formatBandwidth(total)
    bandwidthSessionText?.text = PreferencesHelper.formatBandwidth(session)
}

private fun showConfigureProxyDialog() {
    val dialogView = layoutInflater.inflate(R.layout.dialog_configure_proxy, null)

    val hostInput = dialogView.findViewById<TextInputEditText>(R.id.proxyHostInput)
    val portInput = dialogView.findViewById<TextInputEditText>(R.id.proxyPortInput)
    val protocolInput = dialogView.findViewById<AutoCompleteTextView>(R.id.proxyProtocolInput)
    val usernameInput = dialogView.findViewById<TextInputEditText>(R.id.proxyUsernameInput)
    val passwordInput = dialogView.findViewById<TextInputEditText>(R.id.proxyPasswordInput)
    val authTypeInput = dialogView.findViewById<AutoCompleteTextView>(R.id.proxyAuthTypeInput)
    val timeoutInput = dialogView.findViewById<TextInputEditText>(R.id.proxyTimeoutInput)
    val testButton = dialogView.findViewById<MaterialButton>(R.id.testConnectionButton)
    val testResultText = dialogView.findViewById<TextView>(R.id.testResultText)

    // Set up dropdowns
    val protocols = arrayOf("HTTP", "HTTPS", "SOCKS4", "SOCKS5")
    val protocolAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, protocols)
    protocolInput.setAdapter(protocolAdapter)

    val authTypes = arrayOf("None", "Basic", "Digest")
    val authTypeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, authTypes)
    authTypeInput.setAdapter(authTypeAdapter)

    // Load current settings
    hostInput.setText(PreferencesHelper.getCustomProxyHost(requireContext()))
    portInput.setText(PreferencesHelper.getCustomProxyPort(requireContext()).toString())
    protocolInput.setText(PreferencesHelper.getCustomProxyProtocol(requireContext()), false)
    usernameInput.setText(PreferencesHelper.getCustomProxyUsername(requireContext()))
    passwordInput.setText(PreferencesHelper.getCustomProxyPassword(requireContext()))
    authTypeInput.setText(PreferencesHelper.getCustomProxyAuthType(requireContext()), false)
    timeoutInput.setText(PreferencesHelper.getCustomProxyConnectionTimeout(requireContext()).toString())

    // Test Connection button
    testButton.setOnClickListener {
        val host = hostInput.text?.toString() ?: ""
        val port = portInput.text?.toString()?.toIntOrNull() ?: 8080

        if (host.isEmpty()) {
            testResultText.text = "❌ Please enter a proxy host"
            testResultText.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
            testResultText.visibility = View.VISIBLE
            return@setOnClickListener
        }

        testResultText.text = "Testing connection..."
        testResultText.setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
        testResultText.visibility = View.VISIBLE
        testButton.isEnabled = false

        // Test proxy connection in background
        lifecycleScope.launch(Dispatchers.IO) {
            val result = testProxyConnection(host, port)
            withContext(Dispatchers.Main) {
                testButton.isEnabled = true
                testResultText.text = result.message
                testResultText.setTextColor(
                    resources.getColor(
                        if (result.success) android.R.color.holo_green_dark
                        else android.R.color.holo_red_dark,
                        null
                    )
                )
                testResultText.visibility = View.VISIBLE
            }
        }
    }

    AlertDialog.Builder(requireContext())
        .setTitle("Configure Global Custom Proxy")
        .setView(dialogView)
        .setPositiveButton("Save") { _, _ ->
            val host = hostInput.text?.toString() ?: ""
            val port = portInput.text?.toString()?.toIntOrNull() ?: 8080
            val protocol = protocolInput.text?.toString() ?: "HTTP"
            val username = usernameInput.text?.toString() ?: ""
            val password = passwordInput.text?.toString() ?: ""
            val authType = authTypeInput.text?.toString() ?: "None"
            val timeout = timeoutInput.text?.toString()?.toIntOrNull() ?: 30

            PreferencesHelper.setCustomProxyHost(requireContext(), host)
            PreferencesHelper.setCustomProxyPort(requireContext(), port)
            PreferencesHelper.setCustomProxyProtocol(requireContext(), protocol)
            PreferencesHelper.setCustomProxyUsername(requireContext(), username)
            PreferencesHelper.setCustomProxyPassword(requireContext(), password)
            PreferencesHelper.setCustomProxyAuthType(requireContext(), authType)
            PreferencesHelper.setCustomProxyConnectionTimeout(requireContext(), timeout)

            Toast.makeText(requireContext(), "Custom proxy settings saved", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

private suspend fun testProxyConnection(host: String, port: Int): TestResult {
    return try {
        // Simple TCP connection test
        val socket = java.net.Socket()
        socket.connect(java.net.InetSocketAddress(host, port), 5000)
        socket.close()
        TestResult(true, "✓ Connection successful!")
    } catch (e: Exception) {
        TestResult(false, "❌ Connection failed: ${e.message?.take(50)}")
    }
}

private data class TestResult(val success: Boolean, val message: String)

private fun showApplyProxyToAllDialog() {
    val host = PreferencesHelper.getCustomProxyHost(requireContext())
    if (host.isEmpty()) {
        Toast.makeText(
            requireContext(),
            "Please configure global custom proxy first",
            Toast.LENGTH_LONG
        ).show()
        return
    }

    lifecycleScope.launch(Dispatchers.IO) {
        val stationCount = repository.getAllStationsSync().size

        withContext(Dispatchers.Main) {
            AlertDialog.Builder(requireContext())
                .setTitle("Apply Custom Proxy to All Stations")
                .setMessage("Apply global custom proxy settings to all $stationCount stations?\n\nThis will override individual station proxy settings.")
                .setPositiveButton("Apply") { _, _ ->
                    applyCustomProxyToAllStations()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}

private fun applyCustomProxyToAllStations() {
    lifecycleScope.launch(Dispatchers.IO) {
        val stations = repository.getAllStationsSync()
        val host = PreferencesHelper.getCustomProxyHost(requireContext())
        val port = PreferencesHelper.getCustomProxyPort(requireContext())
        val protocol = PreferencesHelper.getCustomProxyProtocol(requireContext())
        val username = PreferencesHelper.getCustomProxyUsername(requireContext())
        val password = PreferencesHelper.getCustomProxyPassword(requireContext())
        val authType = PreferencesHelper.getCustomProxyAuthType(requireContext())
        val timeout = PreferencesHelper.getCustomProxyConnectionTimeout(requireContext())

        var updated = 0
        for (station in stations) {
            try {
                val updatedStation = station.copy(
                    proxyType = "CUSTOM",
                    proxyHost = host,
                    proxyPort = port,
                    customProxyProtocol = protocol,
                    proxyUsername = username,
                    proxyPassword = password,
                    proxyAuthType = authType,
                    proxyConnectionTimeout = timeout,
                    useProxy = true
                )
                repository.updateStation(updatedStation)
                updated++
            } catch (e: Exception) {
                android.util.Log.e("SettingsFragment", "Failed to update station ${station.name}", e)
            }
        }

        withContext(Dispatchers.Main) {
            Toast.makeText(
                requireContext(),
                "Applied custom proxy to $updated station(s)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
```

### 4. Update onResume() to refresh bandwidth display
```kotlin
override fun onResume() {
    super.onResume()
    // ... existing code ...
    updateBandwidthDisplay() // Add this line
}
```

## Code to Add to RadioService.kt

### 1. In playStream() method, add Force Custom Proxy support

Find the proxy routing logic (around line 1406-1446) and add Force Custom Proxy case:

```kotlin
val (effectiveProxyHost, effectiveProxyPort, effectiveProxyType) = when {
    // FORCE CUSTOM PROXY: Everything goes through custom proxy
    PreferencesHelper.isForceCustomProxy(this) -> {
        val customHost = PreferencesHelper.getCustomProxyHost(this)
        val customPort = PreferencesHelper.getCustomProxyPort(this)
        if (customHost.isEmpty()) {
            android.util.Log.e("RadioService", "FORCE CUSTOM PROXY: Proxy not configured - BLOCKING stream")
            isStartingNewStream.set(false)
            broadcastPlaybackStateChanged(isBuffering = false, isPlaying = false)
            startForeground(NOTIFICATION_ID, createNotification("Custom proxy not configured"))
            return
        }
        android.util.Log.d("RadioService", "FORCE CUSTOM PROXY: Routing ALL traffic through custom proxy")
        Triple(customHost, customPort, ProxyType.CUSTOM)
    }
    // FORCE TOR ALL: Everything goes through Tor, no exceptions
    forceTorAll && TorManager.isConnected() -> {
        // ... existing code ...
    }
    // ... rest of existing cases ...
}
```

### 2. Add BandwidthTrackingInterceptor to OkHttp clients

Find where OkHttpClient is built (around line 1485-1509) and add the interceptor:

```kotlin
val builder = OkHttpClient.Builder()
    .proxy(Proxy(javaProxyType, InetSocketAddress(effectiveProxyHost, effectiveProxyPort)))
    .addInterceptor(BandwidthTrackingInterceptor(this)) // ADD THIS LINE

// Add proxy authentication if custom proxy with credentials
if (effectiveProxyType == ProxyType.CUSTOM && proxyUsername.isNotEmpty() && proxyPassword.isNotEmpty()) {
    // ... existing auth code ...
}
```

Also add to the direct connection client:

```kotlin
} else {
    OkHttpClient.Builder()
        .addInterceptor(BandwidthTrackingInterceptor(this)) // ADD THIS LINE
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}
```

## Code to Add to LibraryFragment.kt or Adapter

### Update RadioStationAdapter to show proxy badges

In the ViewHolder's bind() method:

```kotlin
fun bind(station: RadioStation) {
    // ... existing binding code ...

    // Set proxy status badge
    val proxyBadge = itemView.findViewById<ImageView>(R.id.proxyStatusBadge)
    when (station.getProxyTypeEnum()) {
        ProxyType.NONE -> {
            proxyBadge.setImageResource(R.drawable.ic_proxy_direct)
            proxyBadge.imageTintList = ColorStateList.valueOf(
                itemView.context.getColor(R.color.proxy_direct_color)
            )
        }
        ProxyType.I2P -> {
            proxyBadge.setImageResource(R.drawable.ic_proxy_i2p)
            proxyBadge.imageTintList = ColorStateList.valueOf(
                itemView.context.getColor(R.color.proxy_i2p_color)
            )
        }
        ProxyType.TOR -> {
            proxyBadge.setImageResource(R.drawable.ic_tor_on)
            proxyBadge.imageTintList = ColorStateList.valueOf(
                itemView.context.getColor(R.color.proxy_tor_color)
            )
        }
        ProxyType.CUSTOM -> {
            proxyBadge.setImageResource(R.drawable.ic_proxy_custom)
            proxyBadge.imageTintList = ColorStateList.valueOf(
                itemView.context.getColor(R.color.proxy_custom_color)
            )
        }
    }
}
```

## Optional: Add Proxy Badge Colors to colors.xml

```xml
<color name="proxy_direct_color">#808080</color>  <!-- Gray -->
<color name="proxy_i2p_color">#9C27B0</color>     <!-- Purple -->
<color name="proxy_tor_color">#7B1FA2</color>     <!-- Deep Purple -->
<color name="proxy_custom_color">#FF6F00</color>  <!-- Orange -->
```

## Testing Checklist

- [ ] Global custom proxy configuration dialog opens and saves settings
- [ ] Test Connection button validates proxy connectivity
- [ ] Apply to All Stations updates all station proxy settings
- [ ] Force Custom Proxy switch stops current stream
- [ ] Force Custom Proxy routes traffic correctly
- [ ] Proxy status badges show correct icons for each proxy type
- [ ] Bandwidth usage updates during streaming
- [ ] Bandwidth usage persists across app restarts
- [ ] Session bandwidth reset works correctly
- [ ] All UI follows Material You design guidelines
- [ ] Switches have smooth animations
- [ ] Toast messages are clear and helpful

## Known Limitations

1. Bandwidth tracking uses approximate calculations
2. Test Connection only tests TCP connectivity, not full HTTP/SOCKS protocol
3. Proxy badges use fixed colors (could be theme-aware)
4. No bandwidth usage graphs (future enhancement)

## Future Enhancements

1. Bandwidth usage graphs and statistics
2. Per-station bandwidth tracking
3. Bandwidth usage alerts/limits
4. More comprehensive proxy testing (HTTP/SOCKS protocol validation)
5. Proxy performance metrics (latency, throughput)
6. Auto-detect best proxy for each station
