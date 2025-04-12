package com.example.safesignroads

import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
// Removed KeyboardOptions import as it's no longer needed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
// Removed KeyboardType import as it's no longer needed
import kotlin.math.roundToInt // Added for formatting threshold text

class SettingsActivity : ComponentActivity() {
    // Default threshold values matching the original ones in feature.txt
    private val DEFAULT_CAR_HORN_THRESHOLD = 0.15f
    private val DEFAULT_EMERGENCY_VEHICLE_THRESHOLD = 0.14f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val savedVibrationDuration = sharedPreferences.getFloat("vibration_duration", 900f)
        val savedFontSize = sharedPreferences.getFloat("font_size", 19f)
        // Removed server IP loading [cite: 82]
        val savedCarHornThreshold = sharedPreferences.getFloat("car_horn_threshold", DEFAULT_CAR_HORN_THRESHOLD)
        val savedEmergencyVehicleThreshold = sharedPreferences.getFloat("emergency_vehicle_threshold", DEFAULT_EMERGENCY_VEHICLE_THRESHOLD)


        setContent {
            SettingsScreen(
                vibrationDuration = savedVibrationDuration,
                fontSize = savedFontSize,
                // Pass the loaded thresholds to the composable
                initialCarHornThreshold = savedCarHornThreshold,
                initialEmergencyVehicleThreshold = savedEmergencyVehicleThreshold,
                onVibrate = { duration ->
                    vibratePhone(duration)
                    // Save vibration duration immediately when testing? Or only on main save?
                    // Currently saves on main save button. Let's keep it that way for consistency.
                    // saveVibrationDuration(duration)
                },
                onSaveFontSize = { saveFontSize(it) },
                onSaveVibrationDuration = { saveVibrationDuration(it) },
                // Removed onSaveServerIp [cite: 85]
                // Add save functions for new thresholds
                onSaveCarHornThreshold = { saveCarHornThreshold(it) },
                onSaveEmergencyVehicleThreshold = { saveEmergencyVehicleThreshold(it) }
            )
        }
    }

    // Removed saveServerIp function [cite: 85]

    private fun vibratePhone(durationMs: Float) {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs.toLong())
        }
    }

    private fun saveVibrationDuration(duration: Float) {
        val sharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        sharedPreferences.edit().putFloat("vibration_duration", duration).apply()
    }

    private fun saveFontSize(size: Float) {
        val sharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        sharedPreferences.edit().putFloat("font_size", size).apply()
    }

    // Added functions to save the new thresholds
    private fun saveCarHornThreshold(threshold: Float) {
        val sharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        sharedPreferences.edit().putFloat("car_horn_threshold", threshold).apply()
    }

    private fun saveEmergencyVehicleThreshold(threshold: Float) {
        val sharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        sharedPreferences.edit().putFloat("emergency_vehicle_threshold", threshold).apply()
    }
}

@Composable
fun SettingsScreen(
    vibrationDuration: Float,
    fontSize: Float,
    // Add initial values for new thresholds
    initialCarHornThreshold: Float,
    initialEmergencyVehicleThreshold: Float,
    onVibrate: (Float) -> Unit,
    onSaveFontSize: (Float) -> Unit,
    onSaveVibrationDuration: (Float) -> Unit,
    // Removed onSaveServerIp parameter [cite: 85]
    // Add save callbacks for new thresholds
    onSaveCarHornThreshold: (Float) -> Unit,
    onSaveEmergencyVehicleThreshold: (Float) -> Unit
    // Removed initialServerIp parameter [cite: 89]
) {
    val customYellow = Color(0xFFFBD713)
    val deepBlue = Color(0xFF023C69)
    val context = LocalContext.current
    var vibrationDurationValue by remember { mutableStateOf(vibrationDuration) }
    var fontSizeValue by remember { mutableStateOf(fontSize) }
    // Add state for new threshold sliders
    var carHornThresholdValue by remember { mutableStateOf(initialCarHornThreshold) }
    var emergencyVehicleThresholdValue by remember { mutableStateOf(initialEmergencyVehicleThreshold) }

    var showSaveConfirmationDialog by remember { mutableStateOf(false) }
    // Removed serverIpValue state [cite: 89]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(deepBlue)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Removed Server IP Section [cite: 90, 91, 92, 93, 94]

            // Back Button (Moved Up slightly for better spacing without IP field)
            Row(
                modifier = Modifier.fillMaxWidth(), // Take full width
                verticalAlignment = Alignment.CenterVertically // Align items vertically center
                // Removed horizontalArrangement as we only have the back button now
            ) {
                IconButton(
                    onClick = { (context as? ComponentActivity)?.finish() },
                    modifier = Modifier.padding(bottom = 16.dp) // Added bottom padding
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }


            // Font Size Section
            Text(
                text = "FONT SIZE",
                color = Color.White,
                fontSize = 20.sp, // Fixed size for section headers
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Slider(
                    value = fontSizeValue,
                    onValueChange = { fontSizeValue = it },
                    valueRange = 8f..32f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
                Text(
                    text = fontSizeValue.toInt().toString(),
                    color = Color.White,
                    fontSize = 24.sp, // Fixed size for value display
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            Text(
                text = "This text changes size!",
                color = Color.White,
                fontSize = fontSizeValue.sp, // Dynamic size based on slider
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 10.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))

            // Vibration Intensity Section
            Text(
                text = "VIBRATION INTENSITY",
                color = Color.White,
                fontSize = 20.sp, // Fixed size
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Slider(
                    value = vibrationDurationValue,
                    onValueChange = { vibrationDurationValue = it },
                    valueRange = 100f..2000f, // Adjusted range slightly (min 100ms)
                    modifier = Modifier.weight(1f), // Use weight for flexible width
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
                Text(
                    // Display value in milliseconds
                    text = "${vibrationDurationValue.toInt()} ms",
                    color = Color.White,
                    fontSize = 24.sp, // Fixed size
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            Button( // Test Vibration Button
                onClick = { onVibrate(vibrationDurationValue) },
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp), // Added top padding
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Text(
                    text = "TEST VIBRATION",
                    color = deepBlue,
                    fontSize = fontSizeValue.sp, // Use dynamic font size
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(20.dp))


            // --- NEW: Car Horn Threshold Section ---
            Text(
                text = "CAR HORN THRESHOLD",
                color = Color.White,
                fontSize = 20.sp, // Fixed size
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Slider(
                    value = carHornThresholdValue,
                    onValueChange = { carHornThresholdValue = it },
                    valueRange = 0.01f..0.5f, // Define a reasonable range for RMS threshold
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
                Text(
                    // Format threshold to 2 decimal places
                    text = String.format("%.2f", carHornThresholdValue),
                    color = Color.White,
                    fontSize = 24.sp, // Fixed size
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))


            // --- NEW: Emergency Vehicle Threshold Section ---
            Text(
                text = "EMERGENCY VEHICLE THRESHOLD",
                color = Color.White,
                fontSize = 20.sp, // Fixed size
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Slider(
                    value = emergencyVehicleThresholdValue,
                    onValueChange = { emergencyVehicleThresholdValue = it },
                    valueRange = 0.01f..0.5f, // Define a reasonable range for RMS threshold
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
                Text(
                    // Format threshold to 2 decimal places
                    text = String.format("%.2f", emergencyVehicleThresholdValue),
                    color = Color.White,
                    fontSize = 24.sp, // Fixed size
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            Spacer(modifier = Modifier.height(40.dp)) // Increased spacing before save


            // Save Settings Button
            Button(
                onClick = {
                    onSaveVibrationDuration(vibrationDurationValue)
                    onSaveFontSize(fontSizeValue)
                    // Removed onSaveServerIp call [cite: 114]
                    // Add calls to save new thresholds
                    onSaveCarHornThreshold(carHornThresholdValue)
                    onSaveEmergencyVehicleThreshold(emergencyVehicleThresholdValue)
                    showSaveConfirmationDialog = true
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                colors = ButtonDefaults.buttonColors(containerColor = customYellow)
            ) {
                Text(
                    text = "SAVE SETTINGS",
                    color = Color.White, // Changed text color to white for contrast
                    fontSize = fontSizeValue.sp, // Use dynamic font size
                    fontWeight = FontWeight.Bold
                )
            }
        } // End Column

        // Save Confirmation Dialog
        if (showSaveConfirmationDialog) {
            AlertDialog(
                onDismissRequest = { showSaveConfirmationDialog = false },
                title = { Text("Settings Saved", fontSize = fontSizeValue.sp, fontWeight = FontWeight.Bold, lineHeight = (fontSizeValue * 1.2f).sp) },
                text = { Text("Your preferences have been updated successfully.", fontSize = (fontSizeValue * 0.9f).sp, lineHeight = (fontSizeValue * 1.2f).sp) },
                confirmButton = {
                    Button(onClick = { showSaveConfirmationDialog = false }) {
                        Text("OK")
                    }
                }
            )
        }
    } // End Box
}