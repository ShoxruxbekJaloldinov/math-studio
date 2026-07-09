package com.example.math_studio

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.provider.Settings
import android.net.Uri
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.os.Build
import android.widget.Button

class FloatingPanelService : Service() {

    private lateinit var windowManager: WindowManager

    private var floatingView: View? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        Log.d("FloatingPanel", "Service Created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (!Settings.canDrawOverlays(this)) {

    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName")
    )

    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    startActivity(intent)

    stopSelf()

    return START_NOT_STICKY
}

        Log.d("FloatingPanel", "Service Started")
        if (floatingView == null) {

    windowManager =
        getSystemService(WINDOW_SERVICE) as WindowManager

    floatingView = LayoutInflater.from(this)
        .inflate(R.layout.floating_panel, null)

    val btnPause =
        floatingView!!.findViewById<Button>(R.id.btnPause)

    val btnStop =
        floatingView!!.findViewById<Button>(R.id.btnStop)
    btnStop.setOnClickListener {

    Log.d("FloatingPanel", "STOP CLICKED")

    val stopIntent =
        Intent(this, RecorderService::class.java)

    stopIntent.action = "STOP"

    startService(stopIntent)

    Log.d("FloatingPanel", "STOP INTENT SENT")

    stopSelf()
}

    val params = WindowManager.LayoutParams(

        WindowManager.LayoutParams.WRAP_CONTENT,

        WindowManager.LayoutParams.WRAP_CONTENT,

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE,

        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,

        PixelFormat.TRANSLUCENT
    )

    params.gravity = Gravity.TOP or Gravity.START
    params.x = 100
    params.y = 300

    var startX = 0
var startY = 0
var touchX = 0f
var touchY = 0f

floatingView!!.setOnTouchListener { _, event ->

    when (event.action) {

        android.view.MotionEvent.ACTION_DOWN -> {

            startX = params.x
            startY = params.y

            touchX = event.rawX
            touchY = event.rawY

            true
        }

        android.view.MotionEvent.ACTION_MOVE -> {

            params.x = startX + (event.rawX - touchX).toInt()
            params.y = startY + (event.rawY - touchY).toInt()

            windowManager.updateViewLayout(
                floatingView,
                params
            )

            true
        }

        else -> false
    }
}

    windowManager.addView(floatingView, params)
}

        return START_STICKY
    }

    override fun onDestroy() {

    floatingView?.let {

        windowManager.removeView(it)

        floatingView = null
    }

    super.onDestroy()

    Log.d("FloatingPanel", "Service Destroyed")
}
}