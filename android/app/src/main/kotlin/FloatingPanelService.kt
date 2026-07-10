package com.example.math_studio

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button

class FloatingPanelService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var paused = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordingStartRealtime = 0L
    private var pausedElapsedMillis = 0L
    private var pauseStartRealtime = 0L
    private var timerRunnable: Runnable? = null
    private var timerTextView: android.widget.TextView? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == RecorderService.ACTION_RECORDING_STATUS) {
                handleRecordingStatus(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("FloatingPanel", "Service Created")
        registerReceiver(statusReceiver, IntentFilter(RecorderService.ACTION_RECORDING_STATUS))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(overlayIntent)
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d("FloatingPanel", "Service Started")
        if (floatingView == null) {
            showFloatingPanel()
        }

        return START_STICKY
    }

    private fun showFloatingPanel() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_panel, null)

        val btnPause = floatingView!!.findViewById<Button>(R.id.btnPause)
        val btnStop = floatingView!!.findViewById<Button>(R.id.btnStop)
        val statusDot = floatingView!!.findViewById<View>(R.id.statusDot)
        val tvTimer = floatingView!!.findViewById<android.widget.TextView>(R.id.tvTimer)

        timerTextView = tvTimer
        tvTimer.text = "00:00"
        recordingStartRealtime = SystemClock.elapsedRealtime()
        pausedElapsedMillis = 0L
        pauseStartRealtime = 0L
        startTimer(tvTimer)

        btnPause.text = "⏸"
        btnPause.setOnClickListener {
            val action = if (paused) "RESUME" else "PAUSE"
            Log.d("FloatingPanel", "$action CLICKED")

            val recorderIntent = Intent(this, RecorderService::class.java)
            recorderIntent.action = action
            startService(recorderIntent)

            paused = !paused
            btnPause.text = if (paused) "▶" else "⏸"
            if (paused) {
                pauseStartRealtime = SystemClock.elapsedRealtime()
                stopTimer()
            } else {
                pausedElapsedMillis += SystemClock.elapsedRealtime() - pauseStartRealtime
                startTimer(tvTimer)
            }
            Log.d("FloatingPanel", "$action INTENT SENT")
        }

        btnStop.setOnClickListener {
            Log.d("FloatingPanel", "STOP CLICKED")
            val stopIntent = Intent(this, RecorderService::class.java)
            stopIntent.action = "STOP"
            startService(stopIntent)
            stopTimer()
            animateCloseAndStop()
            Log.d("FloatingPanel", "STOP INTENT SENT")
        }

        floatingView!!.alpha = 0f
        floatingView!!.scaleX = 0.9f
        floatingView!!.scaleY = 0.9f

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 160
        params.y = 260

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        floatingView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    snapToEdge(params)
                    true
                }

                else -> false
            }
        }

        windowManager.addView(floatingView, params)

        floatingView!!.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        statusDot.alpha = 1f
        val dotAnimator = android.animation.ValueAnimator.ofFloat(1f, 0.2f)
        dotAnimator.duration = 600
        dotAnimator.repeatMode = android.animation.ValueAnimator.REVERSE
        dotAnimator.repeatCount = android.animation.ValueAnimator.INFINITE
        dotAnimator.addUpdateListener { animation ->
            statusDot.alpha = animation.animatedValue as Float
        }
        dotAnimator.start()
    }

    private fun startTimer(tvTimer: android.widget.TextView) {
        stopTimer()
        timerRunnable = object : Runnable {
            override fun run() {
                val elapsedMillis = SystemClock.elapsedRealtime() - recordingStartRealtime - pausedElapsedMillis
                val totalSeconds = (elapsedMillis / 1000).toInt().coerceAtLeast(0)
                val minutes = (totalSeconds / 60).toString().padStart(2, '0')
                val seconds = (totalSeconds % 60).toString().padStart(2, '0')
                tvTimer.text = "$minutes:$seconds"
                timerHandler.postDelayed(this, 500)
            }
        }
        timerHandler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
    }

    private fun updateTimer(elapsedMillis: Long) {
        timerTextView?.text = formatElapsed(elapsedMillis)
    }

    private fun handleRecordingStatus(intent: Intent) {
        val status = intent.getStringExtra(RecorderService.EXTRA_RECORDING_STATUS) ?: return
        val elapsedMillis = intent.getLongExtra(RecorderService.EXTRA_RECORDING_ELAPSED, 0L)
        when (status) {
            RecorderService.STATUS_PAUSE -> {
                paused = true
                timerTextView?.text = formatElapsed(elapsedMillis)
                stopTimer()
            }
            RecorderService.STATUS_RESUME -> {
                paused = false
                recordingStartRealtime = SystemClock.elapsedRealtime() - elapsedMillis
                pausedElapsedMillis = 0L
                timerTextView?.text = formatElapsed(elapsedMillis)
                timerTextView?.let { startTimer(it) }
            }
            RecorderService.STATUS_STOP -> {
                paused = false
                timerTextView?.text = formatElapsed(elapsedMillis)
                stopTimer()
            }
        }
    }

    private fun formatElapsed(elapsedMillis: Long): String {
        val totalSeconds = (elapsedMillis / 1000).toInt().coerceAtLeast(0)
        val minutes = (totalSeconds / 60).toString().padStart(2, '0')
        val seconds = (totalSeconds % 60).toString().padStart(2, '0')
        return "$minutes:$seconds"
    }

    private fun snapToEdge(params: WindowManager.LayoutParams) {
        val screenWidth: Int
        val screenHeight: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val size = android.graphics.Point()
            display.getRealSize(size)
            screenWidth = size.x
            screenHeight = size.y
        }

        val viewWidth = floatingView?.width ?: 1
        val viewHeight = floatingView?.height ?: 1

        val targetX = if (params.x + viewWidth / 2 < screenWidth / 2) 16 else screenWidth - viewWidth - 16
        val targetY = params.y.coerceIn(16, screenHeight - viewHeight - 16)

        val animator = android.animation.ValueAnimator.ofInt(params.x, targetX)
        animator.duration = 250
        animator.interpolator = android.view.animation.OvershootInterpolator()
        animator.addUpdateListener { valueAnimator ->
            params.x = valueAnimator.animatedValue as Int
            params.y = targetY
            floatingView?.let { windowManager.updateViewLayout(it, params) }
        }
        animator.start()
    }

    private fun animateCloseAndStop() {
        floatingView?.animate()?.alpha(0f)?.scaleX(0.95f)?.scaleY(0.95f)
            ?.setDuration(180)
            ?.setInterpolator(android.view.animation.AccelerateInterpolator())
            ?.withEndAction {
                stopSelf()
            }
            ?.start()
    }

    override fun onDestroy() {
        stopTimer()
        unregisterReceiver(statusReceiver)
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (ignored: IllegalArgumentException) {
            }
            floatingView = null
        }

        super.onDestroy()
        Log.d("FloatingPanel", "Service Destroyed")
    }
}
