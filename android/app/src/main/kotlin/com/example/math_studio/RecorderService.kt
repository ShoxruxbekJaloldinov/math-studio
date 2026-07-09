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
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

class RecorderService : Service() {
    companion object {
        const val ACTION_RECORDING_STOPPED =
            "com.example.math_studio.RECORDING_STOPPED"
    }

    private val channelId = "recording_channel"
    private val notificationId = 1

    private lateinit var projectionManager: MediaProjectionManager

    private var mediaRecorder: MediaRecorder? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorderStarted = false
    private var recorderPaused = false

    private var videoWidth = 1920
    private var videoHeight = 1080
    private var videoDpi = 0
    private var videoFps = 30
    private var audioMode = "microphone"

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d("RecorderService", "MediaProjection stopped")
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
        Log.d("RecorderService", "Service created")
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
        audioMode = intent?.getStringExtra("audioMode") ?: "microphone"

        startRecordingNotification()

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = projectionData(intent)

        Log.d(
            "RecorderService",
            "Video = ${videoWidth}x${videoHeight}, dpi=$videoDpi @ $videoFps FPS, audio=$audioMode"
        )

        if (data == null || resultCode != Activity.RESULT_OK) {
            Log.e("RecorderService", "MediaProjection data missing or denied")
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

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder?.apply {
            if (recordsMicrophone()) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                Log.d("RecorderService", "Microphone audio source enabled")
            } else if (audioMode == "internal") {
                Log.w(
                    "RecorderService",
                    "Internal audio capture is not configured in this MediaRecorder pipeline"
                )
            }

            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputFile.absolutePath)

            if (recordsMicrophone()) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setAudioChannels(1)
            }

            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(videoBitrate())
            setVideoFrameRate(videoFps)
            setVideoSize(videoWidth, videoHeight)
            prepare()
        }

        Log.d("RecorderService", "Recorder ready: ${outputFile.absolutePath}")
    }

    private fun recordsMicrophone(): Boolean {
        return audioMode == "microphone" || audioMode == "both"
    }

    private fun videoBitrate(): Int {
        val calculated = (videoWidth * videoHeight * videoFps * 0.08).toInt()
        return calculated.coerceIn(8_000_000, 40_000_000)
    }

    private fun startCapture() {
        val recorderSurface = mediaRecorder?.surface
        if (recorderSurface == null) {
            Log.e("RecorderService", "Recorder surface is null")
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

        mediaRecorder?.start()
        recorderStarted = true
        recorderPaused = false

        Log.d("RecorderService", "Recording started")
    }

    private fun pauseCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w("RecorderService", "Pause requires Android N or newer")
            return
        }

        if (!recorderStarted || recorderPaused) {
            Log.d("RecorderService", "Pause ignored: started=$recorderStarted paused=$recorderPaused")
            return
        }

        try {
            mediaRecorder?.pause()
            recorderPaused = true
            MainActivity.eventSink?.success("PAUSE")
            Log.d("RecorderService", "Recording paused")
        } catch (e: IllegalStateException) {
            Log.e("RecorderService", "Pause failed", e)
        }
    }

    private fun resumeCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w("RecorderService", "Resume requires Android N or newer")
            return
        }

        if (!recorderStarted || !recorderPaused) {
            Log.d("RecorderService", "Resume ignored: started=$recorderStarted paused=$recorderPaused")
            return
        }

        try {
            mediaRecorder?.resume()
            recorderPaused = false
            MainActivity.eventSink?.success("RESUME")
            Log.d("RecorderService", "Recording resumed")
        } catch (e: IllegalStateException) {
            Log.e("RecorderService", "Resume failed", e)
        }
    }

    private fun stopCapture() {
        releaseRecorderResources(stopRecorder = true, stopProjection = true)
        notifyFlutterStopped()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
        Log.d("RecorderService", "Recording stopped")
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
            Log.e("RecorderService", "Release error", e)
        }
    }

    private fun notifyFlutterStopped() {
        MainActivity.eventSink?.success("STOP")
        sendBroadcast(Intent(ACTION_RECORDING_STOPPED))
    }

    override fun onDestroy() {
        releaseRecorderResources(stopRecorder = false, stopProjection = true)
        super.onDestroy()
        Log.d("RecorderService", "Service destroyed")
    }
}
