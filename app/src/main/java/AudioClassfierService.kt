package com.example.safesignroads

// --- Add necessary imports ---
import android.Manifest
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
import be.tarsos.dsp.mfcc.MFCC
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import java.nio.MappedByteBuffer
import kotlin.math.abs // Import for absolute value function
import java.io.FileInputStream // Needed for loadModelFile
import java.nio.channels.FileChannel
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import kotlin.math.sqrt
 // Import for finding the max of two numbers

/* ... other existing imports ... */

class AudioClassifierService : Service() {
    companion object {
        private const val TAG = "AudioService" // Moved TAG here too
        private const val CHANNEL_ID = "AudioClassifierChannel"
        private const val NOTIFICATION_ID = 1
        private const val DETECTION_NOTIFICATION_ID = 2
    }


    private var interpreter: org.tensorflow.lite.Interpreter? = null // Use Interpreter from TFLite library
    private val TFLITE_MODEL_FILENAME = "audio_classifier_model_3class_builtin_ops.tflite" // Make sure this matches your model file in assets

    private val sampleRate = 22050
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = 0

    private val mfccBufferSize = 2048 // n_fft
    private val mfccHopLength = 512
    private val mfccBufferOverlap = mfccBufferSize - mfccHopLength // 1536
    private val mfccNumMelFilters = 128
    private val mfccNumCoefficients = 20
    private val mfccLowerFreq = 0.0f
    private val mfccUpperFreq = sampleRate / 2.0f
    private val mfccProcessor: MFCC? by lazy {
        try {
            // Use Android Studio's code completion/inspection on MFCC(...)
            // to find the correct constructor and parameter names/order.
            // Ensure you pass mfccNumCoefficients = 20 correctly!
            MFCC(mfccBufferSize, sampleRate.toFloat(), mfccNumCoefficients, mfccNumMelFilters, mfccLowerFreq, mfccUpperFreq)
            // ^^ This constructor is a GUESS! Adjust based on the actual library API.
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TarsosDSP MFCC processor", e)
            null
        }
    }
    private val tarsosFormat by lazy {
        // Parameters: sampleRate, sampleSizeInBits, channels, signed, bigEndian
        TarsosDSPAudioFormat(sampleRate.toFloat(), 16, 1, true, false) // 22050Hz, 16bit, 1ch, signed, little-endian
    }
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null

    private val chunkDurationSeconds = 3
    private val samplesPerChunk = sampleRate * chunkDurationSeconds
    private val bytesPerChunk = samplesPerChunk * 2
    private var audioChunkBuffer: ByteArrayOutputStream? = null

    private var processingHandler: Handler? = null
    private var processingThread: HandlerThread? = null

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var sharedPreferences: SharedPreferences
    private var serverUrl: String = "http://192.168.1.15:8000/classify/"
    private var lastDetectionTime: Long = 0L
    private var vibrationDuration: Float = 500f // Default
    private val labelMap = mapOf(0 to "car_horn", 1 to "emergency_vehicle", 2 to "traffic")
    private val backgroundLabel = "background" // Label for ignored sounds

    // Define threshold (from notebook.txt Cell 13, make tunable via SharedPreferences later if needed)
    private val CAR_HORN_RMS_THRESHOLD = 0.15f
    private val EMERGENCY_VEHICLE_RMS_THRESHOLD = 0.14f
    @Throws(IOException::class)
    private fun loadModelFile(modelFileName: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(modelFileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        Log.d(TAG, "Loading TFLite model file: $modelFileName")
        val mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        // It's good practice to close resources
        fileChannel.close()
        inputStream.close()
        fileDescriptor.close()
        return mappedByteBuffer
    }

    private fun processAudioChunkNatively(chunkBytes: ByteArray) {
        Log.i(TAG, "--- processAudioChunkNatively called ---")

        // (Step 1 & 2: Byte-to-Float & Normalization - This code remains the same)
        if (chunkBytes.size != bytesPerChunk) { /* ... log error ... */ return }
        val audioSamples = FloatArray(samplesPerChunk)
        val byteBuffer = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samplesPerChunk) {
            audioSamples[i] = byteBuffer.short / 32768.0f
        }
        Log.d(TAG, "Converted bytes to ${audioSamples.size} float samples.")
        var peak = 0.0f
        for (sample in audioSamples) { peak = maxOf(peak, abs(sample)) }
        val epsilon = 1e-5f
        if (peak > epsilon) {
            for (i in audioSamples.indices) { audioSamples[i] = audioSamples[i] / peak }
            Log.d(TAG,"Normalization applied. Peak: $peak")
        } else {
            Log.d(TAG,"Normalization skipped (silent audio). Peak: $peak")
        }
        // --- `audioSamples` FloatArray is ready ---

        Log.d(TAG, "Preprocessing complete. Starting MFCC calculation.")

        // --- Step 3: Calculate MFCCs using TarsosDSP ---
        if (mfccProcessor == null) { Log.e(TAG, "MFCC Processor is NULL!"); return }
        if (audioSamples.isEmpty()) { Log.e(TAG, "Audio samples array is empty!"); return }

        val allMfccFeatures = mutableListOf<FloatArray>()
        var currentPosition = 0
        var framesProcessed = 0

        // Create the AudioEvent object using the correct constructor
        val audioEvent = AudioEvent(tarsosFormat) // [cite: 4] Pass the format object
        audioEvent.overlap = mfccBufferOverlap // [cite: 9] Set overlap

        while (currentPosition + mfccBufferSize <= audioSamples.size) {
            val buffer = audioSamples.copyOfRange(currentPosition, currentPosition + mfccBufferSize)

            // Set the float buffer for the current frame
            audioEvent.setFloatBuffer(buffer) // [cite: 18]

            try {
                // Process this frame's event
                val processed = mfccProcessor!!.process(audioEvent) // [cite: 40] This call is correct

                if (processed) {
                    // Get the results using the correct method
                    val frameFeatures: FloatArray? = mfccProcessor!!.getMFCC() // [cite: 69] Correct method

                    if (frameFeatures != null) {
                        framesProcessed++
                        // The getMFCC() result should already have the correct number of coefficients (20)
                        // as defined by 'amountOfCepstrumCoef' in the constructor.
                        // .take() might not be strictly necessary if constructor works as expected, but doesn't hurt.
                        allMfccFeatures.add(frameFeatures.take(mfccNumCoefficients).toFloatArray())
                        if (framesProcessed % 10 == 1) {
                            Log.d(TAG, "Frame $framesProcessed (pos $currentPosition): Retrieved features size: ${frameFeatures.size}. First value: ${frameFeatures.firstOrNull()}")
                        }
                    } else {
                        Log.w(TAG, "Frame at pos $currentPosition: getMFCC() returned null features")
                    }
                } else {
                    Log.w(TAG, "MFCC processing method returned false for frame at pos $currentPosition")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during TarsosDSP MFCC processing frame at pos $currentPosition", e)
                break
            }
            currentPosition += mfccHopLength
        }

        Log.i(TAG, "Finished MFCC loop. Total frames processed successfully: $framesProcessed. Expected ~130 frames.")
        Log.i(TAG, "Final collected feature list size: ${allMfccFeatures.size}")
        Log.i(TAG, "Size of coefficients in first frame: ${allMfccFeatures.firstOrNull()?.size}") // Should now consistently be 20

        if (allMfccFeatures.isEmpty()) {
            Log.e(TAG, "No MFCC features were successfully extracted!")
            return
        }

        // --- `allMfccFeatures` is now List<FloatArray>, hopefully with ~130 FloatArrays of size 20 ---

        // --- Step 4: Prepare Input Buffer for TFLite ---
        val modelInputShape = interpreter?.getInputTensor(0)?.shape() ?: run {
            Log.e(TAG, "Cannot get TFLite model input shape.")
            return // Or handle error appropriately
        }

// Expected shape [1, 130, 20] based on logs [cite: 29]
        val expectedFrames = modelInputShape[1] // Should be 130
        val expectedCoefficients = modelInputShape[2] // Should be 20

        if (allMfccFeatures.firstOrNull()?.size != expectedCoefficients) {
            Log.e(TAG, "MFCC coefficient count (${allMfccFeatures.firstOrNull()?.size}) does not match model input ($expectedCoefficients)")
            return
        }

// Calculate buffer size: batch_size * frames * coefficients * bytes_per_float
        val inputBufferSize = 1 * expectedFrames * expectedCoefficients * 4 // 4 bytes for FLOAT32
        val inputBuffer = ByteBuffer.allocateDirect(inputBufferSize).order(ByteOrder.nativeOrder())
        Log.d(TAG, "Allocated TFLite input ByteBuffer with size: $inputBufferSize bytes")

// Fill the buffer, frame by frame, coefficient by coefficient
        var framesToProcess = minOf(allMfccFeatures.size, expectedFrames) // Use min to avoid index errors
        for (frameIndex in 0 until framesToProcess) {
            val frameFeatures = allMfccFeatures[frameIndex]
            for (coeffIndex in 0 until expectedCoefficients) {
                // Put the float value into the ByteBuffer
                inputBuffer.putFloat(frameFeatures[coeffIndex])
            }
        }

// If fewer frames were processed than expected (e.g., end of audio stream), fill remaining with zeros
        if (framesToProcess < expectedFrames) {
            Log.w(TAG, "Processed only $framesToProcess frames, expected $expectedFrames. Padding input buffer with zeros.")
            val remainingFloats = (expectedFrames - framesToProcess) * expectedCoefficients
            for (i in 0 until remainingFloats) {
                inputBuffer.putFloat(0.0f)
            }
        }

// VERY IMPORTANT: Rewind the buffer before running inference!
        inputBuffer.rewind()
        Log.d(TAG, "Input buffer prepared and rewound. Position: ${inputBuffer.position()}, Limit: ${inputBuffer.limit()}, Capacity: ${inputBuffer.capacity()}")
        // --- Step 5: Allocate Output Buffer for TFLite ---
        val modelOutputTensor = interpreter?.getOutputTensor(0)
        val modelOutputShape = modelOutputTensor?.shape()
        val modelOutputType = modelOutputTensor?.dataType()

        if (modelOutputShape == null || modelOutputType != org.tensorflow.lite.DataType.FLOAT32) {
            Log.e(TAG, "Cannot get valid TFLite model output shape or type is not FLOAT32.")
            return
        }
// Expected shape [1, 3]
        val outputClasses = modelOutputShape[1]
        val outputBufferSize = 1 * outputClasses * 4 // 4 bytes for FLOAT32
        val outputBuffer = ByteBuffer.allocateDirect(outputBufferSize).order(ByteOrder.nativeOrder())
        Log.d(TAG, "Allocated TFLite output ByteBuffer with size: $outputBufferSize bytes")


// --- Step 6: Run Inference ---
        if (interpreter == null) {
            Log.e(TAG, "TFLite interpreter is null, cannot run inference.")
            return
        }
        try {
            Log.d(TAG, "Running TFLite inference...")
            // Ensure buffers are ready and interpreter is not null
            interpreter?.run(inputBuffer, outputBuffer)
            Log.i(TAG, "TFLite inference ran successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error running TFLite inference", e)
            return // Stop processing if inference fails
        }
        // --- Step 7: Interpret Output ---
        outputBuffer.rewind() // Rewind buffer to read from the beginning
        val probabilities = FloatArray(outputClasses) // outputClasses should be 3
        outputBuffer.asFloatBuffer().get(probabilities) // Read floats into the array

        // Find the index with the highest probability
        var maxProb = -1.0f
        var predictedIndex = -1
        for (i in probabilities.indices) {
            if (probabilities[i] > maxProb) {
                maxProb = probabilities[i]
                predictedIndex = i
            }
        }

        // Map index to initial label
        var predictedLabel = labelMap[predictedIndex] ?: backgroundLabel // Default to background if index unknown

        Log.i(TAG, "TFLite Output: Probabilities=${probabilities.joinToString()}, Predicted Index=$predictedIndex, Initial Label='$predictedLabel'")


        // --- Step 8: (Optional) Apply Energy Threshold for Car Horn ---
        var finalLabel = predictedLabel // Start with the initial prediction

        if (predictedLabel == "car_horn") {
            // Calculate RMS of the normalized audio chunk (use audioSamples from Step 2)
            // Option 1: Use TarsosDSP static helper (if AudioEvent class is accessible/imported correctly)
            // val rms = AudioEvent.calculateRMS(audioSamples)

            // Option 2: Calculate RMS manually
            val rmsSumOfSquares = audioSamples.map { (it * it).toDouble() }.sum()
            val rms = sqrt(rmsSumOfSquares / audioSamples.size).toFloat()

            Log.d(TAG, "Predicted 'car_horn'. RMS Energy: $rms, Threshold: $CAR_HORN_RMS_THRESHOLD")

            // Apply threshold
            if (rms < CAR_HORN_RMS_THRESHOLD) {
                Log.i(TAG, "'car_horn' prediction ignored due to low RMS energy ($rms < $CAR_HORN_RMS_THRESHOLD)")
                finalLabel = backgroundLabel // Override label if below threshold
            } else {
                Log.d(TAG, "'car_horn' prediction confirmed (RMS >= threshold).")
            }
        }

        if (predictedLabel == "emergency_vehicle") {
            // Calculate RMS of the normalized audio chunk (use audioSamples from Step 2)
            // Option 1: Use TarsosDSP static helper (if AudioEvent class is accessible/imported correctly)
            // val rms = AudioEvent.calculateRMS(audioSamples)

            // Option 2: Calculate RMS manually
            val rmsSumOfSquares = audioSamples.map { (it * it).toDouble() }.sum()
            val rms = sqrt(rmsSumOfSquares / audioSamples.size).toFloat()

            Log.d(TAG, "Predicted 'emergency_vehicle'. RMS Energy: $rms, Threshold: ${EMERGENCY_VEHICLE_RMS_THRESHOLD}")

            // Apply threshold
            if (rms < EMERGENCY_VEHICLE_RMS_THRESHOLD) {
                Log.i(TAG, "'emergency_vehicle' prediction ignored due to low RMS energy ($rms < $EMERGENCY_VEHICLE_RMS_THRESHOLD)")
                finalLabel = backgroundLabel // Override label if below threshold
            } else {
                Log.d(TAG, "'emergency_vehicle' prediction confirmed (RMS >= threshold).")
            }
        }

        // --- Step 9: Trigger Action ---
        Log.i(TAG, "Final classification result: '$finalLabel'")
        if (finalLabel != backgroundLabel) {
            // Check if the final label requires notification/vibration
            val isEmergency = (finalLabel == "emergency_vehicle")
            val isHorn = (finalLabel == "car_horn")

            if (isEmergency || isHorn) {
                Log.d(TAG, "Triggering notification/vibration for '$finalLabel'")
                // Call your existing function
                triggerNotificationAndVibration(isEmergency = isEmergency, isHorn = isHorn)
            }
        }

        Log.i(TAG, "--- processAudioChunkNatively finished ---")

        Log.i(TAG, "--- processAudioChunkNatively finished ---")
    }

    private fun logTensorDetails() {
        // ...(implementation from previous code/audioclassifier.txt)...
        try {
            val inputCount = interpreter?.inputTensorCount ?: 0
            val outputCount = interpreter?.outputTensorCount ?: 0
            Log.d(TAG, "TFLite Model Details: Inputs=$inputCount, Outputs=$outputCount")
            for (i in 0 until inputCount) {
                val inputTensor = interpreter?.getInputTensor(i)
                Log.d(TAG, "  Input $i: Shape=${inputTensor?.shape()?.contentToString()}, DataType=${inputTensor?.dataType()}")
            }
            for (i in 0 until outputCount) {
                val outputTensor = interpreter?.getOutputTensor(i)
                Log.d(TAG, "  Output $i: Shape=${outputTensor?.shape()?.contentToString()}, DataType=${outputTensor?.dataType()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not log tensor details", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
        createNotificationChannel() // Keep this

        // --- Add TFLite Loading Logic ---
        try {
            val modelBuffer = loadModelFile(TFLITE_MODEL_FILENAME)
            val options = org.tensorflow.lite.Interpreter.Options()
            // options.setNumThreads(4) // Optional: Configure threads
            interpreter = org.tensorflow.lite.Interpreter(modelBuffer, options) // Initialize the interpreter
            Log.i(TAG, "TensorFlow Lite model loaded and interpreter initialized successfully.")
            logTensorDetails() // Keep this if you have it, helps verify model I/O
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Error initializing TFLite Interpreter", e)
            // Handle error appropriately - maybe stop the service?
        }
        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        loadSettings()

        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        bufferSize = maxOf(bufferSize, sampleRate / 4 * 2) // *2 for bytes
        Log.d(TAG, "AudioRecord buffer size: $bufferSize bytes")

        audioChunkBuffer = ByteArrayOutputStream()

        processingThread = HandlerThread("AudioProcessingThread").apply { start() }
        processingHandler = Handler(processingThread!!.looper)

        // okHttpClient = OkHttpClient()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service onStartCommand received.")
        loadSettings()

        startForeground(NOTIFICATION_ID, createNotification())
        Log.i(TAG, "Service started in foreground.")

        if (!isRecording.get() && recordingThread == null) {
            isRecording.set(true)
            audioChunkBuffer?.reset()
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {  return }

        try {
            Log.d(TAG, "Initializing AudioRecord...")
            audioRecord = AudioRecord( MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord init failed")

            val audioBuffer = ByteArray(bufferSize)
            audioRecord?.startRecording()
            Log.i(TAG, "*** AudioRecord started recording ***")
            Log.d(TAG, ">>> Entering recording loop <<<")

            while (isRecording.get()) {
                val bytesRead = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0

                if (bytesRead > 0) {
                    audioChunkBuffer?.write(audioBuffer, 0, bytesRead)

                    while (audioChunkBuffer != null && audioChunkBuffer!!.size() >= bytesPerChunk) {
                        Log.d(TAG, "Full chunk detected (Buffer size: ${audioChunkBuffer?.size()})")
                        val completeChunkBytes = audioChunkBuffer!!.toByteArray().copyOfRange(0, bytesPerChunk)
                        val remainingBytes = audioChunkBuffer!!.toByteArray().copyOfRange(bytesPerChunk, audioChunkBuffer!!.size())
                        audioChunkBuffer = ByteArrayOutputStream().apply { write(remainingBytes) }

                        Log.d(TAG, "Posting chunk for processing...")
                        processingHandler?.post {
                            processAudioChunkNatively(completeChunkBytes)
                        }
                    }
                } else if (bytesRead < 0) {
                    Log.e(TAG, "AudioRecord read error: $bytesRead")
                }
            }
            Log.i(TAG, "<<< Exited recording loop >>>")

        } catch (e: Exception) { Log.e(TAG, "Exception in recording thread", e) }
        finally { stopRecordingProcess() } // Ensure cleanup
    }


    private fun triggerNotificationAndVibration(isEmergency: Boolean, isHorn: Boolean) {
        val now = System.currentTimeMillis()
        val debounceMs = 5000L

        if (now - lastDetectionTime < debounceMs) {
            Log.d(TAG, "Notification/Vibration debounced.")
            return
        }
        lastDetectionTime = now
        Log.i(TAG, "!!! Triggering Notification & Vibration !!!")

        loadSettings()
        vibratePhone(vibrationDuration)

        val title = if (isEmergency) "Emergency Vehicle Alert! 🚨" else "Car Horn Alert! 🔊"
        val body = if (isEmergency) "Emergency vehicle detected nearby!" else "Car horn detected nearby!"

        try {
            val notificationManager = NotificationManagerCompat.from(this)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Cannot show detection alert.")
                return // Exit if no permission on Android 13+
            }

            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(this, 1, intent, pendingIntentFlags)

            val smallIcon = R.mipmap.ic_launcher

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(smallIcon)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_SOUND)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(DETECTION_NOTIFICATION_ID, notification)
            Log.i(TAG, "Detection notification posted.")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to post detection notification", e)
        }
    }

    private fun loadSettings() {
        serverUrl = sharedPreferences.getString("server_ip", null)?.let { "http://$it:8000/classify/" } ?: "http://192.168.1.15:8000/classify/"
        vibrationDuration = sharedPreferences.getFloat("vibration_duration", 500f)
        Log.d(TAG, "Settings loaded: URL=$serverUrl, VibDuration=$vibrationDuration")
    }

    private fun vibratePhone(durationMs: Float) {
        if (durationMs <= 0) return

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager?
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator?
        }

        if (vibrator == null || !vibrator.hasVibrator()) {
            Log.w(TAG, "Device does not support vibration or vibrator service not found.")
            return
        }

        Log.d(TAG, "Vibrating for ${durationMs.toLong()} ms")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs.toLong())
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val smallIcon = R.mipmap.ic_launcher

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SafeSignRoad Active")
            .setContentText("Monitoring for critical sounds...")
            .setSmallIcon(smallIcon)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service Destroyed")
        stopRecordingProcess() // Stop recording if running
        recordingThread?.interrupt() // Interrupt thread if still running (might not be needed if loop checks flag)
        recordingThread = null
        stopForeground(true) // Remove notification
    }
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
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
    }


}