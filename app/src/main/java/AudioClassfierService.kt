package com.example.safesignroads

// --- Add necessary imports ---
import android.content.Context // For SharedPreferences
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat // For sending notifications
import okhttp3.* // OkHttp library
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject // For parsing JSON response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
// --- Keep other imports ---
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

/* ... other existing imports ... */

class AudioClassifierService : Service() {
    companion object {
        private const val TAG = "AudioService" // Moved TAG here too
        private const val CHANNEL_ID = "AudioClassifierChannel"
        private const val NOTIFICATION_ID = 1
        private const val DETECTION_NOTIFICATION_ID = 2
    }

    // ... other constants (CHANNEL_ID, NOTIFICATION_ID, sampleRate, etc.) ...
    private val sampleRate = 22050
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = 0


    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null

    // --- Buffering & Processing ---
    private val chunkDurationSeconds = 3
    private val samplesPerChunk = sampleRate * chunkDurationSeconds // 66150 shorts
    private val bytesPerChunk = samplesPerChunk * 2 // 16-bit = 2 bytes per sample
    private var audioChunkBuffer: ByteArrayOutputStream? = null

    // --- Threading for Network/Processing ---
    private var processingHandler: Handler? = null
    private var processingThread: HandlerThread? = null

    // --- Networking ---
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var sharedPreferences: SharedPreferences
    private var serverUrl: String = "http://192.168.1.15:8000/classify/" // Default

    // --- Notification/Vibration State ---
    private var lastDetectionTime: Long = 0L
    private var vibrationDuration: Float = 500f // Default

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
        createNotificationChannel()

        sharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        // Load initial settings
        loadSettings()

        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        // Ensure buffer is reasonably large, e.g., at least 1/4 second
        bufferSize = maxOf(bufferSize, sampleRate / 4 * 2) // *2 for bytes
        Log.d(TAG, "AudioRecord buffer size: $bufferSize bytes")

        audioChunkBuffer = ByteArrayOutputStream()

        // Initialize background thread for processing/networking
        processingThread = HandlerThread("AudioProcessingThread").apply { start() }
        processingHandler = Handler(processingThread!!.looper)

        // Initialize OkHttp client
        okHttpClient = OkHttpClient()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service onStartCommand received.")
        // Load potentially updated settings on each start command
        loadSettings()

        startForeground(NOTIFICATION_ID, createNotification())
        Log.i(TAG, "Service started in foreground.")

        if (!isRecording.get() && recordingThread == null) {
            isRecording.set(true)
            audioChunkBuffer?.reset() // Clear buffer on new start
            recordingThread = Thread { runRecordingLoop() }
            recordingThread?.start()
            Log.i(TAG, "Recording thread initiated.")
        } else {
            Log.w(TAG, "Start command received, but recording is already active.")
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun runRecordingLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { /* ... handle error ... */ return }

        try {
            Log.d(TAG, "Initializing AudioRecord...")
            audioRecord = AudioRecord( MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord init failed")

            // Use ByteArray for reading raw bytes
            val audioBuffer = ByteArray(bufferSize)
            audioRecord?.startRecording()
            Log.i(TAG, "*** AudioRecord started recording ***")
            Log.d(TAG, ">>> Entering recording loop <<<")

            while (isRecording.get()) {
                val bytesRead = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                // Log.v(TAG, "--- Loop: read $bytesRead bytes ---") // Verbose

                if (bytesRead > 0) {
                    // Append read data to the main chunk buffer
                    audioChunkBuffer?.write(audioBuffer, 0, bytesRead)

                    // Check if we have enough data for a chunk
                    while (audioChunkBuffer != null && audioChunkBuffer!!.size() >= bytesPerChunk) {
                        Log.d(TAG, "Full chunk detected (Buffer size: ${audioChunkBuffer?.size()})")
                        // Extract the chunk
                        val completeChunkBytes = audioChunkBuffer!!.toByteArray().copyOfRange(0, bytesPerChunk)
                        // Create new buffer with remaining data
                        val remainingBytes = audioChunkBuffer!!.toByteArray().copyOfRange(bytesPerChunk, audioChunkBuffer!!.size())
                        audioChunkBuffer = ByteArrayOutputStream().apply { write(remainingBytes) }

                        // --- Process the chunk on the background thread ---
                        Log.d(TAG, "Posting chunk for processing...")
                        processingHandler?.post {
                            processAndUploadChunk(completeChunkBytes)
                        }
                        // ----------------------------------------------------
                    }
                } else if (bytesRead < 0) {
                    Log.e(TAG, "AudioRecord read error: $bytesRead")
                }
            } // End while loop
            Log.i(TAG, "<<< Exited recording loop >>>")

        } catch (e: Exception) { Log.e(TAG, "Exception in recording thread", e) }
        finally { stopRecordingProcess() } // Ensure cleanup
    }

    // --- Runs on the processingThread ---
    private fun processAndUploadChunk(chunkBytes: ByteArray) {
        Log.d(TAG, "Processing chunk on thread: ${Thread.currentThread().name}")
        try {
            // 1. Format Data (e.g., as WAV bytes for simplicity, or just raw PCM if server handles it)
            // Creating a basic WAV header (44 bytes)
            val totalDataLen = chunkBytes.size + 36
            val totalAudioLen = chunkBytes.size.toLong()
            val channels: Short = 1 // Mono
            val byteRate = (sampleRate * 16 * channels / 8).toLong() // SampleRate * BitsPerSample * Channels / 8
            val blockAlign: Short = (channels * 16 / 8).toShort() // Channels * BitsPerSample / 8

            val wavHeader = ByteArray(44)
            val bb = ByteBuffer.wrap(wavHeader).order(ByteOrder.LITTLE_ENDIAN)

            bb.put('R'.code.toByte()).put('I'.code.toByte()).put('F'.code.toByte()).put('F'.code.toByte()) // RIFF header
            bb.putInt(totalDataLen) // Size of entire file - 4 bytes
            bb.put('W'.code.toByte()).put('A'.code.toByte()).put('V'.code.toByte()).put('E'.code.toByte()) // WAVE header
            bb.put('f'.code.toByte()).put('m'.code.toByte()).put('t'.code.toByte()).put(' '.code.toByte()) // fmt chunk
            bb.putInt(16) // Audio format 1 (PCM=16)
            bb.putShort(1) // Format = 1 (PCM)
            bb.putShort(channels) // Channels
            bb.putInt(sampleRate) // Sample Rate
            bb.putInt(byteRate.toInt()) // Byte Rate
            bb.putShort(blockAlign) // Block Align
            bb.putShort(16) // Bits per sample
            bb.put('d'.code.toByte()).put('a'.code.toByte()).put('t'.code.toByte()).put('a'.code.toByte()) // data chunk header
            bb.putInt(chunkBytes.size) // Size of data section.

            // Combine header and PCM data
            val wavBytes = ByteArrayOutputStream().apply {
                write(wavHeader)
                write(chunkBytes)
            }.toByteArray()

            // 2. Prepare Request Body
            val requestBody = wavBytes.toRequestBody("audio/wav".toMediaTypeOrNull())
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio_chunk.wav", requestBody)
                .build()

            // 3. Build Request
            val currentServerUrl = sharedPreferences.getString("server_ip", null)?.let { "http://$it:8000/classify/" } ?: serverUrl // Prefer fresh IP
            Log.d(TAG, "Sending chunk to: $currentServerUrl")
            val request = Request.Builder()
                .url(currentServerUrl)
                .post(multipartBody)
                .build()

            // 4. Execute Request (Synchronously on this background thread)
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "API call failed: ${response.code} ${response.message}")
                    throw IOException("Unexpected code $response")
                }

                val responseBody = response.body?.string()
                Log.i(TAG, "API Response: $responseBody")

                // 5. Process Response & Trigger Actions
                if (responseBody != null) {
                    val json = JSONObject(responseBody)
                    val emergencyCount = json.optInt("emergency_vehicle", 0)
                    val hornCount = json.optInt("car_horn", 0)
                    // val trafficCount = json.optInt("traffic", 0) // If needed

                    if (emergencyCount > 0 || hornCount > 0) {
                        triggerNotificationAndVibration(
                            isEmergency = (emergencyCount > 0),
                            isHorn = (hornCount > 0)
                        )
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing/uploading chunk", e)
            // Handle error - maybe send error event back to JS?
        }
    }

    // --- Notification/Vibration Trigger (called from processing thread) ---
    private fun triggerNotificationAndVibration(isEmergency: Boolean, isHorn: Boolean) {
        val now = System.currentTimeMillis()
        val debounceMs = 5000L // 5 seconds debounce

        // 1. Debounce Check
        if (now - lastDetectionTime < debounceMs) {
            Log.d(TAG, "Notification/Vibration debounced.")
            return
        }
        lastDetectionTime = now
        Log.i(TAG, "!!! Triggering Notification & Vibration !!!")

        // 2. Vibration (using duration from SharedPreferences)
        loadSettings() // Refresh settings just in case they changed
        vibratePhone(vibrationDuration) // Call helper

        // 3. Notification (Build and display)
        val title = if (isEmergency) "Emergency Vehicle Alert! 🚨" else "Car Horn Alert! 🔊"
        val body = if (isEmergency) "Emergency vehicle detected nearby!" else "Car horn detected nearby!"

        try {
            val notificationManager = NotificationManagerCompat.from(this)

            // Check POST_NOTIFICATIONS permission for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Cannot show detection alert.")
                // Optionally send event back to JS to prompt user if needed
                return // Exit if no permission on Android 13+
            }

            // Intent to open MainActivity when notification is tapped
            val intent = Intent(this, MainActivity::class.java)
            // Add flags to bring existing task to front or start new one
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(this, 1, intent, pendingIntentFlags) // Use different request code (1)

            // Use your actual small icon resource here!
            val smallIcon = R.mipmap.ic_launcher // IMPORTANT: Replace with real notification icon

            val notification = NotificationCompat.Builder(this, CHANNEL_ID) // Use same channel ID
                .setSmallIcon(smallIcon)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pendingIntent) // Action on tap
                .setPriority(NotificationCompat.PRIORITY_HIGH) // High priority for alerts
                .setDefaults(NotificationCompat.DEFAULT_SOUND) // Use default notification sound
                .setAutoCancel(true) // Dismiss notification when tapped
                .build()

            // Use a unique ID for this specific notification type
            notificationManager.notify(DETECTION_NOTIFICATION_ID, notification)
            Log.i(TAG, "Detection notification posted.")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to post detection notification", e)
        }
    }

    // --- Helper to load settings ---
    private fun loadSettings() {
        serverUrl = sharedPreferences.getString("server_ip", null)?.let { "http://$it:8000/classify/" } ?: "http://192.168.1.15:8000/classify/"
        // Read vibration duration using the correct key from SettingsActivity
        vibrationDuration = sharedPreferences.getFloat("vibration_duration", 500f)
        Log.d(TAG, "Settings loaded: URL=$serverUrl, VibDuration=$vibrationDuration")
    }

    // --- Helper to vibrate phone ---
    private fun vibratePhone(durationMs: Float) {
        if (durationMs <= 0) return // Don't vibrate if duration is zero or less

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager?
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        }

        if (vibrator == null || !vibrator.hasVibrator()) {
            Log.w(TAG, "Device does not support vibration or vibrator service not found.")
            return
        }

        Log.d(TAG, "Vibrating for ${durationMs.toLong()} ms")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create vibration effect for specified duration
            val effect = VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        } else {
            // Use deprecated method for older APIs
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs.toLong())
        }
    }

    private fun createNotification(): Notification { // Declared to return Notification
        // Intent to open your app's main activity when notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java) // Assumes MainActivity exists
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        // Placeholder small icon - replace R.mipmap.ic_launcher with a real notification icon
        // You MUST have a small icon defined in your drawable resources for this to work.
        // Create a simple white icon on a transparent background for notifications.
        val smallIcon = R.mipmap.ic_launcher // IMPORTANT: Use a real notification icon!

        // Build the notification object and return it
        return NotificationCompat.Builder(this, CHANNEL_ID) // Use the CHANNEL_ID constant defined earlier
            .setContentTitle("SafeSignRoad Active")
            .setContentText("Monitoring for critical sounds...")
            .setSmallIcon(smallIcon)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority for ongoing foreground service
            .setOngoing(true) // Makes it persistent
            .build() // <-- THIS IS CRUCIAL - it builds and returns the Notification object
    }
    // --- Service Lifecycle Methods (onDestroy, onBind - Keep from previous version) ---
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service Destroyed")
        stopRecordingProcess() // Stop recording if running
        recordingThread?.interrupt() // Interrupt thread if still running (might not be needed if loop checks flag)
        recordingThread = null
        stopForeground(true) // Remove notification
    }
    override fun onBind(intent: Intent?): IBinder? {
        // We don't provide binding, so return null
        return null
    }
    // --- Notification Channel Creation (Keep from previous version) ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Audio Classifier Service Channel",
                NotificationManager.IMPORTANCE_LOW // Use LOW or MIN to make it less intrusive
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            Log.d(TAG,"Notification channel created.")
        }
    }
    // --- Foreground Notification Creation (Keep from previous version) --- // --- stopRecordingProcess Helper (Keep from previous version) ---
    private fun stopRecordingProcess() {
        if (isRecording.compareAndSet(true, false)) { // Ensure stop logic runs only once
            Log.d(TAG, "Stopping AudioRecord and releasing resources...")
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                try { audioRecord?.stop() } catch (e: IllegalStateException) { Log.e(TAG, "Error stopping AudioRecord", e) }
            }
            try { audioRecord?.release() } catch (e: Exception) { Log.e(TAG, "Error releasing AudioRecord", e) }
            audioRecord = null
            Log.d(TAG, "AudioRecord stopped and released.")
        }
        // Consider stopping the service itself if appropriate
        // stopSelf()
    }


}