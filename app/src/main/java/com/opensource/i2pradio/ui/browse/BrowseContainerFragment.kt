package com.opensource.i2pradio.ui.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.opensource.i2pradio.R
import com.opensource.i2pradio.ui.PreferencesHelper

/**
 * Container fragment that dynamically shows the appropriate browse fragment
 * based on Network & API settings.
 *
 * Modes:
 * - Both APIs enabled: Shows full BrowseStationsFragment
 * - RadioBrowser disabled only: Shows BrowseRadioRegistryOnlyFragment (Privacy Radio only)
 * - Radio Registry disabled only: Shows BrowseStationsFragment (hides Privacy Radio section)
 * - Both APIs disabled: Shows BrowseDisabledFragment
 */
class BrowseContainerFragment : Fragment() {

    private var currentMode: BrowseMode? = null

    private enum class BrowseMode {
        FULL,                    // Both APIs enabled
        RADIO_BROWSER_ONLY,      // Radio Registry disabled
        RADIO_REGISTRY_ONLY,     // RadioBrowser disabled
        DISABLED                 // Both APIs disabled
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_browse_container, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Initial setup
        updateChildFragment()
    }

    override fun onResume() {
        super.onResume()
        // Check if settings changed and update if needed
        updateChildFragment()
    }

    /**
     * Determine the current browse mode based on settings and update the child fragment if needed.
     */
    private fun updateChildFragment() {
        val context = context ?: return

        val isRadioBrowserDisabled = PreferencesHelper.isRadioBrowserApiDisabled(context)
        val isRadioRegistryDisabled = PreferencesHelper.isRadioRegistryApiDisabled(context)

        val newMode = when {
            isRadioBrowserDisabled && isRadioRegistryDisabled -> BrowseMode.DISABLED
            isRadioBrowserDisabled -> BrowseMode.RADIO_REGISTRY_ONLY
            isRadioRegistryDisabled -> BrowseMode.RADIO_BROWSER_ONLY
            else -> BrowseMode.FULL
        }

        // Only replace fragment if mode changed
        if (newMode != currentMode) {
            currentMode = newMode
            replaceChildFragment(newMode)
        }
    }

    private fun replaceChildFragment(mode: BrowseMode) {
        val fragment: Fragment = when (mode) {
            BrowseMode.DISABLED -> BrowseDisabledFragment()
            BrowseMode.RADIO_REGISTRY_ONLY -> BrowseRadioRegistryOnlyFragment()
            BrowseMode.RADIO_BROWSER_ONLY -> BrowseStationsFragment()
            BrowseMode.FULL -> BrowseStationsFragment()
        }

        childFragmentManager.beginTransaction()
            .replace(R.id.browseFragmentContainer, fragment)
            .commitAllowingStateLoss()
    }
}
