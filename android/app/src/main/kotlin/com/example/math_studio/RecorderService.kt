package com.example.math_studio

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.math.roundToInt

enum class VideoCodec {
    H264,
    HEVC
}

class RecorderService : Service() {
    // Audio sozlamalarini markaziy va oson boshqarish uchun yagona konfiguratsiya modeli.
    private data class AudioConfig(
        val audioSource: Int,
        val audioEncoder: Int,
        val sampleRate: Int,
        val channelCount: Int,
        val bitrate: Int,
        val testingMode: String
    )

    companion object {
        const val ACTION_RECORDING_STOPPED =
            "com.example.math_studio.RECORDING_STOPPED"
        const val ACTION_RECORDING_STATUS =
            "com.example.math_studio.RECORDING_STATUS"
        const val EXTRA_RECORDING_STATUS = "status"
        const val EXTRA_RECORDING_ELAPSED = "elapsedMillis"
        const val STATUS_PAUSE = "PAUSE"
        const val STATUS_RESUME = "RESUME"
        const val STATUS_STOP = "STOP"
        const val MIC = "MIC"
        const val CAMCORDER = "CAMCORDER"
        const val VOICE_RECOGNITION = "VOICE_RECOGNITION"
        const val VOICE_COMMUNICATION = "VOICE_COMMUNICATION"
        const val UNPROCESSED = "UNPROCESSED"
        private const val DEFAULT_AUDIO_SOURCE_MODE = MIC
        private const val DEFAULT_AUDIO_ENCODER = MediaRecorder.AudioEncoder.AAC
        private const val DEFAULT_CHANNEL_COUNT = 1
        private const val DEFAULT_BITRATE = 192000
        private const val DEFAULT_SAMPLE_RATE = 44100

        internal fun calculateVideoBitrate(
            width: Int,
            height: Int,
            fps: Int,
            codec: VideoCodec
        ): Int {
            val safeWidth = width.coerceAtLeast(1)
            val safeHeight = height.coerceAtLeast(1)
            val safeFps = fps.coerceAtLeast(1)
            val referencePixels = 1920.0 * 1080.0
            val pixelCount = safeWidth.toDouble() * safeHeight.toDouble()
            val resolutionFactor = (pixelCount / referencePixels).coerceAtLeast(0.44)
            val fpsFactor = 1.0 + 0.50 * ((safeFps.toDouble() / 30.0) - 1.0)
            val codecFactor = if (codec == VideoCodec.HEVC) 0.70 else 1.0
            val calculatedBitrate = (8_000_000.0 * resolutionFactor * fpsFactor * codecFactor).roundToInt()
            return calculatedBitrate.coerceIn(2_000_000, 100_000_000)
        }
    }

    private val channelId = "recording_channel"
    private val notificationId = 1

    private lateinit var projectionManager: MediaProjectionManager
    private val audioDiagHandler = Handler(Looper.getMainLooper())

    private var mediaRecorder: MediaRecorder? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorderStarted = false
    private var recorderPaused = false

    private var videoWidth = 1920
    private var videoHeight = 1080
    private var videoDpi = 0
    private var videoFps = 30
    private var videoCodec: VideoCodec = VideoCodec.H264
    private var audioMode = "microphone"

    private var recordingStartRealtime = 0L
    private var recordingElapsedBeforePause = 0L
    private var recordingPauseStartRealtime = 0L
    private var audioFocusRequested = false
    private var activeAudioConfig: AudioConfig? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            logAudioDiag("MediaProjection stopped")
            releaseRecorderResources(stopRecorder = true, stopProjection = false)
            notifyFlutterStopped()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        logAudioDiag("Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PAUSE" -> {
                pauseCapture()
                return START_NOT_STICKY
            }

            "RESUME" -> {
                resumeCapture()
                return START_NOT_STICKY
            }

            "STOP" -> {
                stopCapture()
                return START_NOT_STICKY
            }
        }

        videoWidth = intent?.getIntExtra("width", 1920) ?: 1920
        videoHeight = intent?.getIntExtra("height", 1080) ?: 1080
        videoDpi = intent?.getIntExtra("dpi", resources.displayMetrics.densityDpi)
            ?: resources.displayMetrics.densityDpi
        videoFps = intent?.getIntExtra("fps", 30) ?: 30
        videoCodec = resolveVideoCodec(intent?.getStringExtra("videoCodec"))
        audioMode = intent?.getStringExtra("audioMode") ?: "microphone"

        startRecordingNotification()

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = projectionData(intent)

        logAudioDiag(
            "Video = ${videoWidth}x${videoHeight}, dpi=$videoDpi @ $videoFps FPS, audio=$audioMode"
        )
        logVideoCodecSelection()

        if (data == null || resultCode != Activity.RESULT_OK) {
            logAudioDiagError("MediaProjection data missing or denied")
            stopSelf()
            return START_NOT_STICKY
        }

        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(projectionCallback, null)

        setupRecorder()
        startCapture()

        return START_NOT_STICKY
    }

    private fun projectionData(intent: Intent?): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }
    }

    private fun startRecordingNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Screen Recorder",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification =
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("Math Studio")
                .setContentText("Screen recording...")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (recordsMicrophone()) {
                foregroundType = foregroundType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(notificationId, notification, foregroundType)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun setupRecorder() {
        val folder = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES
            ),
            "Math Studio"
        )

        if (!folder.exists()) {
            folder.mkdirs()
        }

        val outputFile = File(
            folder,
            "record_${System.currentTimeMillis()}.mp4"
        )

        logAudioDeviceDiagnostics()
        logAudioRoutingDiagnostics("before recording")

        if (!recordsMicrophone()) {
            mediaRecorder = createMediaRecorder()
            mediaRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outputFile.absolutePath)
                val selectedVideoEncoder = selectVideoEncoder()
                setVideoEncoder(selectedVideoEncoder)
                setVideoEncodingBitRate(calculateVideoBitrate(videoCodec))
                setVideoFrameRate(videoFps)
                setVideoSize(videoWidth, videoHeight)
                prepare()
            }
            logAudioDiag("Recorder ready: ${outputFile.absolutePath}")
            return
        }

        val selectedMode = requestedTestingMode()
        val initialConfig = buildAudioConfig(audioSourceFromTestingMode(selectedMode))
        activeAudioConfig = initialConfig

        if (!configureRecorderWithFallback(outputFile, initialConfig)) {
            logAudioDiagError("Recorder initialization failed")
            stopSelf()
            return
        }

        logAudioDiag("Recorder ready: ${outputFile.absolutePath}")
    }

    // MediaRecorder uchun xavfsiz fallback mexanizmi: agar bir manba ishlamasa, keyingi manbaga o'tadi.
    private fun configureRecorderWithFallback(outputFile: File, initialConfig: AudioConfig): Boolean {
        val candidates = buildAudioSourceCandidates(initialConfig.testingMode)
        var lastError: Exception? = null

        for ((index, candidateMode) in candidates.withIndex()) {
            val attemptConfig = buildAudioConfig(audioSourceFromTestingMode(candidateMode))
            mediaRecorder?.release()
            mediaRecorder = createMediaRecorder()

            try {
                val audioManager = getSystemService(AudioManager::class.java)
                val preferredDevice = findPreferredUsbInputDevice(audioManager)
                logAudioRouting("Selected AudioSource=${audioSourceName(attemptConfig.audioSource)}")
                logAudioRouting("Preferred Device=${preferredDevice?.let { describeDevice(it) } ?: "none"}")

                mediaRecorder?.apply {
                    setAudioSource(attemptConfig.audioSource)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(outputFile.absolutePath)
                    setAudioEncoder(attemptConfig.audioEncoder)
                    setAudioSamplingRate(attemptConfig.sampleRate)
                    setAudioEncodingBitRate(attemptConfig.bitrate)
                    setAudioChannels(attemptConfig.channelCount)
                    val selectedVideoEncoder = selectVideoEncoder()
                    setVideoEncoder(selectedVideoEncoder)
                    setVideoEncodingBitRate(calculateVideoBitrate(videoCodec))
                    setVideoFrameRate(videoFps)
                    setVideoSize(videoWidth, videoHeight)
                    applyPreferredDeviceIfSupported(preferredDevice)
                    prepare()
                }

                activeAudioConfig = attemptConfig
                logAudioDiag(
                    "MediaRecorder configured with source=${audioSourceName(attemptConfig.audioSource)}, sampleRate=${attemptConfig.sampleRate}, channels=${attemptConfig.channelCount}, bitrate=${attemptConfig.bitrate}, encoder=${audioEncoderName(attemptConfig.audioEncoder)}, testingMode=${attemptConfig.testingMode}"
                )
                return true
            } catch (e: Exception) {
                lastError = e
                mediaRecorder?.reset()
                mediaRecorder?.release()
                mediaRecorder = null
                if (index < candidates.lastIndex) {
                    logAudioDiag(
                        "Audio source fallback: ${audioSourceName(audioSourceFromTestingMode(candidateMode))} failed, trying ${audioSourceName(audioSourceFromTestingMode(candidates[index + 1]))}"
                    )
                }
            }
        }

        logAudioDiagError("MediaRecorder could not be initialized with any safe audio source", lastError)
        return false
    }

    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    private fun resolveVideoCodec(value: String?): VideoCodec {
        return when (value?.trim()?.uppercase()) {
            "HEVC" -> VideoCodec.HEVC
            "H264", "H.264" -> VideoCodec.H264
            else -> VideoCodec.H264
        }
    }

    private fun logVideoCodecSelection() {
        logAudioDiag("Selected codec = ${videoCodec.name}")
        if (videoCodec == VideoCodec.HEVC) {
            val hevcSupported = isHevcSupported()
            logAudioDiag("Hardware encoder found = $hevcSupported")
            if (!hevcSupported) {
                logAudioDiag("HEVC unavailable")
                logAudioDiag("Fallback -> H264")
            }
        }
    }

    private fun selectVideoEncoder(): Int {
        return if (videoCodec == VideoCodec.HEVC) {
            if (isHevcSupported()) {
                logAudioDiag("Using hardware HEVC encoder")
                MediaRecorder.VideoEncoder.HEVC
            } else {
                videoCodec = VideoCodec.H264
                logAudioDiag("HEVC not supported. Falling back to H264.")
                MediaRecorder.VideoEncoder.H264
            }
        } else {
            logAudioDiag("Using hardware H264 encoder")
            MediaRecorder.VideoEncoder.H264
        }
    }

    private fun isHevcSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false
        }

        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        return codecList.codecInfos.any { codecInfo ->
            codecInfo.isEncoder && codecInfo.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
        }
    }

    private fun calculateVideoBitrate(codec: VideoCodec): Int {
        val bitrate = calculateVideoBitrate(videoWidth, videoHeight, videoFps, codec)
        logAudioDiag(
            "Calculated video bitrate = ${bitrate}bps for ${videoWidth}x${videoHeight} @ ${videoFps}fps codec=${codec.name}"
        )
        return bitrate
    }

    private fun applyPreferredDeviceIfSupported(device: AudioDeviceInfo?) {
        if (device == null) {
            logAudioRouting("Preferred device rejected: no suitable USB input device found")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            logAudioRouting("Preferred device rejected: MediaRecorder.setPreferredDevice() is unavailable before Android 7.0")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Public Android SDK'da MediaRecorder uchun input qurilmasini tanlash imkoniyati mavjud bo'lsa ham,
                // ko'pgina OEM/Android build'larda bu tanlov Audio Policy Manager tomonidan yakunlanadi.
                // Agar qurilma qabul qilinmasa, biz faqat log chiqarib, routingni Android tizimiga qoldiramiz.
                if (hasPreferredDeviceApi()) {
                    val method = mediaRecorder?.javaClass?.getMethod("setPreferredDevice", AudioDeviceInfo::class.java)
                    method?.invoke(mediaRecorder, device)
                    logAudioRouting("Preferred device applied: ${describeDevice(device)}")
                } else {
                    logAudioRouting("Preferred device rejected: public MediaRecorder API is not available in this build")
                }
            }
        } catch (e: IllegalArgumentException) {
            logAudioRouting("Preferred device rejected: ${describeDevice(device)} (${e.message ?: "invalid device"})")
        } catch (e: IllegalStateException) {
            logAudioRouting("Preferred device rejected: ${describeDevice(device)} (${e.message ?: "invalid recorder state"})")
        } catch (e: ReflectiveOperationException) {
            logAudioRouting("Preferred device rejected: ${describeDevice(device)} (${e.message ?: "reflection failed"})")
        }
    }

    private fun findPreferredUsbInputDevice(audioManager: AudioManager?): AudioDeviceInfo? {
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty()
        val usbCandidates = devices.filter { device ->
            device.isSource && isUsbRoutingCandidate(device)
        }
        return usbCandidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: usbCandidates.firstOrNull()
    }

    private fun isUsbRoutingCandidate(device: AudioDeviceInfo): Boolean {
        val product = device.productName ?: ""
        val address = device.address ?: ""
        val typeName = device.type.toString()
        return typeName.contains("usb", ignoreCase = true) ||
            product.contains("usb", ignoreCase = true) ||
            address.contains("usb", ignoreCase = true) ||
            (device.type == 25 && (product.contains("mic", ignoreCase = true) || address.contains("usb", ignoreCase = true)))
    }

    private fun describeDevice(device: AudioDeviceInfo): String {
        return "id=${device.id}, type=${deviceTypeName(device.type)}, product=${device.productName ?: "n/a"}, address=${device.address ?: "n/a"}, sampleRates=${device.sampleRates.contentToString()}"
    }

    private fun hasPreferredDeviceApi(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            MediaRecorder::class.java.methods.any { it.name == "setPreferredDevice" }
    }

    private fun logAudioDeviceDiagnostics() {
        val audioManager = getSystemService(AudioManager::class.java)
        if (audioManager == null) {
            logAudioDiag("AudioManager unavailable")
            return
        }

        logAudioDiag("Device manufacturer: ${Build.MANUFACTURER}")
        logAudioDiag("Device model: ${Build.MODEL}")
        logAudioDiag("Android version: ${Build.VERSION.RELEASE}")
        logAudioDiag("SDK version: ${Build.VERSION.SDK_INT}")
        logAudioDiag("Audio mode: ${audioManager.mode}")
        logAudioDiag("isMusicActive: ${audioManager.isMusicActive}")
        logAudioDiag("isSpeakerphoneOn: ${audioManager.isSpeakerphoneOn}")
        logAudioDiag("isBluetoothScoOn: ${audioManager.isBluetoothScoOn}")
        logAudioDiag("isBluetoothA2dpOn: ${audioManager.isBluetoothA2dpOn}")

        logAudioDiag("Input devices:")
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).forEachIndexed { index, device ->
            logAudioDiag(
                "Input[$index] id=${device.id} type=${deviceTypeName(device.type)} product=${device.productName ?: "n/a"} address=${device.address ?: "n/a"} isSource=${device.isSource} isSink=${device.isSink} channels=${device.channelCounts.contentToString()} masks=${device.channelMasks.contentToString()} sampleRates=${device.sampleRates.contentToString()} encodings=${device.encodings.contentToString()}"
            )
        }

        logAudioDiag("Output devices:")
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEachIndexed { index, device ->
            logAudioDiag(
                "Output[$index] id=${device.id} type=${deviceTypeName(device.type)} product=${device.productName ?: "n/a"} address=${device.address ?: "n/a"} isSource=${device.isSource} isSink=${device.isSink} channels=${device.channelCounts.contentToString()} masks=${device.channelMasks.contentToString()} sampleRates=${device.sampleRates.contentToString()} encodings=${device.encodings.contentToString()}"
            )
        }

        logCommunicationDevice(audioManager)
        logAvailableMicrophones(audioManager)
        logUsbDeviceSummary(audioManager)
    }

    private fun logCommunicationDevice(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val communicationDevice = audioManager.communicationDevice
            logAudioDiag(
                "Communication device: ${communicationDevice?.productName ?: "n/a"} type=${deviceTypeName(communicationDevice?.type ?: AudioDeviceInfo.TYPE_UNKNOWN)} address=${communicationDevice?.address ?: "n/a"}"
            )
        } else {
            logAudioDiag("Communication device: unavailable before Android 12")
        }
    }

    private fun logAvailableMicrophones(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            logAudioDiag("Available microphones: unavailable before Android 12")
            return
        }

        val microphones = audioManager.getMicrophones()
        logAudioDiag("Available microphones (${microphones.size}):")
        microphones.forEachIndexed { index, microphone ->
            logAudioDiag(
                "Mic[$index] description=${readMicrophoneValue(microphone, "getDescription") ?: "n/a"} directionality=${readMicrophoneValue(microphone, "getDirectionality") ?: "n/a"} location=${readMicrophoneValue(microphone, "getLocation") ?: "n/a"} sensitivity=${readMicrophoneValue(microphone, "getSensitivity") ?: "n/a"} group=${readMicrophoneValue(microphone, "getGroup") ?: "n/a"} index=${readMicrophoneValue(microphone, "getIndex") ?: "n/a"} type=${readMicrophoneValue(microphone, "getType") ?: "n/a"} address=${readMicrophoneValue(microphone, "getAddress") ?: "n/a"} position=${readMicrophoneValue(microphone, "getPosition") ?: "n/a"} orientation=${readMicrophoneValue(microphone, "getOrientation") ?: "n/a"} frequencyResponse=${readMicrophoneValue(microphone, "getFrequencyResponse") ?: "n/a"}"
            )
        }
    }

    // Android SDK public API orqali USB mikrofonga to'g'ridan-to'g'ri tanlov qilish imkoni bo'lmasa,
    // bu yerda faqat mavjud qurilma haqida aniq log chiqariladi.
    private fun logUsbDeviceSummary(audioManager: AudioManager) {
        val usbDevices = mutableListOf<AudioDeviceInfo>()
        usbDevices.addAll(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter(::isUsbDevice))
        usbDevices.addAll(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter(::isUsbDevice))

        logAudioDiag("USB microphone detected: ${if (usbDevices.isEmpty()) "NO" else "YES"}")
        usbDevices.forEachIndexed { index, device ->
            logAudioDiag(
                "USB[$index] id=${device.id} type=${deviceTypeName(device.type)} product=${device.productName ?: "n/a"} address=${device.address ?: "n/a"}"
            )
        }
    }

    private fun isUsbDevice(device: AudioDeviceInfo): Boolean {
        val typeName = deviceTypeName(device.type)
        return typeName.contains("USB", ignoreCase = true) ||
            (device.productName ?: "").contains("usb", ignoreCase = true) ||
            (device.address ?: "").contains("usb", ignoreCase = true)
    }

    private fun deviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_UNKNOWN -> "UNKNOWN"
            else -> "TYPE_$type"
        }
    }

    private fun readMicrophoneValue(microphone: Any, methodName: String): String? {
        return try {
            val method = microphone.javaClass.getMethod(methodName)
            val value = method.invoke(microphone)
            when (value) {
                null -> null
                is Array<*> -> value.contentToString()
                is FloatArray -> value.contentToString()
                is IntArray -> value.contentToString()
                else -> value.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildAudioConfig(audioSource: Int): AudioConfig {
        val audioManager = getSystemService(AudioManager::class.java)
        val preferredSampleRate = if (audioManager != null) {
            preferredSampleRate(audioManager)
        } else {
            DEFAULT_SAMPLE_RATE
        }
        val selectedMode = requestedTestingMode()
        return AudioConfig(
            audioSource = audioSource,
            audioEncoder = MediaRecorder.AudioEncoder.AAC,
            sampleRate = preferredSampleRate,
            channelCount = DEFAULT_CHANNEL_COUNT,
            bitrate = DEFAULT_BITRATE,
            testingMode = selectedMode
        )
    }

    private fun preferredSampleRate(audioManager: AudioManager): Int {
        val preferredDevice = preferredInputDevice(audioManager)
        val candidateRates = intArrayOf(48000, 44100)
        val deviceRates = preferredDevice?.sampleRates?.toList() ?: emptyList()
        for (rate in candidateRates) {
            if (deviceRates.contains(rate)) {
                return rate
            }
        }
        val anyInput = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.isSource && it.sampleRates.isNotEmpty() }
        return anyInput?.sampleRates?.firstOrNull { it in candidateRates } ?: DEFAULT_SAMPLE_RATE
    }

    private fun preferredInputDevice(audioManager: AudioManager): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val usbInput = devices.firstOrNull { it.isSource && isUsbDevice(it) }
        return usbInput ?: devices.firstOrNull { it.isSource }
    }

    private fun buildAudioSourceCandidates(requestedMode: String): List<String> {
        val orderedModes = mutableListOf<String>()
        val requested = requestedMode.uppercase()
        if (requested != MIC) {
            orderedModes.add(requested)
        }
        orderedModes.addAll(
            listOf(MIC, VOICE_RECOGNITION, CAMCORDER, VOICE_COMMUNICATION, UNPROCESSED)
                .filter { it != requested }
        )
        return orderedModes
    }

    private fun requestedTestingMode(): String {
        return when ((audioMode ?: "").trim().uppercase()) {
            CAMCORDER -> CAMCORDER
            VOICE_RECOGNITION -> VOICE_RECOGNITION
            VOICE_COMMUNICATION -> VOICE_COMMUNICATION
            UNPROCESSED -> UNPROCESSED
            "MICROPHONE", "MIC", "BOTH" -> MIC
            else -> DEFAULT_AUDIO_SOURCE_MODE
        }
    }

    private fun audioSourceFromTestingMode(mode: String): Int {
        return when (mode.uppercase()) {
            CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
            VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.MIC
        }
    }

    private fun audioSourceName(audioSource: Int): String {
        return when (audioSource) {
            MediaRecorder.AudioSource.CAMCORDER -> CAMCORDER
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> VOICE_RECOGNITION
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> VOICE_COMMUNICATION
            MediaRecorder.AudioSource.UNPROCESSED -> UNPROCESSED
            else -> MIC
        }
    }

    private fun audioEncoderName(audioEncoder: Int): String {
        return when (audioEncoder) {
            MediaRecorder.AudioEncoder.AAC -> "AAC"
            else -> "AAC"
        }
    }

    private fun recordsMicrophone(): Boolean {
        val normalized = (audioMode ?: "").trim().lowercase()
        return normalized == "microphone" || normalized == "both" || normalized == "mic" || normalized == "camcorder" || normalized == "voice_recognition" || normalized == "voice-recognition" || normalized == "voice_communication" || normalized == "voice-communication" || normalized == "unprocessed"
    }

    private fun startCapture() {
        val recorderSurface = mediaRecorder?.surface
        if (recorderSurface == null) {
            logAudioDiag("Recorder surface is null")
            stopSelf()
            return
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MathStudioRecorder",
            videoWidth,
            videoHeight,
            videoDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorderSurface,
            null,
            null
        )

        requestAudioFocusSafely()
        mediaRecorder?.start()
        recorderStarted = true
        recorderPaused = false

        recordingStartRealtime = SystemClock.elapsedRealtime()
        recordingElapsedBeforePause = 0L
        recordingPauseStartRealtime = 0L
        sendRecordingStatus(STATUS_RESUME, 0L)

        logAudioDiag("Recording started")
        logAudioLevel("Estimated recording configuration: source=${audioSourceName(activeAudioConfig?.audioSource ?: MediaRecorder.AudioSource.MIC)}, sampleRate=${activeAudioConfig?.sampleRate ?: DEFAULT_SAMPLE_RATE}, channels=${activeAudioConfig?.channelCount ?: DEFAULT_CHANNEL_COUNT}, bitrate=${activeAudioConfig?.bitrate ?: DEFAULT_BITRATE}")
        logAudioLevel("Audio effects available: not directly configurable via public MediaRecorder API")
        logAudioLevel("AGC available: not directly configurable via public MediaRecorder API; VOICE_RECOGNITION/VOICE_COMMUNICATION may trigger Android automatic AGC")
        logAudioLevel("NS available: not directly configurable via public MediaRecorder API")
        logAudioLevel("AEC available: not directly configurable via public MediaRecorder API")
        logAudioRoutingDiagnostics("during recording")
        audioDiagHandler.postDelayed({ logAudioRoutingDiagnostics("during recording") }, 1000L)
    }

    private fun requestAudioFocusSafely() {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        if (audioFocusRequested) {
            return
        }

        val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .build()
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        audioFocusRequested = focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        logAudioDiag("Audio focus request result = $focusResult")
    }

    private fun abandonAudioFocusSafely() {
        if (!audioFocusRequested) {
            return
        }

        val audioManager = getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .build()
            audioManager.abandonAudioFocusRequest(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioFocusRequested = false
        logAudioDiag("Audio focus abandoned")
    }

    private fun logAudioRoutingDiagnostics(phase: String) {
        val audioManager = getSystemService(AudioManager::class.java)
        if (audioManager == null) {
            logAudioDiag("AudioManager unavailable during $phase routing diagnostics")
            return
        }

        logAudioDiag("Routing diagnostics [$phase]")
        logCommunicationDevice(audioManager)
        logConnectedDevices(audioManager)
        logAvailableMicrophones(audioManager)
        logAudioLevel("Audio mode: ${audioManager.mode}")
        logAudioLevel("Input device: ${findPreferredUsbInputDevice(audioManager)?.let { describeDevice(it) } ?: "none"}")
        logAudioDiag("Preferred sample rate: ${activeAudioConfig?.sampleRate ?: DEFAULT_SAMPLE_RATE}")
        logAudioDiag("Preferred encoding: AAC")
        logAudioDiag("Selected AudioSource: ${audioSourceName(activeAudioConfig?.audioSource ?: MediaRecorder.AudioSource.MIC)}")
    }

    private fun logConnectedDevices(audioManager: AudioManager) {
        val connectedDevices = audioManager.getDevices(
            AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS
        )
        logAudioDiag("Connected devices (${connectedDevices.size}):")
        connectedDevices.forEachIndexed { index, device ->
            val role = when {
                device.isSource -> "source"
                device.isSink -> "sink"
                else -> "unknown"
            }
            logAudioDiag(
                "Connected[$index] id=${device.id} type=${deviceTypeName(device.type)} product=${device.productName ?: "n/a"} address=${device.address ?: "n/a"} role=$role"
            )
        }
    }

    private fun logAudioDiag(message: String) {
        Log.d("RecorderService", "[AUDIO_DIAG] $message")
    }

    private fun logAudioRouting(message: String) {
        Log.d("RecorderService", "[AUDIO_ROUTING] $message")
    }

    private fun logAudioLevel(message: String) {
        Log.d("RecorderService", "[AUDIO_LEVEL] $message")
    }

    private fun logAudioDiagError(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e("RecorderService", "[AUDIO_DIAG] $message")
        } else {
            Log.e("RecorderService", "[AUDIO_DIAG] $message", throwable)
        }
    }

    private fun pauseCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            logAudioDiag("Pause requires Android N or newer")
            return
        }

        if (!recorderStarted || recorderPaused) {
            logAudioDiag("Pause ignored: started=$recorderStarted paused=$recorderPaused")
            return
        }

        try {
            mediaRecorder?.pause()
            recorderPaused = true
            recordingPauseStartRealtime = SystemClock.elapsedRealtime()
            recordingElapsedBeforePause = recordingPauseStartRealtime - recordingStartRealtime
            MainActivity.eventSink?.success(STATUS_PAUSE)
            sendRecordingStatus(STATUS_PAUSE, recordingElapsedBeforePause)
            logAudioDiag("Recording paused")
        } catch (e: IllegalStateException) {
            logAudioDiagError("Pause failed", e)
        }
    }

    private fun resumeCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            logAudioDiag("Resume requires Android N or newer")
            return
        }

        if (!recorderStarted || !recorderPaused) {
            logAudioDiag("Resume ignored: started=$recorderStarted paused=$recorderPaused")
            return
        }

        try {
            mediaRecorder?.resume()
            recorderPaused = false
            recordingStartRealtime = SystemClock.elapsedRealtime() - recordingElapsedBeforePause
            recordingPauseStartRealtime = 0L
            MainActivity.eventSink?.success(STATUS_RESUME)
            sendRecordingStatus(STATUS_RESUME, recordingElapsedBeforePause)
            logAudioDiag("Recording resumed")
        } catch (e: IllegalStateException) {
            logAudioDiagError("Resume failed", e)
        }
    }

    private fun stopCapture() {
        val elapsedMillis = if (recorderPaused) {
            recordingPauseStartRealtime - recordingStartRealtime
        } else {
            SystemClock.elapsedRealtime() - recordingStartRealtime
        }
        logAudioRoutingDiagnostics("after recording")
        sendRecordingStatus(STATUS_STOP, elapsedMillis)
        abandonAudioFocusSafely()
        releaseRecorderResources(stopRecorder = true, stopProjection = true)
        notifyFlutterStopped()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
        logAudioDiag("Recording stopped")
    }

    private fun releaseRecorderResources(
        stopRecorder: Boolean,
        stopProjection: Boolean
    ) {
        try {
            virtualDisplay?.release()
            virtualDisplay = null

            mediaRecorder?.apply {
                if (stopRecorder && recorderStarted) {
                    if (recorderPaused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        resume()
                    }
                    stop()
                }
                reset()
                release()
            }
            mediaRecorder = null
            recorderStarted = false
            recorderPaused = false

            mediaProjection?.unregisterCallback(projectionCallback)
            if (stopProjection) {
                mediaProjection?.stop()
            }
            mediaProjection = null
        } catch (e: Exception) {
            logAudioDiagError("Release error", e)
        }
    }

    private fun sendRecordingStatus(status: String, elapsedMillis: Long) {
        val statusIntent = Intent(ACTION_RECORDING_STATUS).apply {
            putExtra(EXTRA_RECORDING_STATUS, status)
            putExtra(EXTRA_RECORDING_ELAPSED, elapsedMillis)
        }
        sendBroadcast(statusIntent)
    }

    private fun notifyFlutterStopped() {
        MainActivity.eventSink?.success(STATUS_STOP)
        sendBroadcast(Intent(ACTION_RECORDING_STOPPED))
    }

    override fun onDestroy() {
        releaseRecorderResources(stopRecorder = false, stopProjection = true)
        super.onDestroy()
        logAudioDiag("Service destroyed")
    }
}
