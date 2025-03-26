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

class SettingsActivity : ComponentActivity() {
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
                    saveVibrationDuration(duration)
                },
                onSaveFontSize = { saveFontSize(it) },
                onSaveVibrationDuration = { saveVibrationDuration(it) },
            )
        }
    }

    // Function to vibrate the phone
    private fun vibratePhone(durationMs: Float) {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // Android 8.0+ (API 26+)
            val effect = VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        } else {
            // Android 7.0 (API 24-25) - Deprecated method
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs.toLong())
        }
    }

    // Function to save vibration duration
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
    fontSize: Float,  // Accept font size as a parameter
    onVibrate: (Float) -> Unit,
    onSaveFontSize: (Float) -> Unit,  // Function to save font size
    onSaveVibrationDuration: (Float) -> Unit
) {
    val darkPurple = Color(0xFF2A2A72)
    val context = LocalContext.current
    var vibrationDurationValue by remember { mutableStateOf(vibrationDuration) }
    var fontSizeValue by remember { mutableStateOf(fontSize) }  // Load font size

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(darkPurple)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {

            IconButton(
                onClick = { (context as? ComponentActivity)?.finish() },
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(50.dp))

            Text(
                text = "FONT SIZE",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Font Size Slider
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

            Spacer(modifier = Modifier.height(20.dp))

            // Text that changes dynamically with the font size
            Text(
                text = "This text changes size!",
                color = Color.White,
                fontSize = fontSizeValue.sp,  // Apply dynamic font size
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 10.dp)
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Save Font Size Button
            Button(
                onClick = { onSaveFontSize(fontSizeValue) },  // Save font size when clicked
                modifier = Modifier.align(Alignment.CenterHorizontally),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
            ) {
                Text(
                    text = "SAVE FONT SIZE",
                    color = Color.White,
                    fontSize = fontSizeValue.sp,  // Apply dynamic font size
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "VIBRATION DURATION (ms)",
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
                    value = vibrationDurationValue,
                    onValueChange = { vibrationDurationValue = it },
                    valueRange = 0f..2000f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )

                Text(
                    text = vibrationDurationValue.toInt().toString(),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Vibrate Button
            Button(
                onClick = { onVibrate(vibrationDurationValue) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Text(
                    text = "TEST VIBRATION",
                    color = darkPurple,
                    fontSize = fontSizeValue.sp,  // Apply font size
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Save Vibration Button
            Button(
                onClick = { onSaveVibrationDuration(vibrationDurationValue) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
            ) {
                Text(
                    text = "SAVE SETTINGS",
                    color = Color.White,
                    fontSize = fontSizeValue.sp,  // Apply font size
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = { },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                colors = ButtonDefaults.buttonColors(containerColor = darkPurple)
            ) {
                Text(
                    text = "ABOUT US",
                    color = Color.White,
                    fontSize = fontSizeValue.sp,  // Apply font size here too!
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}