package com.autoclicker.arknights.service

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
import android.os.CountDownTimer
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
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.autoclicker.arknights.R
import com.autoclicker.arknights.data.AppSettings
import com.autoclicker.arknights.data.ClickPoint
import com.autoclicker.arknights.data.ClickScheme
import com.autoclicker.arknights.data.OperationType
import com.autoclicker.arknights.data.SettingsManager
import com.autoclicker.arknights.ui.MainActivity
import com.autoclicker.arknights.ui.RecordingOverlayView
import com.autoclicker.arknights.util.ClickUtils
import kotlin.random.Random

/**
 * 悬浮窗服务 v1.1.0
 * 提供悬浮窗UI和控制连点器的核心逻辑
 */
class FloatingWindowService : Service() {
    
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var miniFloatView: View  // 最小化视图
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var miniLayoutParams: WindowManager.LayoutParams? = null
    private var recordingOverlay: RecordingOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settingsManager: SettingsManager
    
    // 连点器状态
    private var isRecording = false
    private var isRunning = false
    private var isPaused = false
    private var isMinimized = false
    private val recordedPoints = mutableListOf<ClickPoint>()
    private var clickThread: Thread? = null
    
    // 设置
    private var settings: AppSettings = AppSettings()
    
    // 定时启动
    private var scheduledTimer: CountDownTimer? = null
    private var countdownHandler: Handler? = null
    private var countdownRunnable: Runnable? = null
    
    // 微停顿相关
    private var clickCountSinceLastPause = 0
    private var nextPauseAt = 0
    
    // 回调接口
    var onStateChanged: ((State) -> Unit)? = null
    var onPointsChanged: ((Int) -> Unit)? = null
    var onRecordingPoint: ((ClickPoint) -> Unit)? = null
    var onCountdownUpdate: ((Long) -> Unit)? = null
    
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
        createMiniFloatingWindow()
        checkScheduledStart()
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
        cancelScheduledStart()
        hideRecordingOverlay()
        try {
            windowManager.removeView(floatingView)
            if (isMinimized && ::miniFloatView.isInitialized) {
                windowManager.removeView(miniFloatView)
            }
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
        
        val statusText = when {
            isRunning -> getString(R.string.status_running)
            isPaused -> getString(R.string.status_paused)
            isRecording -> getString(R.string.status_recording)
            else -> getString(R.string.notification_content)
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
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
        
        // 最小化按钮
        floatingView.findViewById<ImageButton>(R.id.btnMinimize)?.setOnClickListener {
            minimizeWindow()
        }
        
        // 定时启动按钮
        floatingView.findViewById<ImageButton>(R.id.btnScheduled)?.setOnClickListener {
            showScheduledDialog()
        }
        
        windowManager.addView(floatingView, layoutParams)
        updateButtonStates()
    }
    
    /**
     * 创建最小化悬浮窗（小圆点）
     */
    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createMiniFloatingWindow() {
        miniFloatView = LayoutInflater.from(this).inflate(R.layout.layout_mini_floating, null)
        
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        miniLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }
        
        // 设置触摸监听，实现拖拽功能
        miniFloatView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    miniLayoutParams?.x = (miniLayoutParams?.x ?: 0) + dx.toInt()
                    miniLayoutParams?.y = (miniLayoutParams?.y ?: 0) + dy.toInt()
                    windowManager.updateViewLayout(miniFloatView, miniLayoutParams)
                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }
                else -> false
            }
        }
        
        // 点击恢复
        miniFloatView.setOnClickListener {
            restoreWindow()
        }
    }
    
    /**
     * 最小化悬浮窗
     */
    private fun minimizeWindow() {
        if (isRecording || isRunning) {
            Toast.makeText(this, "运行中无法最小化", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            floatingView.visibility = View.GONE
            miniFloatView.visibility = View.VISIBLE
            isMinimized = true
        } catch (e: Exception) {
            Log.e(TAG, "Error minimizing window", e)
        }
    }
    
    /**
     * 恢复悬浮窗
     */
    private fun restoreWindow() {
        try {
            miniFloatView.visibility = View.GONE
            floatingView.visibility = View.VISIBLE
            isMinimized = false
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring window", e)
        }
    }
    
    /**
     * 检查定时启动
     */
    private fun checkScheduledStart() {
        val scheduledTime = settings.scheduledStartTime
        if (scheduledTime > System.currentTimeMillis()) {
            startScheduledCountdown(scheduledTime - System.currentTimeMillis())
        }
    }
    
    /**
     * 开始定时启动倒计时
     */
    private fun startScheduledCountdown(millisInFuture: Long) {
        cancelScheduledStart()
        
        scheduledTimer = object : CountDownTimer(millisInFuture, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                updateCountdownDisplay(seconds)
                onCountdownUpdate?.invoke(seconds)
            }
            
            override fun onFinish() {
                clearCountdownDisplay()
                if (!isRunning && !isRecording) {
                    startClicking()
                }
            }
        }.start()
        
        // 保存位置信息用于显示
        miniLayoutParams?.let { params ->
            params.x = layoutParams.x
            params.y = layoutParams.y
        }
    }
    
    /**
     * 更新倒计时显示
     */
    private fun updateCountdownDisplay(seconds: Long) {
        if (isMinimized) {
            miniFloatView.findViewById<TextView>(R.id.tvCountdown)?.text = "${seconds}s"
        } else {
            floatingView.findViewById<TextView>(R.id.tvCountdown)?.text = "${seconds}s"
        }
    }
    
    /**
     * 清除倒计时显示
     */
    private fun clearCountdownDisplay() {
        if (isMinimized) {
            miniFloatView.findViewById<TextView>(R.id.tvCountdown)?.text = ""
        } else {
            floatingView.findViewById<TextView>(R.id.tvCountdown)?.text = ""
        }
    }
    
    /**
     * 取消定时启动
     */
    fun cancelScheduledStart() {
        scheduledTimer?.cancel()
        scheduledTimer = null
        clearCountdownDisplay()
    }
    
    /**
     * 设置定时启动
     */
    fun setScheduledStart(hourOfDay: Int, minute: Int) {
        val calendar = java.util.Calendar.getInstance()
        val now = calendar.timeInMillis
        
        calendar.set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
        calendar.set(java.util.Calendar.MINUTE, minute)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        
        // 如果设置的时间已过，安排到明天
        if (calendar.timeInMillis <= now) {
            calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        
        settings = settings.copy(scheduledStartTime = calendar.timeInMillis)
        settingsManager.saveSettings(settings)
        startScheduledCountdown(calendar.timeInMillis - now)
        
        Toast.makeText(this, "已设定 ${hourOfDay}:${String.format("%02d", minute)} 启动", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 显示定时启动对话框
     */
    private fun showScheduledDialog() {
        // 使用TimePickerDialog
        val timePicker = android.app.TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                setScheduledStart(hourOfDay, minute)
            },
            java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
            java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE),
            true
        )
        timePicker.setTitle("设置定时启动")
        timePicker.show()
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
     * 添加录制点位（带操作类型）
     */
    fun addRecordedPoint(x: Float, y: Float, type: OperationType = OperationType.CLICK, duration: Long = 0) {
        if (isRecording) {
            val point = ClickPoint(x, y, recordedPoints.size + 1, type, duration)
            recordedPoints.add(point)
            onRecordingPoint?.invoke(point)
            onPointsChanged?.invoke(recordedPoints.size)
        }
    }
    
    /**
     * 添加等待步骤
     */
    fun addWaitStep(durationMs: Long = settings.waitDuration.toLong()) {
        if (isRecording) {
            val point = ClickPoint(0f, 0f, recordedPoints.size + 1, OperationType.WAIT, durationMs)
            recordedPoints.add(point)
            onRecordingPoint?.invoke(point)
            onPointsChanged?.invoke(recordedPoints.size)
            Toast.makeText(this, "已添加等待 ${durationMs}ms", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 添加长按步骤
     */
    fun addLongPressPoint(x: Float, y: Float, durationMs: Long = settings.longPressDuration.toLong()) {
        if (isRecording) {
            val point = ClickPoint(x, y, recordedPoints.size + 1, OperationType.LONG_PRESS, durationMs)
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
            clickCountSinceLastPause = 0
            // 计算下次停顿位置
            nextPauseAt = Random.nextInt(settings.pauseMinClicks, settings.pauseMaxClicks + 1)
        }
        
        updateButtonStates()
        onStateChanged?.invoke(State.RUNNING)
        
        // 启动点击线程
        clickThread = Thread {
            runClickingLoop()
        }.apply {
            isDaemon = true
            start()
        }
    }
    
    /**
     * 连点循环
     */
    private fun runClickingLoop() {
        val service = AutoClickAccessibilityService.instance
        if (service == null) {
            Log.e(TAG, "AccessibilityService not available")
            handler.post {
                Toast.makeText(this@FloatingWindowService, "无障碍服务未启动", Toast.LENGTH_SHORT).show()
                stopClicking()
            }
            return
        }
        
        var loopIndex = 0
        var totalClicks = 0
        
        while (isRunning) {
            if (isPaused) {
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }
            
            // 检查循环次数
            if (settings.loopCount > 0 && loopIndex >= settings.loopCount) {
                handler.post {
                    Toast.makeText(this, "已完成 ${settings.loopCount} 次循环", Toast.LENGTH_SHORT).show()
                    stopClicking()
                }
                break
            }
            
            for ((index, point) in recordedPoints.withIndex()) {
                if (!isRunning || isPaused) break
                
                // 处理不同操作类型
                when (point.type) {
                    OperationType.CLICK -> {
                        // 普通点击，可能需要偏移
                        ClickUtils.click(
                            service = service,
                            x = point.x,
                            y = point.y,
                            offsetRange = settings.offsetRange
                        )
                        totalClicks++
                        clickCountSinceLastPause++
                    }
                    OperationType.LONG_PRESS -> {
                        // 长按
                        ClickUtils.longPress(
                            service = service,
                            x = point.x,
                            y = point.y,
                            duration = point.duration,
                            offsetRange = settings.offsetRange
                        )
                        totalClicks++
                        clickCountSinceLastPause++
                    }
                    OperationType.WAIT -> {
                        // 等待
                        try {
                            Thread.sleep(point.duration)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }
                
                // 检查是否需要微停顿
                if (clickCountSinceLastPause >= nextPauseAt) {
                    val pauseDuration = Random.nextInt(settings.pauseMinDuration, settings.pauseMaxDuration + 1)
                    Log.d(TAG, "Micro pause for $pauseDuration ms at $totalClicks total clicks")
                    try {
                        Thread.sleep(pauseDuration.toLong())
                    } catch (e: InterruptedException) {
                        break
                    }
                    clickCountSinceLastPause = 0
                    nextPauseAt = Random.nextInt(settings.pauseMinClicks, settings.pauseMaxClicks + 1)
                }
                
                // 随机间隔
                val interval = Random.nextInt(settings.minIntervalMs, settings.maxIntervalMs + 1)
                try {
                    Thread.sleep(interval.toLong())
                } catch (e: InterruptedException) {
                    break
                }
            }
            
            loopIndex++
        }
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
        Toast.makeText(this, getString(R.string.btn_stop), Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 停止连点
     */
    private fun stopClicking() {
        isRunning = false
        isPaused = false
        clickThread?.interrupt()
        clickThread = null
        updateButtonStates()
        onStateChanged?.invoke(State.IDLE)
    }
    
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
     * 获取录制的点位
     */
    fun getRecordedPoints(): List<ClickPoint> = recordedPoints.toList()
    
    /**
     * 更新按钮状态
     */
    private fun updateButtonStates() {
        if (!::floatingView.isInitialized) return
        
        val btnRecord = floatingView.findViewById<ImageButton>(R.id.btnRecord)
        val btnStartPause = floatingView.findViewById<ImageButton>(R.id.btnStartPause)
        val btnStop = floatingView.findViewById<ImageButton>(R.id.btnStop)
        val btnSettings = floatingView.findViewById<ImageButton>(R.id.btnSettings)
        
        handler.post {
            when {
                isRecording -> {
                    btnRecord?.setColorFilter(getColor(R.color.status_recording))
                    btnStartPause?.isEnabled = false
                    btnStop?.isEnabled = false
                    btnSettings?.isEnabled = false
                }
                isRunning -> {
                    btnRecord?.isEnabled = false
                    btnStartPause?.setImageResource(android.R.drawable.ic_media_pause)
                    btnStartPause?.isEnabled = true
                    btnStop?.isEnabled = true
                    btnSettings?.isEnabled = false
                }
                isPaused -> {
                    btnRecord?.isEnabled = false
                    btnStartPause?.setImageResource(android.R.drawable.ic_media_play)
                    btnStartPause?.isEnabled = true
                    btnStop?.isEnabled = true
                    btnSettings?.isEnabled = false
                }
                else -> {
                    btnRecord?.isEnabled = true
                    btnRecord?.setColorFilter(getColor(R.color.text_secondary))
                    btnStartPause?.setImageResource(android.R.drawable.ic_media_play)
                    btnStartPause?.isEnabled = recordedPoints.isNotEmpty()
                    btnStop?.isEnabled = false
                    btnSettings?.isEnabled = true
                }
            }
        }
    }
    
    /**
     * 显示录制覆盖层
     */
    @SuppressLint("InflateParams")
    private fun showRecordingOverlay() {
        if (recordingOverlay != null) return
        
        recordingOverlay = RecordingOverlayView(this).apply {
            onPointRecorded = { point ->
                // 录制的默认都是点击类型
            }
            onFinishRecording = {
                finishRecording()
            }
        }
        
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        
        windowManager.addView(recordingOverlay, overlayParams)
    }
    
    /**
     * 隐藏录制覆盖层
     */
    private fun hideRecordingOverlay() {
        recordingOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing recording overlay", e)
            }
            recordingOverlay = null
        }
    }
    
    /**
     * 刷新设置
     */
    fun refreshSettings() {
        settings = settingsManager.getSettings()
    }
    
    /**
     * 加载方案
     */
    fun loadScheme(scheme: ClickScheme) {
        recordedPoints.clear()
        recordedPoints.addAll(scheme.points)
        onPointsChanged?.invoke(recordedPoints.size)
        Toast.makeText(this, "已加载方案: ${scheme.name}", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 打开设置页面
     */
    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_settings", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }
    
    companion object {
        private const val TAG = "FloatingWindowService"
        private const val CHANNEL_ID = "autoclicker_channel"
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
    }
}
