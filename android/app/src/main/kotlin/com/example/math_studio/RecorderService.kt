package com.example.math_studio

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.app.Activity
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.media.MediaRecorder
import android.os.Environment
import java.io.File
import android.hardware.display.DisplayManager

class RecorderService : Service() {
    companion object {
     const val ACTION_RECORDING_STOPPED =
        "com.example.math_studio.RECORDING_STOPPED"
    }
    private var mediaRecorder: MediaRecorder? = null
    private var mediaProjection: MediaProjection? = null

    private lateinit var projectionManager: MediaProjectionManager

    private var virtualDisplay: VirtualDisplay? = null

    private val CHANNEL_ID = "recording_channel"
    
    private val NOTIFICATION_ID = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        projectionManager =
         getSystemService(MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        Log.d("RecorderService", "Service created")
    }

    override fun onStartCommand(
      intent: Intent?,
      flags: Int,
      startId: Int
    ): Int {
        
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
        Log.d("RecorderService", "Recording service started")
        startRecordingNotification()
val resolution =
    intent?.getStringExtra("resolution") ?: "1080p"

videoFps =
    intent?.getIntExtra("fps", 30) ?: 30

when (resolution) {

    "720p" -> {
        videoWidth = 1280
        videoHeight = 720
    }

    "1080p" -> {
        videoWidth = 1920
        videoHeight = 1080
    }

    "1440p" -> {
        videoWidth = 2560
        videoHeight = 1440
    }
}

Log.d(
    "RecorderService",
    "Video = ${videoWidth}x${videoHeight} @ $videoFps FPS"
)
        val resultCode =
        intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)

        val data: Intent? =
        intent?.getParcelableExtra("data")

        Log.d("RecorderService", "ResultCode = $resultCode")

        if (data != null) {

            mediaProjection =
              projectionManager.getMediaProjection(resultCode!!, data)

             Log.d("RecorderService", "MediaProjection CREATED")
             setupRecorder()
             startCapture()
        }

     return START_NOT_STICKY
    }
private fun startRecordingNotification() {

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Recorder",
            NotificationManager.IMPORTANCE_LOW
        )

        val manager =
            getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(channel)
    }

    val notification: Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Math Studio")
            .setContentText("Screen recording...")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .build()

    startForeground(
        NOTIFICATION_ID,
        notification
    )
}
private var videoWidth = 1920
private var videoHeight = 1080
private var videoFps = 30
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

    mediaRecorder = MediaRecorder()

    mediaRecorder?.apply {

        //setAudioSource(MediaRecorder.AudioSource.MIC)

        setVideoSource(MediaRecorder.VideoSource.SURFACE)

        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        setOutputFile(outputFile.absolutePath)

        setVideoEncoder(MediaRecorder.VideoEncoder.H264)

        //setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

        setVideoEncodingBitRate(8_000_000)

        setVideoFrameRate(videoFps)

setVideoSize(videoWidth, videoHeight)

            prepare()
     }

     Log.d("RecorderService", "Recorder READY")
    }
    private fun startCapture() {

    val metrics = resources.displayMetrics

    virtualDisplay = mediaProjection?.createVirtualDisplay(
    "MathStudioRecorder",
    videoWidth,
    videoHeight,
    metrics.densityDpi,
    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
    mediaRecorder!!.surface,
    null,
    null
)

    mediaRecorder?.start()

    Log.d("RecorderService", "RECORDING STARTED")
}
private fun pauseCapture() {

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder?.pause()
        Log.d("RecorderService", "RECORDING PAUSED")
    }
}
private fun resumeCapture() {

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder?.resume()
        Log.d("RecorderService", "RECORDING RESUMED")
    }
}
    private fun stopCapture() {

    try {

        virtualDisplay?.release()
        virtualDisplay = null

        mediaRecorder?.apply {
            stop()
            reset()
            release()
        }
        mediaRecorder = null

        //mediaProjection?.stop()
        //mediaProjection = null

        Log.d("RecorderService", "RECORDING STOPPED")
        MainActivity.eventSink?.success("STOP")
        val intent = Intent(ACTION_RECORDING_STOPPED)
sendBroadcast(intent)

    } catch (e: Exception) {
        Log.e("RecorderService", "STOP ERROR", e)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
    }

    stopSelf()
}
    override fun onDestroy() {
        super.onDestroy()
        Log.d("RecorderService", "Service destroyed")
    }
}