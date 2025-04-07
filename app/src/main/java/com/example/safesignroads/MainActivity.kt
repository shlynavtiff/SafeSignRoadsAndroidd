package com.example.safesignroads

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.os.Build
import android.util.Log
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

class MainActivity : ComponentActivity() {

    private fun startAudioService() {
        if (!checkPermissions()) {
            Log.w("MainActivity", "startAudioService attempted but permissions not granted.")
            requestPermissions()
            return
        }

        Log.i("MainActivity", ">>> startAudioService called <<<")

        val serviceIntent = Intent(this, AudioClassifierService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Log.i("MainActivity", ">>> startForegroundService (or startService) command issued <<<")
    }

    private fun stopAudioService() {
        Log.i("MainActivity", "Attempting to stop AudioClassifierService...")
        val serviceIntent = Intent(this, AudioClassifierService::class.java)
        stopService(serviceIntent)
    }
    private fun loadFontSize() {
        appFontSize = sharedPreferences.getFloat("font_size", 19f)
        Log.d("MainActivity", "Loaded font size: $appFontSize")
    }
    override fun onResume() {
        super.onResume()
        loadFontSize()
    }
    private lateinit var sharedPreferences: android.content.SharedPreferences
    private var appFontSize by mutableStateOf(19f)
    override fun onCreate(savedInstanceState: Bundle?) {
        sharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        loadFontSize()
        super.onCreate(savedInstanceState)
        setContent {
            val currentFontSize = appFontSize
            var showEnableDialog by remember { mutableStateOf(false) }
            var showDisableDialog by remember { mutableStateOf(false) }
            var showAlreadyEnabled by remember { mutableStateOf(false) }
            var showAlreadyDisabled by remember { mutableStateOf(false) }
            var showAboutDialog by remember { mutableStateOf(false) }
            var showInstructionsDialog by remember { mutableStateOf(false) }
            var showRequestPermissions by remember { mutableStateOf(false)}
            var isServiceActive by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        Log.w("MainActivity", "POST_NOTIFICATIONS permission might be needed for foreground service on Android 13+")
                    }
                }
            }
            SafeSignRoadsApp(
                onEnableClick = {
                    if (isServiceActive){
                        showAlreadyEnabled = true
                    } else{
                        if (checkPermissions()) {
                            startAudioService()
                            isServiceActive = true
                            showEnableDialog = true
                        } else {
                            showRequestPermissions = true
                        }
                    }

                },
                onDisableClick = {
                    if (!isServiceActive){
                        showAlreadyDisabled = true
                    } else{
                        stopAudioService()
                        isServiceActive = false
                        showDisableDialog = true
                    }
                },
                onSettingsClick = { openSettingsActivity() },
                onAboutClick = { showAboutDialog = true },
                onInstructionsClick = { showInstructionsDialog = true },
                showEnableDialog = showEnableDialog,
                onDismissEnableDialog = { showEnableDialog = false },
                showDisableDialog = showDisableDialog,
                onDismissDisableDialog = { showDisableDialog = false },
                showAboutDialog = showAboutDialog,
                onDismissAboutDialog = { showAboutDialog = false },
                showInstructionsDialog = showInstructionsDialog,
                onDismissInstructionsDialog = { showInstructionsDialog = false },
                openAppSettings = { openAppSettings() },
                fontSize = appFontSize,
                showAlreadyEnabled = showAlreadyEnabled,
                onDismissAlreadyEnabled = {showAlreadyEnabled = false},
                showAlreadyDisabled = showAlreadyDisabled,
                onDismissAlreadyDisabled = {showAlreadyDisabled = false},
                showRequestPermissions = showRequestPermissions,
                onDismissRequestPermissions = {showRequestPermissions = false},
                onRequestPermission = {requestPermissions()}
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
            Manifest.permission.POST_NOTIFICATIONS
        )

        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
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
    openAppSettings: () -> Unit,
    fontSize: Float, // Add this parameter
    showAlreadyDisabled: Boolean,
    onDismissAlreadyEnabled: () -> Unit,
    showAlreadyEnabled: Boolean,
    onDismissAlreadyDisabled: () -> Unit,
    showRequestPermissions: Boolean,
    onDismissRequestPermissions: () -> Unit,
    onRequestPermission: () -> Unit
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
            contentScale = ContentScale.FillHeight
        )


        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            Spacer(modifier = Modifier.height(360.dp))


            YellowButton(text = "ENABLE", onClick = onEnableClick, fontSize = fontSize)

            Spacer(modifier = Modifier.height(12.dp))

            YellowButton(text = "DISABLE", onClick = onDisableClick, fontSize = fontSize)

            Spacer(modifier = Modifier.height(12.dp))

            YellowButton(text = "SETTINGS", onClick = onSettingsClick, fontSize = fontSize)

            Spacer(modifier = Modifier.height(12.dp))

            YellowButton(text = "INSTRUCTIONS", onClick = onInstructionsClick, fontSize = fontSize)

            Spacer(modifier = Modifier.height(12.dp))

            YellowButton(text = "ABOUT", onClick = onAboutClick, fontSize = fontSize)
        }


        if (showRequestPermissions) {
            AlertDialog(
                onDismissRequest = onDismissRequestPermissions,
                title = { Text("Allow Access to Microphone & Notifications", fontWeight = FontWeight.Bold, fontSize = fontSize.sp) },
                text = { Text("This application requires access to your microphone and notifications to detect vehicle horns and emergency sirens to provide you vibration alerts. Please grant necessary permissions for the best experience.",
                    fontSize = (fontSize * 0.9f).sp,
                    lineHeight = (fontSize * 1.2f).sp) },
                confirmButton = {
                    Button(onClick = {onDismissRequestPermissions(); onRequestPermission() }) {
                        Text("OK")
                    }
                }
            )
        }

        if (showAlreadyEnabled) {
            AlertDialog(
                onDismissRequest = onDismissAlreadyEnabled,
                title = { Text("Detection already started!", fontWeight = FontWeight.Bold, fontSize = fontSize.sp) },
                text = { Text("Detection for Vehicle Horns and Sirens has already started and running in the background.",
                    fontSize = (fontSize * 0.9f).sp,
                    lineHeight = (fontSize * 1.2f).sp) },
                confirmButton = {
                    Button(onClick = onDismissAlreadyEnabled) {
                        Text("OK")
                    }
                }
            )
        }
        if (showAlreadyDisabled) {
            AlertDialog(
                onDismissRequest = onDismissAlreadyDisabled,
                title = { Text("Detection is currently disabled.", fontWeight = FontWeight.Bold, fontSize = fontSize.sp) },
                text = { Text("Enable the detection first!",
                    fontSize = (fontSize * 0.9f).sp,
                    lineHeight = (fontSize * 1.2f).sp) },
                confirmButton = {
                    Button(onClick = onDismissAlreadyDisabled) {
                        Text("OK")
                    }
                }
            )
        }

        if (showEnableDialog) {
            AlertDialog(
                onDismissRequest = onDismissEnableDialog,
                title = { Text("Detection started.", fontWeight = FontWeight.Bold, fontSize = fontSize.sp) },
                text = { Text("Detection for Vehicle Horns and Sirens has Started",
                    fontSize = (fontSize * 0.9f).sp,
                    lineHeight = (fontSize * 1.2f).sp) },
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
                title = { Text("Detection stopped.", fontWeight = FontWeight.Bold, fontSize = fontSize.sp) },
                text = { Text("Detection for Vehicle Horns and Sirens has stopped.",
                    fontSize = (fontSize * 0.9f).sp,
                    lineHeight = (fontSize * 1.2f).sp) },
                confirmButton = {
                    Button(onClick = {
                        onDismissDisableDialog()
                    }) {
                        Text("OK")
                    }
                },
            )
        }

        if (showAboutDialog) {
            AlertDialog(
                onDismissRequest = onDismissAboutDialog,
                title = { Text("About SafeSign Roads", fontWeight = FontWeight.Bold, fontSize = fontSize.sp) },
                text = {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp) // Set a max height for the text area
                            .verticalScroll(scrollState) // Make it scrollable
                    ) {
                    Text(
                        buildAnnotatedString {
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("What do we offer?\n")
                            }
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("• Real-Time Sound Detection")
                            }
                            append(" – The app detects vehicle horns and ambulance sirens.\n")

                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("• Instant Alerts")
                            }
                            append(" – Receive vibration and visual notifications when sounds are detected.\n")

                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("• Increased Awareness")
                            }
                            append(" – Stay informed of approaching vehicles, even in noisy environments.\n")

                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("• Easy & Reliable")
                            }
                            append(" – Designed with a simple interface for quick and effortless use.\n\n")

                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("Meet the Developers\n")
                            }
                            append("SafeSign Roads was developed by ")
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("Mariel A. Cuerdo")
                            }
                            append(" and ")
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("Lynx J. Macasaet")
                            }
                            append(", dedicated developers passionate about using technology to create a safer world for the deaf community. Their mission is to bridge the communication gap between pedestrians and road safety through inclusive solutions.\n\n")

                            append("Walk safely. Stay aware. Let SafeSign Roads guide you.\n")
                        },
                        fontSize = (fontSize * 0.9f).sp,
                        lineHeight = (fontSize * 1.2f).sp
                    )
                    }
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
                title = { Text("How to Use SafeSign Roads", fontWeight = FontWeight.Bold, fontSize = fontSize.sp) },
                text = {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp) // Set a max height for the text area
                            .verticalScroll(scrollState) // Make it scrollable
                    ) {
                        Text(
                            text = buildAnnotatedString {
                                // Features
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append("Enable")
                                }
                                append(" – This will start monitoring for vehicle horns and sirens.\n")

                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append("Disable")
                                }
                                append(" – Revokes previously granted permissions, preventing the app from accessing the microphone, vibration, and notifications.\n")

                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append("Settings")
                                }
                                append(" – Customize your experience by adjusting font size and vibration intensity.\n")

                                append("Also, the developer will provide an IP address for real-time detection.\n")

                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append("About")
                                }
                                append(" – Learn more about SafeSign Roads, how it works, and the team behind its development.\n\n")

                                // Note
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append("Important Note:")
                                }
                                append(" To ensure the vibration feature works correctly, check your phone’s settings and make sure all vibration settings are enabled.")
                            },
                            fontSize = (fontSize * 0.9f).sp,
                            lineHeight = (fontSize * 1.2f).sp

                        )
                    }
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
fun YellowButton(text: String, onClick: () -> Unit, fontSize: Float) {
    val brightYellow = Color(0xFFFFDD00)
    val navyBlue = Color(0xFF003366)

    Button(
        onClick = onClick,
        modifier = Modifier
            .width(256.dp)
            .height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = brightYellow),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = navyBlue,
            fontSize = fontSize.sp, // Use the passed-in font size
            fontWeight = FontWeight.Bold
        )
    }
}

//@Preview(showBackground = true)
//@Composable
//fun DefaultPreview() {
//    SafeSignRoadsApp(
//        onEnableClick = {},
//        onDisableClick = {},
//        onSettingsClick = {},
//        onAboutClick = {},
//        onInstructionsClick = {},
//        showEnableDialog = false,
//        onDismissEnableDialog = {},
//        showDisableDialog = false,
//        onDismissDisableDialog = {},
//        showAboutDialog = false,
//        onDismissAboutDialog = {},
//        showInstructionsDialog = false,
//        onDismissInstructionsDialog = {},
//        openAppSettings = {},
//        fontSize = 12F
//    )
//}