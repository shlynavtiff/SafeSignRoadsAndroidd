package com.example.safesignroads

import android.Manifest
import android.content.Context // For SharedPreferences
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat // For sending notifications
// Removed OkHttp imports as server communication is removed
// import okhttp3.*
// import okhttp3.MediaType.Companion.toMediaTypeOrNull
// import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject // For parsing JSON response (Might be removable if server removed fully)
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
import kotlin.math.max // Import for finding the max of two numbers (if needed, seems only maxOf is used)
import kotlin.math.min // Import for minOf


class AudioClassifierService : Service() {
    companion object {
        private const val TAG = "AudioService"
        private const val CHANNEL_ID = "AudioClassifierChannel"
        private const val NOTIFICATION_ID = 1
        private const val DETECTION_NOTIFICATION_ID = 2
        private const val DEFAULT_CAR_HORN_THRESHOLD = 0.15f
        private const val DEFAULT_EMERGENCY_VEHICLE_THRESHOLD = 0.14f
    }

    private var interpreter: org.tensorflow.lite.Interpreter? = null
    private val TFLITE_MODEL_FILENAME = "audio_classifier_model_3class_builtin_ops.tflite"

    private val sampleRate = 22050
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = 0

    private val mfccBufferSize = 2048
    private val mfccHopLength = 512
    private val mfccBufferOverlap = mfccBufferSize - mfccHopLength
    private val mfccNumMelFilters = 128
    private val mfccNumCoefficients = 20
    private val mfccLowerFreq = 0.0f
    private val mfccUpperFreq = sampleRate / 2.0f
    private val mfccProcessor: MFCC? by lazy {
        try {
            MFCC(mfccBufferSize, sampleRate.toFloat(), mfccNumCoefficients, mfccNumMelFilters, mfccLowerFreq, mfccUpperFreq)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TarsosDSP MFCC processor", e)
            null
        }
    }
    private val tarsosFormat by lazy {
        TarsosDSPAudioFormat(sampleRate.toFloat(), 16, 1, true, false)
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
    private lateinit var sharedPreferences: SharedPreferences
    private var lastDetectionTime: Long = 0L
    private var vibrationDuration: Float = 500f // Default

    private var carHornRmsThreshold = 0.14f
    private var emergencyVehicleRmsThreshold = 0.30f

    private val labelMap = mapOf(0 to "car_horn", 1 to "emergency_vehicle", 2 to "traffic")
    private val backgroundLabel = "background" // Label for ignored sounds


    @Throws(IOException::class)
    private fun loadModelFile(modelFileName: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(modelFileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        Log.d(TAG, "Loading TFLite model file: $modelFileName")
        Log.i(TAG, "car thresh: $carHornRmsThreshold, vehicle thresh: $emergencyVehicleRmsThreshold")
        val mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        fileChannel.close()
        inputStream.close()
        fileDescriptor.close()
        return mappedByteBuffer
    }

    private fun processAudioChunkNatively(chunkBytes: ByteArray) {
        Log.i(TAG, "--- processAudioChunkNatively called ---")
        if (chunkBytes.size != bytesPerChunk) { Log.e(TAG, "Incorrect chunk size: ${chunkBytes.size}, expected $bytesPerChunk"); return }
        val audioSamples = FloatArray(samplesPerChunk)
        val byteBuffer = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samplesPerChunk) {
            audioSamples[i] = byteBuffer.short / 32768.0f
        }
        var peak = 0.0f
        for (sample in audioSamples) { peak = maxOf(peak, abs(sample)) }
        val epsilon = 1e-5f
        if (peak > epsilon) {
            for (i in audioSamples.indices) { audioSamples[i] = audioSamples[i] / peak }
        } else {
        }

        if (mfccProcessor == null) { Log.e(TAG, "MFCC Processor is NULL!"); return }
        if (audioSamples.isEmpty()) { Log.e(TAG, "Audio samples array is empty!"); return }

        val allMfccFeatures = mutableListOf<FloatArray>()
        var currentPosition = 0
        var framesProcessed = 0
        val audioEvent = AudioEvent(tarsosFormat)
        audioEvent.overlap = mfccBufferOverlap

        while (currentPosition + mfccBufferSize <= audioSamples.size) {
            val buffer = audioSamples.copyOfRange(currentPosition, currentPosition + mfccBufferSize)
            audioEvent.setFloatBuffer(buffer)
            try {
                val processed = mfccProcessor!!.process(audioEvent)
                if (processed) {
                    val frameFeatures: FloatArray? = mfccProcessor!!.getMFCC()
                    if (frameFeatures != null) {
                        framesProcessed++
                        allMfccFeatures.add(frameFeatures.take(mfccNumCoefficients).toFloatArray())
                    } else { Log.w(TAG, "Frame at pos $currentPosition: getMFCC() returned null") }
                } else { Log.w(TAG, "MFCC processing method returned false for frame at pos $currentPosition") }
            } catch (e: Exception) { Log.e(TAG, "Error during TarsosDSP MFCC processing frame at pos $currentPosition", e); break }
            currentPosition += mfccHopLength
        }
        if (allMfccFeatures.isEmpty()) { Log.e(TAG, "No MFCC features extracted!"); return }


        val modelInputShape = interpreter?.getInputTensor(0)?.shape() ?: run { Log.e(TAG, "Cannot get TFLite model input shape."); return }
        val expectedFrames = modelInputShape[1]
        val expectedCoefficients = modelInputShape[2]
        if (allMfccFeatures.firstOrNull()?.size != expectedCoefficients) { Log.e(TAG, "MFCC coeff count mismatch!"); return }
        val inputBufferSize = 1 * expectedFrames * expectedCoefficients * 4
        val inputBuffer = ByteBuffer.allocateDirect(inputBufferSize).order(ByteOrder.nativeOrder())
        var framesToProcess = minOf(allMfccFeatures.size, expectedFrames)
        for (frameIndex in 0 until framesToProcess) {
            val frameFeatures = allMfccFeatures[frameIndex]
            for (coeffIndex in 0 until expectedCoefficients) { inputBuffer.putFloat(frameFeatures[coeffIndex]) }
        }
        if (framesToProcess < expectedFrames) {
            val remainingFloats = (expectedFrames - framesToProcess) * expectedCoefficients
            for (i in 0 until remainingFloats) { inputBuffer.putFloat(0.0f) }
        }
        inputBuffer.rewind()
        val modelOutputTensor = interpreter?.getOutputTensor(0)
        val modelOutputShape = modelOutputTensor?.shape()
        val modelOutputType = modelOutputTensor?.dataType()
        if (modelOutputShape == null || modelOutputType != org.tensorflow.lite.DataType.FLOAT32) { Log.e(TAG, "Invalid TFLite output tensor."); return }
        val outputClasses = modelOutputShape[1]
        val outputBufferSize = 1 * outputClasses * 4
        val outputBuffer = ByteBuffer.allocateDirect(outputBufferSize).order(ByteOrder.nativeOrder())

        if (interpreter == null) { Log.e(TAG, "TFLite interpreter is null."); return }
        try {
            interpreter?.run(inputBuffer, outputBuffer)
        } catch (e: Exception) { Log.e(TAG, "Error running TFLite inference", e); return }


        outputBuffer.rewind()
        val probabilities = FloatArray(outputClasses)
        outputBuffer.asFloatBuffer().get(probabilities)
        var maxProb = -1.0f
        var predictedIndex = -1
        for (i in probabilities.indices) {
            if (probabilities[i] > maxProb) {
                maxProb = probabilities[i]
                predictedIndex = i
            }
        }
        var predictedLabel = labelMap[predictedIndex] ?: backgroundLabel
        Log.i(TAG, "TFLite Output: Probs=${probabilities.joinToString()}, Index=$predictedIndex, Initial Label='$predictedLabel'")


        var finalLabel = predictedLabel

        if (predictedLabel == "car_horn") {
            val rmsSumOfSquares = audioSamples.map { (it * it).toDouble() }.sum()
            val rms = sqrt(rmsSumOfSquares / audioSamples.size).toFloat()
            Log.d(TAG, "Predicted 'car_horn'. RMS Energy: $rms, Threshold: $carHornRmsThreshold")
            if (rms < carHornRmsThreshold) { // Use member variable
                Log.i(TAG, "'car_horn' prediction ignored due to low RMS ($rms < $carHornRmsThreshold)")
                finalLabel = backgroundLabel
            } else {
                Log.d(TAG, "'car_horn' prediction confirmed (RMS >= threshold).")
            }
        }

        if (predictedLabel == "emergency_vehicle") {
            val rmsSumOfSquares = audioSamples.map { (it * it).toDouble() }.sum()
            val rms = sqrt(rmsSumOfSquares / audioSamples.size).toFloat()
            Log.d(TAG, "Predicted 'emergency_vehicle'. RMS Energy: $rms, Threshold: $emergencyVehicleRmsThreshold")
            if (rms < emergencyVehicleRmsThreshold) { // Use member variable
                Log.i(TAG, "'emergency_vehicle' prediction ignored due to low RMS ($rms < $emergencyVehicleRmsThreshold)")
                finalLabel = backgroundLabel
            } else {
                Log.d(TAG, "'emergency_vehicle' prediction confirmed (RMS >= threshold).")
            }
        }


        Log.i(TAG, "Final classification result: '$finalLabel'")
        if (finalLabel != backgroundLabel) {
            val isEmergency = (finalLabel == "emergency_vehicle")
            val isHorn = (finalLabel == "car_horn")
            if (isEmergency || isHorn) {
                Log.d(TAG, "Triggering notification/vibration for '$finalLabel'")
                triggerNotificationAndVibration(isEmergency = isEmergency, isHorn = isHorn)
            }
        }

        Log.i(TAG, "--- processAudioChunkNatively finished ---")
    }

    private fun logTensorDetails() {
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
        } catch (e: Exception) { Log.w(TAG, "Could not log tensor details", e) }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
        createNotificationChannel()

        try {
            val modelBuffer = loadModelFile(TFLITE_MODEL_FILENAME)
            val options = org.tensorflow.lite.Interpreter.Options()
            interpreter = org.tensorflow.lite.Interpreter(modelBuffer, options)
            Log.i(TAG, "TensorFlow Lite model loaded successfully.")
            logTensorDetails()
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Error initializing TFLite Interpreter", e)
            stopSelf()
        }

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        loadSettings()
        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        bufferSize = maxOf(bufferSize, sampleRate / 4 * 2) // Ensure reasonable size
        Log.d(TAG, "AudioRecord buffer size: $bufferSize bytes")
        audioChunkBuffer = ByteArrayOutputStream()
        processingThread = HandlerThread("AudioProcessingThread").apply { start() }
        processingHandler = Handler(processingThread!!.looper)

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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "RECORD_AUDIO permission missing!"); stopSelf(); return }

        try {
            Log.d(TAG, "Initializing AudioRecord...")
            audioRecord = AudioRecord( MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord init failed")

            val audioBuffer = ByteArray(bufferSize)
            audioRecord?.startRecording()
            Log.i(TAG, "*** AudioRecord started recording ***")

            while (isRecording.get()) {
                val bytesRead = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (bytesRead > 0) {
                    audioChunkBuffer?.write(audioBuffer, 0, bytesRead)
                    while (audioChunkBuffer != null && audioChunkBuffer!!.size() >= bytesPerChunk) {
                        val completeChunkBytes = audioChunkBuffer!!.toByteArray().copyOfRange(0, bytesPerChunk)
                        val remainingBytes = audioChunkBuffer!!.toByteArray().copyOfRange(bytesPerChunk, audioChunkBuffer!!.size())
                        audioChunkBuffer = ByteArrayOutputStream().apply { write(remainingBytes) }

                        processingHandler?.post {
                            processAudioChunkNatively(completeChunkBytes)
                        }
                    }
                } else if (bytesRead < 0) { Log.e(TAG, "AudioRecord read error: $bytesRead") }
            }
            Log.i(TAG, "<<< Exited recording loop >>>")
        } catch (e: Exception) { Log.e(TAG, "Exception in recording thread", e) }
        finally { stopRecordingProcess() }
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

        val title = if (isEmergency) "Emergency Vehicle Alert! 🚨" else "Vehicle Horn Alert! 🔊"
        val body = if (isEmergency) "Emergency vehicle detected nearby!" else "Vehicle horn detected nearby!"

        try {
            val notificationManager = NotificationManagerCompat.from(this)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted.")
                return
            }

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
            }
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else { PendingIntent.FLAG_UPDATE_CURRENT }
            val pendingIntent = PendingIntent.getActivity(this, 1, intent, pendingIntentFlags)

            val smallIcon = R.mipmap.ic_launcher

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(smallIcon)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(DETECTION_NOTIFICATION_ID, notification)
            Log.i(TAG, "Detection notification posted.")

        } catch (e: Exception) { Log.e(TAG, "Failed to post detection notification", e) }
    }

    private fun loadSettings() {
        vibrationDuration = sharedPreferences.getFloat("vibration_duration", 500f)

        Log.d(TAG, "Settings loaded: VibDuration=$vibrationDuration")
    }

    private fun vibratePhone(durationMs: Float) {
        if (durationMs <= 0) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager?
            vibratorManager?.defaultVibrator
        } else { @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator? }

        if (vibrator == null || !vibrator.hasVibrator()) { Log.w(TAG, "Device has no vibrator."); return }
        Log.d(TAG, "Vibrating for ${durationMs.toLong()} ms")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        } else { @Suppress("DEPRECATION") vibrator.vibrate(durationMs.toLong()) }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else { PendingIntent.FLAG_UPDATE_CURRENT }
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
        stopRecordingProcess()
        processingThread?.quitSafely()
        interpreter?.close()
        interpreter = null
        recordingThread?.interrupt()
        recordingThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Audio Classifier Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            Log.d(TAG,"Notification channel created.")
        }
    }

    private fun stopRecordingProcess() {
        if (isRecording.compareAndSet(true, false)) {
            Log.d(TAG, "Stopping AudioRecord...")
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                try { audioRecord?.stop() } catch (e: IllegalStateException) { Log.e(TAG, "Error stopping AudioRecord", e) }
            }
            try { audioRecord?.release() } catch (e: Exception) { Log.e(TAG, "Error releasing AudioRecord", e) }
            audioRecord = null
            Log.d(TAG, "AudioRecord stopped and released.")
        }
    }
}