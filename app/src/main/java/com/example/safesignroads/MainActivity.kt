package com.example.safesignroads

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var showEnableDialog by remember { mutableStateOf(false) }
            var showDisableDialog by remember { mutableStateOf(false) }
            var showAboutDialog by remember { mutableStateOf(false) }
            var showInstructionsDialog by remember { mutableStateOf(false) }

            SafeSignRoadsApp(
                onEnableClick = {
                    val allPermissionsGranted = checkPermissions()
                    if (allPermissionsGranted) {
                        showEnableDialog = true
                    } else {
                        requestPermissions()
                    }
                },
                onDisableClick = {
                    showDisableDialog = true
                },
                onSettingsClick = {
                    openSettingsActivity()
                },
                onAboutClick = {
                    showAboutDialog = true
                },
                onInstructionsClick = {
                    showInstructionsDialog = true
                },
                showEnableDialog = showEnableDialog,
                onDismissEnableDialog = { showEnableDialog = false },
                showDisableDialog = showDisableDialog,
                onDismissDisableDialog = { showDisableDialog = false },
                showAboutDialog = showAboutDialog,
                onDismissAboutDialog = { showAboutDialog = false },
                showInstructionsDialog = showInstructionsDialog,
                onDismissInstructionsDialog = { showInstructionsDialog = false },
                openAppSettings = { openAppSettings() }
            )
        }
    }

    private fun openSettingsActivity() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun checkPermissions(): Boolean {
        val requiredPermissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )

        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val notGrantedPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGrantedPermissions.isNotEmpty()) {
            permissionLauncher.launch(notGrantedPermissions.toTypedArray())
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { (permission, isGranted) ->
            if (!isGranted) {
                println("$permission NOT granted")
            } else {
                println("$permission granted")
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }
}

@Composable
fun SafeSignRoadsApp(
    onEnableClick: () -> Unit,
    onDisableClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onInstructionsClick: () -> Unit,
    showEnableDialog: Boolean,
    onDismissEnableDialog: () -> Unit,
    showDisableDialog: Boolean,
    onDismissDisableDialog: () -> Unit,
    showAboutDialog: Boolean,
    onDismissAboutDialog: () -> Unit,
    showInstructionsDialog: Boolean,
    onDismissInstructionsDialog: () -> Unit,
    openAppSettings: () -> Unit
) {
    val brightYellow = Color(0xFFFFDD00)
    val navyBlue = Color(0xFF003366)

    Box(
        modifier = Modifier.fillMaxSize()
    ) {

        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = "Background",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )


        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            Spacer(modifier = Modifier.height(330.dp))


            YellowButton(text = "ENABLE", onClick = onEnableClick)

            Spacer(modifier = Modifier.height(12.dp))

            YellowButton(text = "DISABLE", onClick = onDisableClick)

            Spacer(modifier = Modifier.height(12.dp))

            YellowButton(text = "SETTINGS", onClick = onSettingsClick)

            Spacer(modifier = Modifier.height(12.dp))

            YellowButton(text = "ABOUT", onClick = onAboutClick)

            Spacer(modifier = Modifier.height(12.dp))

            YellowButton(text = "INSTRUCTIONS", onClick = onInstructionsClick)
        }


        if (showEnableDialog) {
            AlertDialog(
                onDismissRequest = onDismissEnableDialog,
                title = { Text("Permissions Granted") },
                text = { Text("Already enabled!") },
                confirmButton = {
                    Button(onClick = onDismissEnableDialog) {
                        Text("OK")
                    }
                }
            )
        }

        if (showDisableDialog) {
            AlertDialog(
                onDismissRequest = onDismissDisableDialog,
                title = { Text("Disable Permissions") },
                text = { Text("To disable permissions, go to App Settings and revoke them manually.") },
                confirmButton = {
                    Button(onClick = {
                        openAppSettings()
                        onDismissDisableDialog()
                    }) {
                        Text("Open Settings")
                    }
                },
                dismissButton = {
                    Button(onClick = onDismissDisableDialog) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showAboutDialog) {
            AlertDialog(
                onDismissRequest = onDismissAboutDialog,
                title = { Text("About SafeSign Roads") },
                text = {
                    Text(
                        "SafeSign Roads is an innovative application designed to enhance road safety " +
                                "by utilizing advanced audio and location technologies to detect and alert " +
                                "users about potential road hazards."
                    )
                },
                confirmButton = {
                    Button(onClick = onDismissAboutDialog) {
                        Text("Close")
                    }
                }
            )
        }

        if (showInstructionsDialog) {
            AlertDialog(
                onDismissRequest = onDismissInstructionsDialog,
                title = { Text("How to Use SafeSign Roads") },
                text = {
                    Text(
                        "1. Enable the app by granting necessary permissions\n" +
                                "2. The app will run in the background, monitoring road conditions\n" +
                                "3. You'll receive alerts for potential hazards based on audio and location data\n" +
                                "4. Customize settings in the Settings menu as needed"
                    )
                },
                confirmButton = {
                    Button(onClick = onDismissInstructionsDialog) {
                        Text("Got It")
                    }
                }
            )
        }
    }
}

@Composable
fun YellowButton(text: String, onClick: () -> Unit) {
    val brightYellow = Color(0xFFFFDD00)
    val navyBlue = Color(0xFF003366)

    Button(
        onClick = onClick,
        modifier = Modifier
            .width(200.dp)
            .height(40.dp),
        colors = ButtonDefaults.buttonColors(containerColor = brightYellow),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = text,
            color = navyBlue,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    SafeSignRoadsApp(
        onEnableClick = {},
        onDisableClick = {},
        onSettingsClick = {},
        onAboutClick = {},
        onInstructionsClick = {},
        showEnableDialog = false,
        onDismissEnableDialog = {},
        showDisableDialog = false,
        onDismissDisableDialog = {},
        showAboutDialog = false,
        onDismissAboutDialog = {},
        showInstructionsDialog = false,
        onDismissInstructionsDialog = {},
        openAppSettings = {}
    )
}