package com.newfuel.fuelalert

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.newfuel.fuelalert.alert.FullScreenIntentPermission
import com.newfuel.fuelalert.backend.BackendResult
import com.newfuel.fuelalert.backend.Geocoder
import com.newfuel.fuelalert.databinding.ActivityMainBinding
import com.newfuel.fuelalert.settings.SettingsRepository
import com.newfuel.fuelalert.settings.ThresholdMode
import com.newfuel.fuelalert.trip.TripMonitorService
import kotlinx.coroutines.launch

/**
 * Settings screen (PRD.md ss5.6/ss8) - every field here reads from and
 * writes straight through to SettingsRepository, so there's no separate
 * "save" step and no risk of the UI and the persisted value drifting
 * apart. Also owns the permission-grant flow PRD.md ss7 calls for: an
 * explainer shown in-layout before each button that triggers the real
 * system prompt (not a bare OS dialog with no context), and background
 * location requested as its own separate step after fine location is
 * already granted - not bundled into one request, per Android's own
 * platform requirement for that permission.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsRepository
    private val geocoder = Geocoder()

    private val requestCoreLocationPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionStatus()
        }
    private val requestBackgroundLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsRepository(this)

        setupTripControls()
        setupFuelTypeSpinner()
        setupSearchFields()
        setupThresholdControls()
        setupTripAutoStartSwitch()
        setupPermissionButtons()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    /** Blank destination -> near-me mode; non-blank -> geocoded first
      * (Geocoder.kt, MainActivity's job per PRD.md ss5.3/TripMonitorService.kt's
      * doc comment - the service itself never geocodes), then passed to
      * TripMonitorService as already-resolved lat/lon extras. */
    private fun setupTripControls() {
        binding.startTripButton.setOnClickListener { startTrip() }
        binding.stopTripButton.setOnClickListener { stopTrip() }
        binding.tripStatusText.text = "Not monitoring"
    }

    private fun startTrip() {
        val destinationText = binding.destinationInput.text.toString().trim()
        if (destinationText.isEmpty()) {
            startMonitoringService(destinationLat = null, destinationLon = null)
            binding.tripStatusText.text = "Monitoring - near-me mode"
            return
        }

        binding.tripStatusText.text = "Finding \"$destinationText\"…"
        lifecycleScope.launch {
            when (val result = geocoder.geocode(destinationText)) {
                is BackendResult.Success -> {
                    startMonitoringService(result.value.lat, result.value.lon)
                    binding.tripStatusText.text = "Monitoring - route-aware to $destinationText"
                }
                is BackendResult.Failure -> {
                    binding.tripStatusText.text = "Could not find \"$destinationText\": ${result.message}"
                }
            }
        }
    }

    private fun startMonitoringService(destinationLat: Double?, destinationLon: Double?) {
        val intent = Intent(this, TripMonitorService::class.java).apply {
            action = TripMonitorService.ACTION_START_MONITORING
            if (destinationLat != null && destinationLon != null) {
                putExtra(TripMonitorService.EXTRA_DESTINATION_LAT, destinationLat)
                putExtra(TripMonitorService.EXTRA_DESTINATION_LON, destinationLon)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopTrip() {
        stopService(Intent(this, TripMonitorService::class.java))
        binding.tripStatusText.text = "Not monitoring"
    }

    private fun setupFuelTypeSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, SettingsRepository.FUEL_TYPES)
        binding.fuelTypeSpinner.adapter = adapter
        binding.fuelTypeSpinner.setSelection(SettingsRepository.FUEL_TYPES.indexOf(settings.fuelType).coerceAtLeast(0))
        binding.fuelTypeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                settings.fuelType = SettingsRepository.FUEL_TYPES[position]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun setupSearchFields() {
        binding.searchRadiusInput.setText(settings.searchRadiusKm.toString())
        binding.corridorWidthInput.setText(settings.corridorWidthKm.toString())
        binding.leadDistanceInput.setText(settings.leadDistanceKm.toString())

        bindDoubleField(binding.searchRadiusInput) { settings.searchRadiusKm = it }
        bindDoubleField(binding.corridorWidthInput) { settings.corridorWidthKm = it }
        bindDoubleField(binding.leadDistanceInput) { settings.leadDistanceKm = it }
    }

    private fun setupThresholdControls() {
        binding.targetPriceInput.setText(settings.targetPricePerLitre.toString())
        binding.percentBelowAverageInput.setText(settings.percentBelowAverage.toString())
        bindDoubleField(binding.targetPriceInput) { settings.targetPricePerLitre = it }
        bindDoubleField(binding.percentBelowAverageInput) { settings.percentBelowAverage = it }

        binding.targetPriceModeButton.setOnClickListener {
            settings.thresholdMode = ThresholdMode.TARGET_PRICE
            refreshThresholdModeButtons()
        }
        binding.percentBelowAverageModeButton.setOnClickListener {
            settings.thresholdMode = ThresholdMode.PERCENT_BELOW_AVERAGE
            refreshThresholdModeButtons()
        }
        refreshThresholdModeButtons()
    }

    private fun refreshThresholdModeButtons() {
        val isTargetPrice = settings.thresholdMode == ThresholdMode.TARGET_PRICE
        binding.targetPriceInput.isEnabled = isTargetPrice
        binding.percentBelowAverageInput.isEnabled = !isTargetPrice
        binding.targetPriceModeButton.alpha = if (isTargetPrice) 1.0f else 0.5f
        binding.percentBelowAverageModeButton.alpha = if (isTargetPrice) 0.5f else 1.0f
    }

    private fun setupTripAutoStartSwitch() {
        binding.tripAutoStartSwitch.isChecked = settings.tripAutoStart
        binding.tripAutoStartSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.tripAutoStart = isChecked
        }
    }

    private fun setupPermissionButtons() {
        binding.grantLocationButton.setOnClickListener {
            requestCoreLocationPermissions.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACTIVITY_RECOGNITION,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            )
        }
        binding.grantBackgroundLocationButton.setOnClickListener {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                Toast.makeText(this, "Grant Location first - background location needs it granted already.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            requestBackgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        binding.grantFullScreenIntentButton.setOnClickListener {
            if (FullScreenIntentPermission.isGranted(this)) {
                Toast.makeText(this, "Already granted.", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(FullScreenIntentPermission.settingsIntent(this))
            }
        }
    }

    private fun refreshPermissionStatus() {
        val location = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val backgroundLocation = hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val activityRecognition = hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        val fullScreenIntent = FullScreenIntentPermission.isGranted(this)

        binding.permissionStatusText.text = buildString {
            appendLine("Location: ${status(location)}")
            appendLine("Background location: ${status(backgroundLocation)}")
            appendLine("Activity recognition: ${status(activityRecognition)}")
            appendLine("Notifications: ${status(notifications)}")
            append("Full-screen alert: ${status(fullScreenIntent)}")
        }
    }

    private fun status(granted: Boolean) = if (granted) "Granted" else "Not granted"

    private fun hasPermission(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Keeps every numeric settings field's wiring identical: parse on
      * every keystroke, only persist a value that actually parsed, and
      * never let an in-progress edit (an empty field, a lone "-") throw
      * or silently reset to some other value. */
    private fun bindDoubleField(field: android.widget.EditText, onValid: (Double) -> Unit) {
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                s?.toString()?.toDoubleOrNull()?.let(onValid)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }
}
