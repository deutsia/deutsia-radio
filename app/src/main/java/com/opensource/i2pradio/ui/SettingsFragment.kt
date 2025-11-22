package com.opensource.i2pradio.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.opensource.i2pradio.R

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)

        val themeButton = view.findViewById<MaterialButton>(R.id.themeButton)
        val githubButton = view.findViewById<MaterialButton>(R.id.githubButton)
        val materialYouSwitch = view.findViewById<MaterialSwitch>(R.id.materialYouSwitch)
        val materialYouContainer = view.findViewById<View>(R.id.materialYouContainer)

        // Show Material You option only on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            materialYouContainer?.visibility = View.VISIBLE
            materialYouSwitch?.isChecked = PreferencesHelper.isMaterialYouEnabled(requireContext())
            materialYouSwitch?.setOnCheckedChangeListener { _, isChecked ->
                PreferencesHelper.setMaterialYouEnabled(requireContext(), isChecked)
                // Recreate activity to apply dynamic colors
                activity?.recreate()
            }
        } else {
            materialYouContainer?.visibility = View.GONE
        }

        // Update theme button text
        updateThemeButtonText(themeButton)

        // Theme selector
        themeButton.setOnClickListener {
            showThemeDialog(themeButton)
        }

        // GitHub button
        githubButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yourusername/i2p-radio"))
            startActivity(intent)
        }

        return view
    }

    private fun updateThemeButtonText(button: MaterialButton) {
        val currentMode = AppCompatDelegate.getDefaultNightMode()
        button.text = when (currentMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> "Light"
            AppCompatDelegate.MODE_NIGHT_YES -> "Dark"
            else -> "System Default"
        }
    }

    private fun showThemeDialog(themeButton: MaterialButton) {
        val themes = arrayOf("System Default", "Light", "Dark")
        val currentMode = AppCompatDelegate.getDefaultNightMode()
        val selectedIndex = when (currentMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> 1
            AppCompatDelegate.MODE_NIGHT_YES -> 2
            else -> 0
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Choose Theme")
            .setSingleChoiceItems(themes, selectedIndex) { dialog, which ->
                val newMode = when (which) {
                    0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(newMode)
                PreferencesHelper.saveThemeMode(requireContext(), newMode)
                updateThemeButtonText(themeButton)
                dialog.dismiss()
            }
            .show()
    }
}
