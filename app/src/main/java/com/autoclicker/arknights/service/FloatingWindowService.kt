package com.autoclicker.arknights.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.autoclicker.arknights.R
import com.autoclicker.arknights.data.AppSettings
import com.autoclicker.arknights.data.ClickPoint
import com.autoclicker.arknights.data.ClickScheme
import android.accessibilityservice.GestureDescription
import com.autoclicker.arknights.data.OperationType
import com.autoclicker.arknights.data.SettingsManager
import com.autoclicker.arknights.ui.DraggableFrameLayout
import com.autoclicker.arknights.ui.MainActivity
import com.autoclicker.arknights.ui.PointListAdapter
import com.autoclicker.arknights.ui.RecordingOverlayView
import com.autoclicker.arknights.util.ClickUtils
import com.autoclicker.arknights.data.DailyRoutine
import com.autoclicker.arknights.data.DeviceConfig
import kotlin.random.Random

/**
 * 悬浮窗服务 v1.3.0
 * 提供悬浮窗UI和控制连点器的核心逻辑
 * 移除定时功能（Android 14闪退修复）
 */
class FloatingWindowService : Service() {
    
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var miniFloatView: View  // 最小化视图
    private var recordingMiniView: View? = null  // 录制缩小视图
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var miniLayoutParams: WindowManager.LayoutParams? = null
    private var recordingMiniParams: WindowManager.LayoutParams? = null  // 录制缩小布局参数
    private var recordingOverlay: RecordingOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayRestoreRunnable: Runnable? = null  // 用于取消pending的覆盖层恢复
    
    // 是否处于录制缩小模式
    private var isRecordingMinimized = false
    
    // 点击反馈视图
    private var clickFeedbackView: ClickFeedbackView? = null
    private var clickFeedbackParams: WindowManager.LayoutParams? = null
    
    // 点列表面板
    private var pointListPanel: View? = null
    private var pointListParams: WindowManager.LayoutParams? = null
    private var pointListAdapter: PointListAdapter? = null
    private var isPointListVisible = false
    private var savedScrollPosition = 0  // 保存列表滚动位置
    // 点位调整模式
    private var isAdjustingPoint = false
    private var adjustingPointPosition = -1
    private var adjustMarker: AdjustPointMarker? = null
    private var adjustMarkerParams: WindowManager.LayoutParams? = null
    private var pointMarker: View? = null  // 显示点位标记（不可拖动）
    private var pointMarkerParams: WindowManager.LayoutParams? = null
    private var pointsDotOverlay: View? = null  // 所有点位红点覆盖层
    private var pointsDotOverlayParams: WindowManager.LayoutParams? = null
    
    // ============ 测试模块相关 ============
    private var testModulePanel: View? = null  // 模块选择面板
    private var testModulePanelParams: WindowManager.LayoutParams? = null
    private var testStatusBar: View? = null  // 测试状态栏
    private var testStatusBarParams: WindowManager.LayoutParams? = null
    private var testClickMarker: View? = null  // 红色点击标记
    private var testClickMarkerParams: WindowManager.LayoutParams? = null
    private var dailyRoutine: DailyRoutine? = null  // 当前的测试运行实例
    private var isTestRunning = false  // 是否正在测试
    
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settingsManager: SettingsManager
    
    // 连点器状态
    private var isRecording = false
    private var isRunning = false
    private var isPaused = false
    private var isMinimized = false
    private var isMiniHidden = false  // 最小化悬浮窗是否被隐藏到边缘
    private val recordedPoints = mutableListOf<ClickPoint>()
    
    // 操作历史栈，用于撤回操作
    private sealed class PointAction {
        data class Added(val point: ClickPoint) : PointAction()
        data class Deleted(val point: ClickPoint, val position: Int) : PointAction()
        data class Modified(val oldPoint: ClickPoint, val position: Int) : PointAction()
    }
    private val actionHistory = mutableListOf<PointAction>()
    private var clickThread: Thread? = null
    
    // 设置
    private var settings: AppSettings = AppSettings()
    
    // 微停顿相关
    private var clickCountSinceLastPause = 0
    private var nextPauseAt = 0
    
    // 当前进度
    private var currentStep = 0
    private var totalSteps = 0
    private var totalCycles = 0
    
    // 回调接口
    var onStateChanged: ((State) -> Unit)? = null
    var onPointsChanged: ((Int) -> Unit)? = null
    
    /**
     * 同步录制overlay的点位数据，确保小红点等显示与recordedPoints一致
     */
    private fun syncOverlayPoints() {
        recordingOverlay?.setPoints(recordedPoints)
        updatePointsDotOverlay()
    }
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
        createCompletionNotificationChannel()
        try {
            createFloatingWindow()
            createMiniFloatingWindow()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create floating window", e)
            stopSelf()
            return
        }
        Log.d(TAG, "FloatingWindowService created")
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
            stopSelf()
        }
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopClicking()
        hideRecordingOverlay()
        hideRecordingMiniWindow()  // 清理录制缩小视图
        hideClickFeedback()
        hidePointListPanel()  // 清理点列表面板
        hideAdjustMarker()  // 清理调整标记
        hidePointMarker()   // 清理点位标记
        hidePointsDotOverlay()  // 清理红点覆盖层
        // 清理测试相关视图
        hideTestModulePanel()
        hideTestStatusBar()
        hideTestClickMarker()
        try {
            windowManager.removeView(floatingView)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing floating view", e)
        }
        try {
            if (isMinimized || isMiniHidden) {
                windowManager.removeView(miniFloatView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing mini floating view", e)
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
     * 发送任务完成通知
     */
    private fun sendCompletionNotification(cycleCount: Int) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, COMPLETION_CHANNEL_ID)
            .setContentTitle("连点器任务完成")
            .setContentText("共执行 ${cycleCount} 次循环")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        
        try {
            notificationManager.notify(COMPLETION_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending completion notification", e)
        }
    }
    
    /**
     * 创建完成通知渠道
     */
    private fun createCompletionNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                COMPLETION_CHANNEL_ID,
                "任务完成通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "连点器任务完成时的通知"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
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
        
        // 使用DraggableFrameLayout的拖动功能
        val draggableContainer = floatingView.findViewById<DraggableFrameLayout>(R.id.draggableContainer)
        draggableContainer.onDragStart = {
            // 拖动开始，不需要特殊处理
        }
        draggableContainer.onDrag = { dx, dy ->
            layoutParams.x += dx.toInt()
            layoutParams.y += dy.toInt()
            windowManager.updateViewLayout(floatingView, layoutParams)
        }
        draggableContainer.onDragEnd = {
            // 拖动结束
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
        
        // 测试按钮
        floatingView.findViewById<ImageButton>(R.id.btnTest)?.setOnClickListener {
            if (isTestRunning) {
                Toast.makeText(this, "测试运行中，请先停止", Toast.LENGTH_SHORT).show()
            } else {
                showTestModulePanel()
            }
        }
        
        // 最小化按钮
        floatingView.findViewById<ImageButton>(R.id.btnMinimize)?.setOnClickListener {
            minimizeWindow()
        }
        
        // 列表按钮
        floatingView.findViewById<ImageButton>(R.id.btnPointList)?.setOnClickListener {
            if (isPointListVisible) {
                hidePointListPanel()
            } else {
                showPointListPanel()
            }
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
        
        // 使用DraggableFrameLayout的拖动功能
        val draggableContainer = miniFloatView.findViewById<DraggableFrameLayout>(R.id.draggableContainer)
        draggableContainer.onDragStart = {
            // 拖动开始
        }
        draggableContainer.onDrag = { dx, dy ->
            miniLayoutParams?.x = (miniLayoutParams?.x ?: 0) + dx.toInt()
            miniLayoutParams?.y = (miniLayoutParams?.y ?: 0) + dy.toInt()
            miniLayoutParams?.let { windowManager.updateViewLayout(miniFloatView, it) }
        }
        draggableContainer.onDragEnd = {
            // 检查是否拖到边缘隐藏
            miniLayoutParams?.let { params ->
                val screenWidth = resources.displayMetrics.widthPixels
                val miniX = params.x
                if (miniX < 50 || miniX > screenWidth - 50) {
                    hideMiniWindow()
                }
            }
        }
        draggableContainer.onTap = {
            restoreWindow()
        }
    }
    
    /**
     * 隐藏最小化悬浮窗（拖到边缘时）
     */
    private fun hideMiniWindow() {
        try {
            windowManager.removeView(miniFloatView)
        } catch (e: Exception) {
            Log.w(TAG, "Mini view may already be removed", e)
        }
        isMiniHidden = true
    }
    
    /**
     * 如果最小化悬浮窗被隐藏则重新显示
     */
    fun showMiniWindowIfHidden() {
        if (isMiniHidden) {
            miniLayoutParams?.let { params ->
                params.x = 100  // 默认靠左位置
                try {
                    windowManager.addView(miniFloatView, params)
                    isMiniHidden = false
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing mini window", e)
                }
            }
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
            // 添加迷你悬浮窗到屏幕
            if (miniLayoutParams != null) {
                try {
                    windowManager.addView(miniFloatView, miniLayoutParams)
                } catch (e: Exception) {
                    // 可能已经添加过
                    Log.w(TAG, "Mini view may already be added", e)
                }
            }
            // 隐藏主悬浮窗
            floatingView.visibility = View.GONE
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
            // 如果最小化悬浮窗被隐藏到边缘，先重新显示
            if (isMiniHidden) {
                showMiniWindowIfHidden()
            }
            // 移除迷你悬浮窗
            try {
                windowManager.removeView(miniFloatView)
            } catch (e: Exception) {
                Log.w(TAG, "Mini view may already be removed", e)
            }
            // 显示主悬浮窗
            floatingView.visibility = View.VISIBLE
            isMinimized = false
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring window", e)
        }
    }
    
    /**
     * 切换录制状态
     * 【优化5】录制时悬浮窗缩小为只显示停止按钮的小条
     */
    fun toggleRecording() {
        isRecording = !isRecording
        if (isRecording) {
            // 检查无障碍服务
            if (AutoClickAccessibilityService.instance == null) {
                Toast.makeText(this, "无障碍服务未连接，录制时无法回放操作到游戏", Toast.LENGTH_LONG).show()
            }
            // 开始录制前先停止连点
            stopClicking()
            recordedPoints.clear()
            onRecordingPoint?.invoke(ClickPoint(0f, 0f, -1)) // 发送清空信号
            // 显示录制覆盖层
            showRecordingOverlay()
            // 【优化5】录制时缩小悬浮窗为只显示停止按钮
            showRecordingMiniWindow()
        } else {
            // 【优化5】停止录制时恢复正常悬浮窗
            hideRecordingMiniWindow()
            // 隐藏录制覆盖层
            hideRecordingOverlay()
        }
        updateButtonStates()
        onStateChanged?.invoke(if (isRecording) State.RECORDING else State.IDLE)
        onPointsChanged?.invoke(recordedPoints.size)
        syncOverlayPoints()
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
        syncOverlayPoints()
        }
    }
    
    /**
     * 撤销最后一次操作（删除/修改坐标/修改时长）
     */
    fun undoLastAction() {
        if (actionHistory.isEmpty()) {
            Toast.makeText(this, "没有可撤销的操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        when (val action = actionHistory.removeAt(actionHistory.size - 1)) {
            is PointAction.Deleted -> {
                // 恢复被删除的点位
                val insertPos = action.position.coerceAtMost(recordedPoints.size)
                recordedPoints.add(insertPos, action.point)
                // 重新编号
                for (i in recordedPoints.indices) {
                    recordedPoints[i] = recordedPoints[i].copy(order = i + 1)
                }
                onPointsChanged?.invoke(recordedPoints.size)
        syncOverlayPoints()
                pointListAdapter?.updatePoints(recordedPoints)
                Toast.makeText(this, "已恢复删除的点位 #${action.point.order}", Toast.LENGTH_SHORT).show()
            }
            is PointAction.Modified -> {
                // 恢复修改前的点位
                if (action.position in 0 until recordedPoints.size) {
                    recordedPoints[action.position] = action.oldPoint
                    onPointsChanged?.invoke(recordedPoints.size)
        syncOverlayPoints()
                    pointListAdapter?.updatePoints(recordedPoints)
                    Toast.makeText(this, "已恢复点位 #${action.oldPoint.order} 的修改", Toast.LENGTH_SHORT).show()
                }
            }
            is PointAction.Added -> {
                // 撤销添加的点位（录制时的撤回）
                if (recordedPoints.isNotEmpty()) {
                    recordedPoints.removeAt(recordedPoints.size - 1)
                    onPointsChanged?.invoke(recordedPoints.size)
        syncOverlayPoints()
                    pointListAdapter?.updatePoints(recordedPoints)
                    Toast.makeText(this, "已撤销添加的点位", Toast.LENGTH_SHORT).show()
                }
            }
        }
        recordingOverlay?.setPoints(recordedPoints)
        // 更新列表面板的空提示
        pointListPanel?.let { panel ->
            val tvEmpty = panel.findViewById<TextView>(R.id.tvPointListEmpty)
            val rvList = panel.findViewById<RecyclerView>(R.id.rvPointList)
            tvEmpty?.visibility = if (recordedPoints.isEmpty()) View.VISIBLE else View.GONE
            rvList?.visibility = if (recordedPoints.isEmpty()) View.GONE else View.VISIBLE
        }
    }
    
    /**
     * 撤销最后一个点位（录制时专用）
     */
    fun undoLastPoint() {
        if (recordedPoints.isNotEmpty()) {
            recordedPoints.removeAt(recordedPoints.size - 1)
            onPointsChanged?.invoke(recordedPoints.size)
        syncOverlayPoints()
            Toast.makeText(this, "已撤销最后一个点位", Toast.LENGTH_SHORT).show()
            
            // 更新录制覆盖层
            recordingOverlay?.setPoints(recordedPoints)
        } else {
            Toast.makeText(this, "没有可撤销的点位", Toast.LENGTH_SHORT).show()
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
        syncOverlayPoints()
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
        syncOverlayPoints()
        }
    }
    
    /**
     * 结束录制
     */
    fun finishRecording() {
        isRecording = false
        makeOverlayTouchable()
        hideRecordingOverlay()
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
            currentStep = 0
            totalSteps = recordedPoints.size
            totalCycles = 0
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
     * 更新进度显示
     */
    private fun updateProgressDisplay() {
        handler.post {
            try {
                val tvProgress = floatingView.findViewById<TextView>(R.id.tvProgress)
                if (isRunning && !isMinimized) {
                    tvProgress?.visibility = View.VISIBLE
                    tvProgress?.text = "${currentStep}/${totalSteps}"
                } else {
                    tvProgress?.visibility = View.GONE
                }
            } catch (e: Exception) {
                // View可能已被移除
            }
        }
    }
    
    /**
     * 显示点击反馈
     */
    private fun showClickFeedback(x: Float, y: Float) {
        handler.post {
            if (clickFeedbackView == null) {
                clickFeedbackView = ClickFeedbackView(this)
                clickFeedbackParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    },
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
                
                try {
                    windowManager.addView(clickFeedbackView, clickFeedbackParams)
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding click feedback view", e)
                }
            }
            
            clickFeedbackView?.showAt(x.toInt(), y.toInt())
        }
    }
    
    /**
     * 隐藏点击反馈
     */
    private fun hideClickFeedback() {
        handler.post {
            clickFeedbackView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing click feedback", e)
                }
            }
            clickFeedbackView = null
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
                val completedCycles = loopIndex
                handler.post {
                    Toast.makeText(this, "已完成 ${settings.loopCount} 次循环", Toast.LENGTH_SHORT).show()
                    sendCompletionNotification(completedCycles)
                    stopClicking()
                }
                break
            }
            
            for ((index, point) in recordedPoints.withIndex()) {
                if (!isRunning || isPaused) break
                
                // 更新当前步骤
                currentStep = index + 1
                updateProgressDisplay()
                
                // 处理不同操作类型
                when (point.type) {
                    OperationType.CLICK -> {
                        // 显示点击反馈
                        showClickFeedback(point.x, point.y)
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
                        showClickFeedback(point.x, point.y)
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
                    OperationType.SWIPE -> {
                        // 滑动
                        showClickFeedback(point.x, point.y)
                        ClickUtils.swipe(
                            service = service,
                            startX = point.x,
                            startY = point.y,
                            endX = point.endX,
                            endY = point.endY,
                            duration = point.duration
                        )
                        totalClicks++
                        clickCountSinceLastPause++
                    }
                    OperationType.LONG_PRESS_DRAG -> {
                        // 长按拖动
                        showClickFeedback(point.x, point.y)
                        ClickUtils.longPressDrag(
                            service = service,
                            startX = point.x,
                            startY = point.y,
                            endX = point.endX,
                            endY = point.endY,
                            holdDuration = point.duration
                        )
                        totalClicks++
                        clickCountSinceLastPause++
                    }
                    OperationType.WAIT_PIXEL, OperationType.MULTI_CLICK -> {
                        // 暂不支持，跳过
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
            totalCycles = loopIndex
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
        
        // 隐藏进度显示
        if (isPaused) {
            handler.post {
                try {
                    val tvProgress = floatingView.findViewById<TextView>(R.id.tvProgress)
                    tvProgress?.visibility = View.GONE
                } catch (e: Exception) {}
            }
        }
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
        currentStep = 0
        clickThread?.interrupt()
        clickThread = null
        
        // 隐藏进度显示
        handler.post {
            try {
                val tvProgress = floatingView.findViewById<TextView>(R.id.tvProgress)
                tvProgress?.visibility = View.GONE
            } catch (e: Exception) {}
        }
        
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
        val tvStartPauseLabel = floatingView.findViewById<TextView>(R.id.tvStartPauseLabel)
        val tvRecordLabel = floatingView.findViewById<TextView>(R.id.tvRecordLabel)
        
        handler.post {
            when {
                isRecording -> {
                    btnRecord?.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    btnRecord?.setColorFilter(getColor(R.color.status_recording))
                    btnRecord?.isEnabled = true
                    btnStartPause?.isEnabled = false
                    btnStop?.isEnabled = false
                    btnSettings?.isEnabled = false
                    tvStartPauseLabel?.text = "开始"
                    tvRecordLabel?.text = "停止"
                }
                isRunning -> {
                    btnRecord?.isEnabled = false
                    btnStartPause?.setImageResource(android.R.drawable.ic_media_pause)
                    btnStartPause?.isEnabled = true
                    btnStop?.isEnabled = true
                    btnSettings?.isEnabled = false
                    tvStartPauseLabel?.text = "暂停"
                }
                isPaused -> {
                    btnRecord?.isEnabled = false
                    btnStartPause?.setImageResource(android.R.drawable.ic_media_play)
                    btnStartPause?.isEnabled = true
                    btnStop?.isEnabled = true
                    btnSettings?.isEnabled = false
                    tvStartPauseLabel?.text = "继续"
                }
                else -> {
                    btnRecord?.isEnabled = true
                    btnRecord?.setImageResource(android.R.drawable.ic_menu_edit)
                    btnRecord?.setColorFilter(getColor(R.color.text_secondary))
                    btnStartPause?.setImageResource(android.R.drawable.ic_media_play)
                    btnStartPause?.isEnabled = recordedPoints.isNotEmpty()
                    btnStop?.isEnabled = false
                    btnSettings?.isEnabled = true
                    tvStartPauseLabel?.text = "开始"
                    tvRecordLabel?.text = "录制"
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
                recordedPoints.add(point)
                onPointsChanged?.invoke(recordedPoints.size)
        syncOverlayPoints()
            }
            onPlaybackNeeded = lambda@{ x, y, type, duration, endX, endY ->
                // WAIT 类型不需要回放，直接返回
                if (type == OperationType.WAIT) return@lambda
                
                // MIUI兼容方案：直接隐藏覆盖层→派发手势→重新显示覆盖层
                // FLAG_NOT_TOUCHABLE在MIUI上可能不生效，覆盖层仍然拦截触摸
                temporarilyHideOverlayForPlayback(x, y, type, duration, endX, endY)
            }
            onUndoPoint = {
                if (recordedPoints.isNotEmpty()) {
                    recordedPoints.removeAt(recordedPoints.size - 1)
                    onPointsChanged?.invoke(recordedPoints.size)
        syncOverlayPoints()
                }
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
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
     * 【优化5】显示录制缩小悬浮窗（只显示停止按钮）
     * 录制开始时调用，隐藏主悬浮窗，显示录制专用缩小视图
     */
    @SuppressLint("InflateParams")
    private fun showRecordingMiniWindow() {
        if (recordingMiniView != null) return
        
        val view = LayoutInflater.from(this).inflate(R.layout.layout_recording_mini, null)
        if (view == null) return
        recordingMiniView = view
        
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        recordingMiniParams = WindowManager.LayoutParams(
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
        
        // 使用DraggableFrameLayout的拖动功能
        val draggableContainer = view.findViewById<DraggableFrameLayout>(R.id.draggableContainer) ?: return
        draggableContainer.onDrag = { dx, dy ->
            recordingMiniParams?.x = (recordingMiniParams?.x ?: 0) + dx.toInt()
            recordingMiniParams?.y = (recordingMiniParams?.y ?: 0) + dy.toInt()
            recordingMiniParams?.let { windowManager.updateViewLayout(view, it) }
        }
        
        // 停止按钮点击事件
        view.findViewById<ImageButton>(R.id.btnStopRecording)?.setOnClickListener {
            // 停止录制
            toggleRecording()
        }
        
        // 隐藏主悬浮窗
        floatingView.visibility = View.GONE
        isRecordingMinimized = true
        
        // 显示录制缩小视图
        try {
            windowManager.addView(view, recordingMiniParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing recording mini window", e)
        }
    }
    
    /**
     * 【优化5】隐藏录制缩小悬浮窗，恢复主悬浮窗
     * 录制结束时调用
     */
    private fun hideRecordingMiniWindow() {
        recordingMiniView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Recording mini view may already be removed", e)
            }
            recordingMiniView = null
        }
        recordingMiniParams = null
        isRecordingMinimized = false
        
        // 恢复主悬浮窗
        floatingView.visibility = View.VISIBLE
    }
    
    /**
     * MIUI兼容方案：临时隐藏覆盖层来派发手势
     * 在MIUI上FLAG_NOT_TOUCHABLE可能不生效，覆盖层仍然会拦截触摸
     * 所以改为：removeView → dispatchGesture → 重新addView
     */
    private fun temporarilyHideOverlayForPlayback(x: Float, y: Float, type: OperationType, duration: Long, endX: Float, endY: Float) {
        val overlay = recordingOverlay ?: return
        val params = overlayParams ?: return
        
        // 1. 先从WindowManager移除覆盖层
        try {
            windowManager.removeView(overlay)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay for playback", e)
            // 移除失败，尝试FLAG_NOT_TOUCHABLE作为备选
            makeOverlayNotTouchable()
        }
        
        // 2. 延迟派发手势（等覆盖层完全移除）
        val gestureDelay = 50L
        handler.postDelayed({
            val service = AutoClickAccessibilityService.instance
            if (service != null) {
                when (type) {
                    OperationType.CLICK -> ClickUtils.click(service, x, y, duration = 1)
                    OperationType.LONG_PRESS -> ClickUtils.longPress(service, x, y, duration = duration)
                    OperationType.SWIPE -> ClickUtils.swipe(service, x, y, endX, endY, duration = duration)
                    OperationType.LONG_PRESS_DRAG -> ClickUtils.longPressDrag(service, x, y, endX, endY, holdDuration = duration)
                    OperationType.WAIT -> { }
                    OperationType.WAIT_PIXEL, OperationType.MULTI_CLICK -> { }
                }
            } else {
                // fallback到input命令
                when (type) {
                    OperationType.CLICK -> ClickUtils.clickByInput(x, y)
                    OperationType.SWIPE -> ClickUtils.swipeByInput(x, y, endX, endY, duration)
                    else -> {}
                }
            }
            
            // 3. 手势执行后延迟重新添加覆盖层
            val restoreDelay = when (type) {
                OperationType.CLICK -> 50L
                OperationType.LONG_PRESS -> duration + 50L
                OperationType.SWIPE -> duration + 50L
                OperationType.LONG_PRESS_DRAG -> duration + 100L
                OperationType.WAIT -> 0L
                OperationType.WAIT_PIXEL, OperationType.MULTI_CLICK -> 50L
            }
            handler.postDelayed({
                // 重新添加覆盖层
                if (isRecording && recordingOverlay != null) {
                    try {
                        windowManager.addView(recordingOverlay, overlayParams)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error re-adding overlay after playback", e)
                    }
                }
            }, restoreDelay)
        }, 50L)
    }
    
    /**
     * 让录制覆盖层暂时不可触摸（手势穿透到下层）
     * 用于回放时避免覆盖层捕获模拟的触摸事件
     */
    private fun makeOverlayNotTouchable() {
        // 取消待执行的恢复操作，防止竞争条件
        overlayRestoreRunnable?.let { handler.removeCallbacks(it) }
        overlayRestoreRunnable = null
        
        recordingOverlay?.let {
            if (!it.isAttachedToWindow) return
            try {
                val params = overlayParams?.apply {
                    flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } ?: return
                overlayParams = params
                windowManager.updateViewLayout(it, params)
            } catch (e: Exception) {
                Log.e(TAG, "Error making overlay not touchable", e)
            }
        }
    }
    
    /**
     * 恢复录制覆盖层为可触摸
     */
    private fun makeOverlayTouchable() {
        overlayRestoreRunnable = null
        recordingOverlay?.let {
            if (!it.isAttachedToWindow) return
            try {
                val params = overlayParams?.apply {
                    flags = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                } ?: return
                overlayParams = params
                windowManager.updateViewLayout(it, params)
            } catch (e: Exception) {
                Log.e(TAG, "Error making overlay touchable", e)
            }
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
        syncOverlayPoints()
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
        private const val COMPLETION_CHANNEL_ID = "completion_channel"
        private const val NOTIFICATION_ID = 1
        private const val COMPLETION_NOTIFICATION_ID = 2
        
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
    
    /**
     * 显示点列表面板
     */
    @SuppressLint("InflateParams")
    fun showPointListPanel() {
        if (pointListPanel != null) return
        
        try {
            // Service没有Activity主题，用ContextThemeWrapper包装以确保?attr/可解析
            val ctx = android.view.ContextThemeWrapper(this, R.style.Theme_ArknightsAutoClicker)
            val panelView = LayoutInflater.from(ctx).inflate(R.layout.layout_point_list_panel, null) ?: return
            
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            pointListParams = WindowManager.LayoutParams(
                280 * resources.displayMetrics.density.toInt(),
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.END or Gravity.TOP
                x = 0
                y = 0
            }
            
            // 设置适配器
            pointListAdapter = PointListAdapter(
                points = recordedPoints,
                onItemClick = { position: Int, point: ClickPoint ->
                    // 点击item切换选中：选中显示绿点，取消选中隐藏绿点
                    if (position >= 0) {
                        showPointMarker(point)
                    } else {
                        hidePointMarker()
                    }
                },
                onShowClick = { position: Int, point: ClickPoint ->
                    // 切换高亮点位显示：在屏幕上显示/隐藏绿点标记
                    pointListAdapter?.highlightedPosition = position
                    pointListAdapter?.notifyDataSetChanged()
                    showPointMarker(point)
                },
                onEditClick = { position: Int, point: ClickPoint ->
                    // 进入调整模式
                    hidePointListPanel()
                    showAdjustMarker(position, point)
                },
                onDeleteClick = { position: Int ->
                    // 删除点位
                    if (position in 0 until recordedPoints.size) {
                        val deletedPoint = recordedPoints[position]
                        actionHistory.add(PointAction.Deleted(deletedPoint, position))
                        recordedPoints.removeAt(position)
                        // 重新编号，使用copy()创建新实例
                        for (i in recordedPoints.indices) {
                            recordedPoints[i] = recordedPoints[i].copy(order = i + 1)
                        }
                        pointListAdapter?.removePoint(position)
                        hidePointMarker()
                        onPointsChanged?.invoke(recordedPoints.size)
        syncOverlayPoints()
                    }
                },
                onWaitDurationChange = { position: Int, point: ClickPoint, newDuration: Long ->
                    // 修改等待时长
                    if (position in 0 until recordedPoints.size) {
                        actionHistory.add(PointAction.Modified(recordedPoints[position], position))
                        recordedPoints[position] = point.copy(duration = newDuration)
                        pointListAdapter?.updatePointDuration(position, newDuration)
                    }
                }
            )
            
            val rvPointList = panelView.findViewById<RecyclerView>(R.id.rvPointList) ?: return
            rvPointList.layoutManager = LinearLayoutManager(this)
            rvPointList.adapter = pointListAdapter
            
            // 恢复上次滚动位置
            if (savedScrollPosition > 0) {
                (rvPointList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(savedScrollPosition, 0)
            }
            
            // 空提示
            val tvEmpty = panelView.findViewById<TextView>(R.id.tvPointListEmpty)
            tvEmpty?.visibility = if (recordedPoints.isEmpty()) View.VISIBLE else View.GONE
            rvPointList.visibility = if (recordedPoints.isEmpty()) View.GONE else View.VISIBLE
            
            // 关闭按钮
            panelView.findViewById<ImageButton>(R.id.btnClosePointList)?.setOnClickListener {
                hidePointListPanel()
            }
            
            // 撤回按钮
            panelView.findViewById<ImageButton>(R.id.btnUndoPoint)?.setOnClickListener {
                undoLastAction()
            }
            
            // 设置DraggableFrameLayout拖动支持
            val draggablePanel = panelView.findViewById<DraggableFrameLayout>(R.id.pointListPanel)
            draggablePanel?.onDrag = { dx, dy ->
                pointListParams?.let { params ->
                    params.x -= dx.toInt()
                    params.y += dy.toInt()
                    windowManager.updateViewLayout(panelView, params)
                }
            }
            
            windowManager.addView(panelView, pointListParams)
            pointListPanel = panelView
            isPointListVisible = true
            showPointsDotOverlay()  // 显示所有红点
        } catch (e: Exception) {
            Log.e(TAG, "Error showing point list panel", e)
        }
    }
    
    /**
     * 隐藏点列表面板
     */
    fun hidePointListPanel() {
        // 保存滚动位置
        pointListPanel?.let {
            val rvList = it.findViewById<RecyclerView>(R.id.rvPointList)
            savedScrollPosition = (rvList?.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing point list panel", e)
            }
        }
        pointListPanel = null
        pointListParams = null
        isPointListVisible = false
        // 关闭面板时也隐藏点位标记和红点覆盖层
        hidePointMarker()
        hidePointsDotOverlay()
        // 重置选中状态
        pointListAdapter?.highlightedPosition = -1
    }
    
    /**
     * 在屏幕上显示点位绿点标记（不可拖动）
     * 再次点击同一个点位则隐藏
     */
    private fun showPointMarker(point: ClickPoint) {
        if (point.x <= 0 && point.y <= 0) {
            hidePointMarker()
            return
        }
        
        // 如果已经有标记在显示同一个位置，则隐藏
        if (pointMarker != null) {
            hidePointMarker()
            return
        }
        
        val marker = AdjustPointMarker(this).apply {
            setPoint(point)
        }
        
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        pointMarkerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = point.x.toInt() - 25
            y = point.y.toInt() - 25
        }
        
        try {
            windowManager.addView(marker, pointMarkerParams)
            pointMarker = marker
        } catch (e: Exception) {
            Log.e(TAG, "Error showing point marker", e)
        }
    }
    
    /**
     * 隐藏点位标记
     */
    private fun hidePointMarker() {
        pointMarker?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
        }
        pointMarker = null
        pointMarkerParams = null
    }
    
    /**
     * 显示点位调整标记
     * 拖动绿点到新位置后点确认按钮
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showAdjustMarker(position: Int, point: ClickPoint) {
        if (point.x <= 0 && point.y <= 0) {
            Toast.makeText(this, "该点位无法调整", Toast.LENGTH_SHORT).show()
            return
        }
        
        isAdjustingPoint = true
        adjustingPointPosition = position
        
        adjustMarker = AdjustPointMarker(this).apply {
            setPoint(point)
        }
        
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        adjustMarkerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = point.x.toInt() - 25
            y = point.y.toInt() - 25
        }
        
        // 触摸拖动，松手不确认，等点确认按钮
        adjustMarker?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    adjustMarkerParams?.let { params ->
                        params.x = event.rawX.toInt() - 25
                        params.y = event.rawY.toInt() - 25
                        try {
                            windowManager.updateViewLayout(adjustMarker, params)
                        } catch (e: Exception) {}
                    }
                }
            }
            true
        }
        
        try {
            windowManager.addView(adjustMarker, adjustMarkerParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing adjust marker", e)
            isAdjustingPoint = false
            return
        }
        
        // 显示确认按钮
        showAdjustConfirmButton(windowType)
        
        Toast.makeText(this, "拖动绿点到新位置后点确定", Toast.LENGTH_LONG).show()
    }
    
    /**
     * 显示调整确认按钮
     */
    private var adjustConfirmView: View? = null
    private var adjustConfirmParams: WindowManager.LayoutParams? = null
    
    @SuppressLint("ClickableViewAccessibility")
    private fun showAdjustConfirmButton(windowType: Int) {
        // 创建确认按钮布局
        val btnLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.argb(200, 76, 175, 80))
            setPadding(24, 16, 24, 16)
        }
        
        val confirmBtn = android.widget.TextView(this).apply {
            text = "✓ 确定"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setPadding(16, 12, 16, 12)
            setBackgroundColor(android.graphics.Color.argb(180, 56, 142, 60))
        }
        
        val cancelBtn = android.widget.TextView(this).apply {
            text = "✕ 取消"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setPadding(16, 8, 16, 8)
            setBackgroundColor(android.graphics.Color.argb(120, 158, 158, 158))
        }
        
        btnLayout.addView(confirmBtn)
        btnLayout.addView(cancelBtn)
        
        adjustConfirmParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 200
        }
        
        confirmBtn.setOnClickListener {
            // 确认：读取绿点当前位置，更新点位坐标
            adjustMarkerParams?.let { params ->
                val newX = params.x + 25f
                val newY = params.y + 25f
                if (adjustingPointPosition in 0 until recordedPoints.size) {
                    val oldPoint = recordedPoints[adjustingPointPosition]
                    actionHistory.add(PointAction.Modified(oldPoint, adjustingPointPosition))
                    recordedPoints[adjustingPointPosition] = recordedPoints[adjustingPointPosition].copy(x = newX, y = newY)
                    onPointsChanged?.invoke(recordedPoints.size)
        syncOverlayPoints()
                    Toast.makeText(this@FloatingWindowService, "坐标已更新", Toast.LENGTH_SHORT).show()
                }
            }
            hideAdjustMarker()
        }
        
        cancelBtn.setOnClickListener {
            hideAdjustMarker()
        }
        
        try {
            windowManager.addView(btnLayout, adjustConfirmParams)
            adjustConfirmView = btnLayout
        } catch (e: Exception) {
            Log.e(TAG, "Error showing confirm button", e)
        }
    }
    
    private fun hideAdjustConfirmButton() {
        adjustConfirmView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
        }
        adjustConfirmView = null
        adjustConfirmParams = null
    }
    
    /**
     * 隐藏点位调整标记
     */
    private fun hideAdjustMarker() {
        adjustMarker?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
        }
        adjustMarker = null
        adjustMarkerParams = null
        isAdjustingPoint = false
        adjustingPointPosition = -1
        hideAdjustConfirmButton()
    }
    
    /**
     * 所有点位红点覆盖层
     * 全屏透明View，在所有有坐标的点位中心画小红点
     */
    class PointsDotOverlay(context: Context) : View(context) {
        
        private val redDotPaint = Paint().apply {
            color = Color.argb(200, 244, 67, 54)  // 半透明红色
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        private var points: List<ClickPoint> = emptyList()
        
        fun setPoints(points: List<ClickPoint>) {
            this.points = points
            invalidate()
        }
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (point in points) {
                if (point.x > 0 && point.y > 0) {
                    when (point.type) {
                        OperationType.SWIPE, OperationType.LONG_PRESS_DRAG -> {
                            canvas.drawCircle(point.x, point.y, 6f, redDotPaint)
                            if (point.endX > 0 && point.endY > 0) {
                                canvas.drawCircle(point.endX, point.endY, 6f, redDotPaint)
                            }
                        }
                        else -> {
                            canvas.drawCircle(point.x, point.y, 6f, redDotPaint)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 显示所有点位红点覆盖层
     */
    private fun showPointsDotOverlay() {
        if (pointsDotOverlay != null) {
            // 已存在则更新
            (pointsDotOverlay as? PointsDotOverlay)?.setPoints(recordedPoints)
            return
        }
        
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        val overlay = PointsDotOverlay(this)
        overlay.setPoints(recordedPoints)
        
        pointsDotOverlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        
        try {
            windowManager.addView(overlay, pointsDotOverlayParams)
            pointsDotOverlay = overlay
        } catch (e: Exception) {
            Log.e(TAG, "Error showing points dot overlay", e)
        }
    }
    
    /**
     * 更新红点覆盖层数据
     */
    private fun updatePointsDotOverlay() {
        if (pointsDotOverlay != null) {
            (pointsDotOverlay as? PointsDotOverlay)?.setPoints(recordedPoints)
        }
    }
    
    /**
     * 隐藏所有点位红点覆盖层
     */
    private fun hidePointsDotOverlay() {
        pointsDotOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
        }
        pointsDotOverlay = null
        pointsDotOverlayParams = null
    }
    
    /**
     * 点位调整标记视图
     * 显示可拖动的红点标记
     */
    class AdjustPointMarker(context: Context) : View(context) {
        
        private val fillPaint = Paint().apply {
            color = Color.argb(80, 76, 175, 80)  // 半透明淡绿色，和录制点位一致
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        private val strokePaint = Paint().apply {
            color = Color.argb(180, 76, 175, 80)  // 绿色描边
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        
        private var pointOrder: Int = 0
        private val radius = 25f
        
        fun setPoint(point: ClickPoint) {
            pointOrder = point.order
            invalidate()
        }
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = radius
            val cy = radius
            
            // 绘制红点
            canvas.drawCircle(cx, cy, radius, fillPaint)
            canvas.drawCircle(cx, cy, radius, strokePaint)
            
            // 绘制序号
            val textY = cy + 8f
            canvas.drawText(pointOrder.toString(), cx, textY, textPaint)
        }
        
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension((radius * 2).toInt(), (radius * 2).toInt())
        }
    }
    // 测试模块功能
    // ============================================================================

    /**
     * 显示测试模块选择面板
     * 居中显示，宽度约300dp，可拖动
     */
    @SuppressLint("InflateParams")
    fun showTestModulePanel() {
        if (testModulePanel != null) return
        
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        // 创建面板容器
        val panelContainer = DraggableFrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.argb(240, 50, 50, 50))
            setPadding(16, 16, 16, 16)
        }
        
        // 创建内容布局
        val contentLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        
        // 标题栏
        val titleBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }
        
        val titleText = android.widget.TextView(this).apply {
            text = "选择测试模块"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val closeBtn = android.widget.TextView(this).apply {
            text = "✕"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            setPadding(16, 8, 8, 8)
        }
        closeBtn.setOnClickListener { hideTestModulePanel() }
        
        titleBar.addView(titleText)
        titleBar.addView(closeBtn)
        contentLayout.addView(titleBar)
        
        // 模块按钮列表
        val modules = listOf(
            "进游戏" to DailyRoutine.TestModule.ENTER_GAME,
            "关弹窗" to DailyRoutine.TestModule.CLEAR_POPUPS,
            "清基建" to DailyRoutine.TestModule.BASE_COLLECT,
            "好友线索交流" to DailyRoutine.TestModule.FRIEND_VISIT,
            "公招" to DailyRoutine.TestModule.RECRUIT,
            "信用商店" to DailyRoutine.TestModule.CREDIT_SHOP,
            "刷1-7" to DailyRoutine.TestModule.BATTLE_1_7,
            "清任务" to DailyRoutine.TestModule.MISSION
        )
        
        for ((name, module) in modules) {
            val btn = android.widget.TextView(this).apply {
                text = name
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                setPadding(16, 14, 16, 14)
                setBackgroundColor(android.graphics.Color.argb(180, 76, 175, 80))
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 8
                }
            }
            btn.setOnClickListener {
                hideTestModulePanel()
                startTestModule(module)
            }
            contentLayout.addView(btn)
        }
        
        panelContainer.addView(contentLayout)
        
        // 设置拖动支持
        panelContainer.onDrag = { dx, dy ->
            testModulePanelParams?.let { params ->
                params.x += dx.toInt()
                params.y += dy.toInt()
                windowManager.updateViewLayout(panelContainer, params)
            }
        }
        
        // 计算位置（居中）
        val displayMetrics = resources.displayMetrics
        val panelWidth = (300 * displayMetrics.density).toInt()
        val panelHeight = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        testModulePanelParams = WindowManager.LayoutParams(
            panelWidth,
            panelHeight,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = -screenHeight / 4  // 稍微偏上
        }
        
        try {
            windowManager.addView(panelContainer, testModulePanelParams)
            testModulePanel = panelContainer
        } catch (e: Exception) {
            Log.e(TAG, "Error showing test module panel", e)
        }
    }

    /**
     * 隐藏测试模块选择面板
     */
    fun hideTestModulePanel() {
        testModulePanel?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing test module panel", e)
            }
        }
        testModulePanel = null
        testModulePanelParams = null
    }

    /**
     * 启动测试模块
     */
    private fun startTestModule(module: DailyRoutine.TestModule) {
        val service = AutoClickAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "无障碍服务未启动", Toast.LENGTH_SHORT).show()
            return
        }
        
        isTestRunning = true
        
        // 获取屏幕尺寸
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // 创建DailyRoutine实例
        dailyRoutine = DailyRoutine(service, screenWidth, screenHeight)
        
        // 设置回调
        dailyRoutine?.onAction = { action ->
            handler.post {
                when (action) {
                    is DailyRoutine.TestAction.Click -> {
                        // 更新状态栏显示
                        updateTestStatusBar(action.x, action.y, null)
                        // 显示红色点击标记
                        showTestClickMarker(action.x, action.y)
                    }
                    is DailyRoutine.TestAction.Wait -> {
                        updateTestStatusBar(null, null, "等待 ${action.seconds}秒")
                    }
                    is DailyRoutine.TestAction.Recognize -> {
                        updateTestStatusBar(null, null, "识别: ${action.stateName}")
                    }
                    is DailyRoutine.TestAction.StateChanged -> {
                        updateTestStatusBar(null, null, null, action.state.name)
                    }
                    is DailyRoutine.TestAction.ModuleDone -> {
                        Toast.makeText(this, "模块完成: ${action.module}", Toast.LENGTH_SHORT).show()
                        hideTestStatusBar()
                        hideTestClickMarker()
                        isTestRunning = false
                        dailyRoutine = null
                    }
                    is DailyRoutine.TestAction.Error -> {
                        Toast.makeText(this, "错误: ${action.msg}", Toast.LENGTH_SHORT).show()
                        hideTestStatusBar()
                        hideTestClickMarker()
                        isTestRunning = false
                        dailyRoutine = null
                    }
                }
            }
        }
        
        dailyRoutine?.onDone = {
            handler.post {
                hideTestStatusBar()
                hideTestClickMarker()
                isTestRunning = false
                dailyRoutine = null
            }
        }
        
        // 显示测试状态栏
        showTestStatusBar(module.name)
        
        // 启动模块
        dailyRoutine?.startModule(module)
    }

    /**
     * 显示测试状态栏
     * 位置: 屏幕顶部
     * 内容: 左侧行为、中间状态、右侧"正在测试"+停止按钮
     */
    @SuppressLint("InflateParams")
    private fun showTestStatusBar(moduleName: String) {
        if (testStatusBar != null) return
        
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        
        // 创建状态栏布局
        val statusBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.argb(200, 30, 30, 30))
            setPadding(16, 12, 16, 12)
        }
        
        // 左侧行为文字
        val actionText = android.widget.TextView(this).apply {
            id = android.R.id.text1
            text = "准备中..."
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        // 中间状态文字
        val stateText = android.widget.TextView(this).apply {
            id = android.R.id.text2
            text = moduleName
            setTextColor(android.graphics.Color.argb(200, 200, 200, 200))
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        // 右侧"正在测试"+停止按钮
        val rightContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val testingText = android.widget.TextView(this).apply {
            text = "正在测试"
            setTextColor(android.graphics.Color.argb(200, 200, 200, 200))
            textSize = 12f
        }
        
        val stopBtn = android.widget.TextView(this).apply {
            text = "■ 停止"
            setTextColor(android.graphics.Color.RED)
            textSize = 14f
            setPadding(16, 4, 8, 4)
            setBackgroundColor(android.graphics.Color.argb(100, 255, 100, 100))
        }
        stopBtn.setOnClickListener {
            dailyRoutine?.stop()
            hideTestStatusBar()
            hideTestClickMarker()
            isTestRunning = false
            dailyRoutine = null
        }
        
        rightContainer.addView(testingText)
        rightContainer.addView(stopBtn)
        
        statusBar.addView(actionText)
        statusBar.addView(stateText)
        statusBar.addView(rightContainer)
        
        testStatusBarParams = WindowManager.LayoutParams(
            screenWidth,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        
        try {
            windowManager.addView(statusBar, testStatusBarParams)
            testStatusBar = statusBar
        } catch (e: Exception) {
            Log.e(TAG, "Error showing test status bar", e)
        }
    }

    /**
     * 更新测试状态栏
     */
    private fun updateTestStatusBar(
        clickX: Int?,
        clickY: Int?,
        actionText: String?,
        stateName: String? = null
    ) {
        testStatusBar?.let { bar ->
            val actionTv = bar.findViewById<TextView>(android.R.id.text1)
            val stateTv = bar.findViewById<TextView>(android.R.id.text2)
            
            when {
                clickX != null && clickY != null -> {
                    actionTv?.text = "点击($clickX, $clickY)"
                }
                actionText != null -> {
                    actionTv?.text = actionText
                }
            }
            
            if (stateName != null) {
                stateTv?.text = stateName
            }
        }
    }

    /**
     * 隐藏测试状态栏
     */
    private fun hideTestStatusBar() {
        testStatusBar?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing test status bar", e)
            }
        }
        testStatusBar = null
        testStatusBarParams = null
    }

    /**
     * 红色点击标记视图
     * 在点击位置显示红色圆圈
     */

    /**
     * 显示红色点击标记
     */
    @SuppressLint("InflateParams")
    private fun showTestClickMarker(x: Int, y: Int) {
        // 每次点击都创建新的标记视图
        hideTestClickMarker()
        
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        val marker = TestClickMarkerView(this)
        
        testClickMarkerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        
        try {
            windowManager.addView(marker, testClickMarkerParams)
            testClickMarker = marker
            marker.showAt(x, y, windowManager, testClickMarkerParams!!)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing test click marker", e)
        }
    }

    /**
     * 隐藏红色点击标记
     */
    private fun hideTestClickMarker() {
        testClickMarker?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing test click marker", e)
            }
        }
        testClickMarker = null
        testClickMarkerParams = null
    }
}


/**
 * 点击反馈视图
 * 在点击位置显示绿色圆圈
 */
class ClickFeedbackView(context: Context) : View(context) {
    
    private val circlePaint = Paint().apply {
        color = Color.parseColor("#4CAF50")  // 绿色
        style = Paint.Style.FILL
        isAntiAlias = true
        alpha = 200
    }
    
    private val strokePaint = Paint().apply {
        color = Color.parseColor("#2E7D32")  // 深绿色边框
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    
    private val circleRadius = 30f
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 绘制圆圈
        canvas.drawCircle(width / 2f, height / 2f, circleRadius, circlePaint)
        canvas.drawCircle(width / 2f, height / 2f, circleRadius, strokePaint)
    }
    
    fun showAt(x: Int, y: Int) {
        // 取消之前的隐藏任务
        hideRunnable?.let { handler.removeCallbacks(it) }
        
        // 设置位置
        val params = layoutParams as? WindowManager.LayoutParams
        params?.let {
            it.x = x - 30
            it.y = y - 30
            it.width = 60
            it.height = 60
            if (context is FloatingWindowService) {
                try {
                    (context as FloatingWindowService).let { service ->
                        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        wm.updateViewLayout(this, it)
                    }
                } catch (e: Exception) {}
            }
        }
        
        // 3秒后自动隐藏
        val runnable = Runnable { hide() }
        hideRunnable = runnable
        handler.postDelayed(runnable, 300)
        
        invalidate()
    }
    
    private fun hide() {
        try {
            if (context is FloatingWindowService) {
                val service = context as FloatingWindowService
                val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(this)
            }
        } catch (e: Exception) {}
    }
}

class TestClickMarkerView(context: Context) : View(context) {
    
    private val circlePaint = Paint().apply {
        color = Color.parseColor("#F44336")  // 红色
        style = Paint.Style.FILL
        isAntiAlias = true
        alpha = 200
    }
    
    private val strokePaint = Paint().apply {
        color = Color.parseColor("#B71C1C")  // 深红色边框
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    
    private val circleRadius = 20f  // 40px直径
    private val markerSize = (circleRadius * 2 + 8).toInt()  // 固定尺寸，48px
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 绘制圆圈
        canvas.drawCircle(width / 2f, height / 2f, circleRadius, circlePaint)
        canvas.drawCircle(width / 2f, height / 2f, circleRadius, strokePaint)
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(markerSize, markerSize)
    }
    
    fun showAt(x: Int, y: Int, wm: WindowManager, params: WindowManager.LayoutParams) {
        // 取消之前的隐藏任务
        hideRunnable?.let { handler.removeCallbacks(it) }
        
        // 设置位置（用固定尺寸居中偏移）
        params.x = x - markerSize / 2
        params.y = y - markerSize / 2
        
        try {
            wm.updateViewLayout(this, params)
        } catch (e: Exception) {
            Log.e("TestClickMarker", "Error updating layout", e)
        }
        
        // 500ms后自动隐藏
        val runnable = Runnable { hide(wm) }
        hideRunnable = runnable
        handler.postDelayed(runnable, 500)
        
        invalidate()
    }
    
    private fun hide(wm: WindowManager) {
        try {
            wm.removeView(this)
        } catch (e: Exception) {
            Log.w("TestClickMarker", "Error removing view", e)
        }
    }
}

