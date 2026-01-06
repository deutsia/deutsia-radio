package com.opensource.i2pradio.ui.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.opensource.i2pradio.R

/**
 * Fragment displayed when both RadioBrowser and Radio Registry APIs are disabled.
 * Shows a message explaining that browse functionality is unavailable.
 */
class BrowseDisabledFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_browse_disabled, container, false)
    }
}
