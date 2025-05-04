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
    private val DEFAULT_CAR_HORN_THRESHOLD = 0.14f
    private val DEFAULT_EMERGENCY_VEHICLE_THRESHOLD = 0.30f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val savedVibrationDuration = sharedPreferences.getFloat("vibration_duration", 900f)
        val savedFontSize = sharedPreferences.getFloat("font_size", 19f)


        setContent {
            SettingsScreen(
                vibrationDuration = savedVibrationDuration,
                fontSize = savedFontSize,
                onVibrate = { duration ->
                    vibratePhone(duration)

                },
                onSaveFontSize = { saveFontSize(it) },
                onSaveVibrationDuration = { saveVibrationDuration(it) },
            )
        }
    }


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

}

@Composable
fun SettingsScreen(
    vibrationDuration: Float,
    fontSize: Float,
    onVibrate: (Float) -> Unit,
    onSaveFontSize: (Float) -> Unit,
    onSaveVibrationDuration: (Float) -> Unit,
) {
    val customYellow = Color(0xFFFBD713)
    val deepBlue = Color(0xFF023C69)
    val context = LocalContext.current
    var vibrationDurationValue by remember { mutableStateOf(vibrationDuration) }
    var fontSizeValue by remember { mutableStateOf(fontSize) }

    var showSaveConfirmationDialog by remember { mutableStateOf(false) }

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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { (context as? ComponentActivity)?.finish() },
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }


            Text(
                text = "FONT SIZE",
                color = Color.White,
                fontSize = 20.sp,
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
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            Text(
                text = "This text changes size!",
                color = Color.White,
                fontSize = fontSizeValue.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 10.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))

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
                    valueRange = 100f..2000f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
                Text(
                    text = "${vibrationDurationValue.toInt()} ms",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            Button(
                onClick = { onVibrate(vibrationDurationValue) },
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Text(
                    text = "TEST VIBRATION",
                    color = deepBlue,
                    fontSize = fontSizeValue.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    onSaveVibrationDuration(vibrationDurationValue)
                    onSaveFontSize(fontSizeValue)
                    showSaveConfirmationDialog = true
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                colors = ButtonDefaults.buttonColors(containerColor = customYellow)
            ) {
                Text(
                    text = "SAVE SETTINGS",
                    color = Color.White,
                    fontSize = fontSizeValue.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
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
    }
}