package com.opensource.i2pradio.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.opensource.i2pradio.R

/**
 * Temporary activity for previewing browse tab mockup designs.
 * Remove this before production release.
 */
class MockupViewerActivity : AppCompatActivity() {

    private lateinit var mockupSpinner: Spinner
    private lateinit var mockupContainer: FrameLayout

    private val mockups = listOf(
        "Option A (Original)" to R.layout.mockup_browse_option_a,
        "Option A1 (Genre-First Refined)" to R.layout.mockup_browse_option_a1,
        "Option A2 (Minimal Clean)" to R.layout.mockup_browse_option_a2,
        "Option B (Original)" to R.layout.mockup_browse_option_b,
        "Option B1 (Card-Based Refined)" to R.layout.mockup_browse_option_b1,
        "Option B2 (Hero Cards)" to R.layout.mockup_browse_option_b2,
        "Option C (Hybrid)" to R.layout.mockup_browse_option_c,
        "Option D (Feed Style)" to R.layout.mockup_browse_option_d,
        "Results Mode (After Search)" to R.layout.mockup_browse_results_mode,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mockup_viewer)

        mockupSpinner = findViewById(R.id.mockupSpinner)
        mockupContainer = findViewById(R.id.mockupContainer)

        setupSpinner()
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mockups.map { it.first }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        mockupSpinner.adapter = adapter
        mockupSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadMockup(mockups[position].second)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadMockup(layoutRes: Int) {
        mockupContainer.removeAllViews()
        val mockupView = layoutInflater.inflate(layoutRes, mockupContainer, false)
        mockupContainer.addView(mockupView)
    }
}
