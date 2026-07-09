package com.example.math_studio

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel


class MainActivity : FlutterActivity() {

    companion object {
        const val CHANNEL = "math_studio/recorder"
        const val REQUEST_CODE = 1001
        const val EVENT_CHANNEL = "math_studio/recorder_events"
        var eventSink: EventChannel.EventSink? = null
    }

    private lateinit var projectionManager: MediaProjectionManager
    private var pendingResult: MethodChannel.Result? = null


private var selectedResolution = "1080p"
private var selectedFps = 30
    

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
EventChannel(
    flutterEngine.dartExecutor.binaryMessenger,
    EVENT_CHANNEL
).setStreamHandler(object : EventChannel.StreamHandler {

    override fun onListen(
        arguments: Any?,
        events: EventChannel.EventSink?
    ) {
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

    selectedResolution =
        call.argument<String>("resolution") ?: "1080p"

    selectedFps =
        call.argument<Int>("fps") ?: 30

    Log.d("MainActivity", "Resolution = $selectedResolution")
    Log.d("MainActivity", "FPS = $selectedFps")

    pendingResult = result

    val intent = projectionManager.createScreenCaptureIntent()
    startActivityForResult(intent, REQUEST_CODE)
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

    val stopIntent =
        Intent(this, RecorderService::class.java)

    stopIntent.action = "STOP"

    startService(stopIntent)

    result.success("STOP")
}

                else -> result.notImplemented()
            }
        }
    }


    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE) {

            if (resultCode == Activity.RESULT_OK && data != null) {

    Log.d("Recorder", "MEDIA PROJECTION GRANTED")

    val serviceIntent = Intent(this, RecorderService::class.java)

    serviceIntent.putExtra("resultCode", resultCode)
    serviceIntent.putExtra("data", data)
    serviceIntent.putExtra("resolution", selectedResolution)
serviceIntent.putExtra("fps", selectedFps)

    startService(serviceIntent)

    val floatingIntent =
    Intent(this, FloatingPanelService::class.java)

    startService(floatingIntent)

    pendingResult?.success("GRANTED")
} else {

                Log.d("Recorder", "MEDIA PROJECTION DENIED")

                pendingResult?.success("DENIED")
            }

            pendingResult = null
        }
    }
}