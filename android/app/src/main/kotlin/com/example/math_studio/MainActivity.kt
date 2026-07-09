package com.example.math_studio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        const val CHANNEL = "math_studio/recorder"
        const val REQUEST_CODE = 1001
        const val REQUEST_RECORD_AUDIO_CODE = 1002
        const val EVENT_CHANNEL = "math_studio/recorder_events"
        var eventSink: EventChannel.EventSink? = null
    }

    private lateinit var projectionManager: MediaProjectionManager
    private var pendingResult: MethodChannel.Result? = null

    private var selectedResolution = "1080p"
    private var selectedWidth: Int? = 1920
    private var selectedHeight: Int? = 1080
    private var selectedFps = 30
    private var selectedAudioMode = "microphone"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENT_CHANNEL
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "startRecording" -> {
                    selectedResolution = call.argument<String>("resolution") ?: "1080p"
                    selectedWidth = call.argument<Int>("width")
                    selectedHeight = call.argument<Int>("height")
                    selectedFps = call.argument<Int>("fps") ?: 30
                    selectedAudioMode = call.argument<String>("audioMode") ?: "microphone"

                    Log.d(
                        "MainActivity",
                        "Selected = $selectedResolution ${selectedWidth}x${selectedHeight} @ $selectedFps FPS audio=$selectedAudioMode"
                    )

                    pendingResult = result
                    if (needsMicrophonePermission() && !hasRecordAudioPermission()) {
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.RECORD_AUDIO),
                            REQUEST_RECORD_AUDIO_CODE
                        )
                    } else {
                        startProjectionRequest()
                    }
                }

                "pauseRecording" -> {
                    val pauseIntent = Intent(this, RecorderService::class.java)
                    pauseIntent.action = "PAUSE"
                    startService(pauseIntent)
                    result.success("PAUSE")
                }

                "resumeRecording" -> {
                    val resumeIntent = Intent(this, RecorderService::class.java)
                    resumeIntent.action = "RESUME"
                    startService(resumeIntent)
                    result.success("RESUME")
                }

                "stopRecording" -> {
                    val stopIntent = Intent(this, RecorderService::class.java)
                    stopIntent.action = "STOP"
                    startService(stopIntent)
                    result.success("STOP")
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun startProjectionRequest() {
        val intent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_CODE)
    }

    private fun needsMicrophonePermission(): Boolean {
        return selectedAudioMode == "microphone" || selectedAudioMode == "both"
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != REQUEST_RECORD_AUDIO_CODE) return

        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startProjectionRequest()
        } else {
            Log.e("MainActivity", "RECORD_AUDIO permission denied")
            pendingResult?.success("AUDIO_PERMISSION_DENIED")
            pendingResult = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CODE) return

        if (resultCode == Activity.RESULT_OK && data != null) {
            Log.d("Recorder", "MEDIA PROJECTION GRANTED")

            val metrics = currentDisplayMetrics()
            val (recordingWidth, recordingHeight) = resolveRecordingSize(
                selectedWidth,
                selectedHeight,
                metrics.widthPixels,
                metrics.heightPixels
            )

            val serviceIntent = Intent(this, RecorderService::class.java)
            serviceIntent.putExtra("resultCode", resultCode)
            serviceIntent.putExtra("data", data)
            serviceIntent.putExtra("resolution", selectedResolution)
            serviceIntent.putExtra("width", recordingWidth)
            serviceIntent.putExtra("height", recordingHeight)
            serviceIntent.putExtra("dpi", metrics.densityDpi)
            serviceIntent.putExtra("fps", selectedFps)
            serviceIntent.putExtra("audioMode", selectedAudioMode)

            Log.d(
                "MainActivity",
                "Starting service with ${recordingWidth}x${recordingHeight}, dpi=${metrics.densityDpi}, audio=$selectedAudioMode"
            )

            startService(serviceIntent)

            val floatingIntent = Intent(this, FloatingPanelService::class.java)
            startService(floatingIntent)

            pendingResult?.success("GRANTED")
        } else {
            Log.d("Recorder", "MEDIA PROJECTION DENIED")
            pendingResult?.success("DENIED")
        }

        pendingResult = null
    }

    private fun currentDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.getRealMetrics(metrics)
        } else {
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun resolveRecordingSize(
        requestedWidth: Int?,
        requestedHeight: Int?,
        displayWidth: Int,
        displayHeight: Int
    ): Pair<Int, Int> {
        val originalRequested = selectedResolution == "original" ||
            requestedWidth == null || requestedHeight == null ||
            requestedWidth <= 0 || requestedHeight <= 0

        if (originalRequested) {
            return Pair(displayWidth.even(), displayHeight.even())
        }

        val displayIsPortrait = displayHeight >= displayWidth
        val requestIsPortrait = requestedHeight >= requestedWidth

        val width: Int
        val height: Int
        if (displayIsPortrait == requestIsPortrait) {
            width = requestedWidth
            height = requestedHeight
        } else {
            width = requestedHeight
            height = requestedWidth
        }

        return Pair(width.even(), height.even())
    }

    private fun Int.even(): Int = if (this % 2 == 0) this else this - 1
}
