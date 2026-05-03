package com.autoclicker.arknights.service

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.autoclicker.arknights.R
import com.autoclicker.arknights.data.AppSettings
import com.autoclicker.arknights.data.ClickPoint
import com.autoclicker.arknights.data.ClickScheme
import com.autoclicker.arknights.data.SettingsManager
import com.autoclicker.arknights.ui.MainActivity
import com.autoclicker.arknights.ui.RecordingOverlayView
import kotlin.random.Random

/**
 * 悬浮窗服务
 * 提供悬浮窗UI和控制连点器的核心逻辑
 */
class FloatingWindowService : Service() {
    
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var recordingOverlay: RecordingOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settingsManager: SettingsManager
    
    // 连点器状态
    private var isRecording = false
    private var isRunning = false
    private var isPaused = false
    private val recordedPoints = mutableListOf<ClickPoint>()
    private var clickThread: Thread? = null
    
    // 设置
    private var settings: AppSettings = AppSettings()
    
    // 回调接口
    var onStateChanged: ((State) -> Unit)? = null
    var onPointsChanged: ((Int) -> Unit)? = null
    var onRecordingPoint: ((ClickPoint) -> Unit)? = null
    
    // 状态枚举
    enum class State {
        IDLE, RECORDING, RUNNING, PAUSED
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): FloatingWindowService = this@FloatingWindowService
    }
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settingsManager = SettingsManager.getInstance(this)
        settings = settingsManager.getSettings()
        createNotificationChannel()
        createFloatingWindow()
        Log.d(TAG, "FloatingWindowService created")
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopClicking()
        hideRecordingOverlay()
        try {
            windowManager.removeView(floatingView)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing floating view", e)
        }
        Log.d(TAG, "FloatingWindowService destroyed")
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 创建前台通知
     */
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    /**
     * 创建悬浮窗
     */
    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createFloatingWindow() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_window, null)
        
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }
        
        // 设置触摸监听，实现拖拽功能
        floatingView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    layoutParams.x += dx.toInt()
                    layoutParams.y += dy.toInt()
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }
                else -> false
            }
        }
        
        // 绑定按钮
        floatingView.findViewById<ImageButton>(R.id.btnRecord).setOnClickListener {
            toggleRecording()
        }
        
        floatingView.findViewById<ImageButton>(R.id.btnStartPause).setOnClickListener {
            toggleStartPause()
        }
        
        floatingView.findViewById<ImageButton>(R.id.btnStop).setOnClickListener {
            stop()
        }
        
        floatingView.findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            openSettings()
        }
        
        windowManager.addView(floatingView, layoutParams)
        updateButtonStates()
    }
    
    private var lastX = 0f
    private var lastY = 0f
    
    /**
     * 切换录制状态
     */
    fun toggleRecording() {
        isRecording = !isRecording
        if (isRecording) {
            // 开始录制前先停止连点
            stopClicking()
            recordedPoints.clear()
            onRecordingPoint?.invoke(ClickPoint(0f, 0f, -1)) // 发送清空信号
            // 显示录制覆盖层
            showRecordingOverlay()
        } else {
            // 隐藏录制覆盖层
            hideRecordingOverlay()
        }
        updateButtonStates()
        onStateChanged?.invoke(if (isRecording) State.RECORDING else State.IDLE)
        onPointsChanged?.invoke(recordedPoints.size)
        Toast.makeText(
            this,
            if (isRecording) getString(R.string.status_recording) else getString(R.string.status_idle),
            Toast.LENGTH_SHORT
        ).show()
    }
    
    /**
     * 添加录制点位
     */
    fun addRecordedPoint(x: Float, y: Float) {
        if (isRecording) {
            val point = ClickPoint(x, y, recordedPoints.size + 1)
            recordedPoints.add(point)
            onRecordingPoint?.invoke(point)
            onPointsChanged?.invoke(recordedPoints.size)
        }
    }
    
    /**
     * 结束录制
     */
    fun finishRecording() {
        isRecording = false
        updateButtonStates()
        onStateChanged?.invoke(State.IDLE)
        Toast.makeText(
            this,
            getString(R.string.point_recorded, recordedPoints.size),
            Toast.LENGTH_SHORT
        ).show()
    }
    
    /**
     * 切换开始/暂停
     */
    fun toggleStartPause() {
        if (isRunning) {
            togglePause()
        } else {
            startClicking()
        }
    }
    
    /**
     * 开始连点
     */
    fun startClicking() {
        if (recordedPoints.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_points_recorded), Toast.LENGTH_SHORT).show()
            return
        }
        
        if (isPaused) {
            // 继续
            isPaused = false
            isRunning = true
        } else {
            // 全新开始
            isRunning = true
            isPaused = false
        }
        
        updateButtonStates()
        onStateChanged?.invoke(State.RUNNING)
        
        clickThread = Thread {
            var loopCount = 0
            while (isRunning && (settings.loopCount < 0 || loopCount < settings.loopCount)) {
                if (!isPaused) {
                    for (point in recordedPoints) {
                        if (!isRunning) break
                        while (isPaused) {
                            Thread.sleep(50)
                            if (!isRunning) break
                        }
                        if (!isRunning) break
                        
                        // 执行点击
                        performClick(point)
                        
                        // 随机等待间隔
                        val interval = Random.nextInt(settings.minIntervalMs, settings.maxIntervalMs + 1)
                        Thread.sleep(interval.toLong())
                    }
                    loopCount++
                } else {
                    Thread.sleep(50)
                }
            }
            
            handler.post {
                isRunning = false
                updateButtonStates()
                onStateChanged?.invoke(State.IDLE)
            }
        }.apply {
            start()
        }
    }
    
    /**
     * 执行点击
     */
    private fun performClick(point: ClickPoint) {
        val service = AutoClickAccessibilityService.instance
        service?.performClick(point.x, point.y)
    }
    
    /**
     * 切换暂停
     */
    fun togglePause() {
        isPaused = !isPaused
        updateButtonStates()
        onStateChanged?.invoke(if (isPaused) State.PAUSED else State.RUNNING)
        Toast.makeText(
            this,
            if (isPaused) getString(R.string.status_paused) else getString(R.string.status_running),
            Toast.LENGTH_SHORT
        ).show()
    }
    
    /**
     * 停止连点
     */
    fun stop() {
        stopClicking()
        updateButtonStates()
        onStateChanged?.invoke(State.IDLE)
    }
    
    /**
     * 停止连点线程
     */
    private fun stopClicking() {
        isRunning = false
        isPaused = false
        clickThread?.interrupt()
        clickThread = null
    }
    
    /**
     * 打开设置页面
     */
    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_settings", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
    
    /**
     * 加载方案
     */
    fun loadScheme(scheme: ClickScheme) {
        recordedPoints.clear()
        recordedPoints.addAll(scheme.points)
        onPointsChanged?.invoke(recordedPoints.size)
        Toast.makeText(this, getString(R.string.scheme_load_success), Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 获取当前点位列表
     */
    fun getRecordedPoints(): List<ClickPoint> = recordedPoints.toList()
    
    /**
     * 获取当前状态
     */
    fun getState(): State {
        return when {
            isRecording -> State.RECORDING
            isRunning && isPaused -> State.PAUSED
            isRunning -> State.RUNNING
            else -> State.IDLE
        }
    }
    
    /**
     * 是否正在录制
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * 更新设置
     */
    fun updateSettings() {
        settings = settingsManager.getSettings()
    }
    
    /**
     * 更新按钮状态
     */
    private fun updateButtonStates() {
        if (!::floatingView.isInitialized) return
        
        handler.post {
            try {
                val btnRecord = floatingView.findViewById<ImageButton>(R.id.btnRecord)
                val btnStartPause = floatingView.findViewById<ImageButton>(R.id.btnStartPause)
                
                when {
                    isRecording -> {
                        btnRecord.alpha = 1f
                        btnStartPause.setImageResource(android.R.drawable.ic_media_pause)
                    }
                    isRunning -> {
                        btnRecord.alpha = 0.5f
                        btnStartPause.setImageResource(android.R.drawable.ic_media_pause)
                    }
                    else -> {
                        btnRecord.alpha = 1f
                        btnStartPause.setImageResource(android.R.drawable.ic_media_play)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating button states", e)
            }
        }
    }
    
    /**
     * 显示录制覆盖层
     */
    private fun showRecordingOverlay() {
        if (recordingOverlay != null) return
        
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        
        recordingOverlay = RecordingOverlayView(this).apply {
            onPointRecorded = { point ->
                addRecordedPoint(point.x, point.y)
            }
            onFinishRecording = {
                finishRecording()
            }
        }
        
        try {
            windowManager.addView(recordingOverlay, overlayParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing recording overlay", e)
        }
    }
    
    /**
     * 隐藏录制覆盖层
     */
    private fun hideRecordingOverlay() {
        recordingOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding recording overlay", e)
            }
        }
        recordingOverlay = null
        overlayParams = null
    }
    
    companion object {
        private const val TAG = "FloatingWindowService"
        private const val CHANNEL_ID = "autoclicker_service"
        private const val NOTIFICATION_ID = 1
        
        /**
         * 启动服务
         */
        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * 停止服务
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }
    }
}
