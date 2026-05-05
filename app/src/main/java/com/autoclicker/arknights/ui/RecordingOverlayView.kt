package com.autoclicker.arknights.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.autoclicker.arknights.R
import com.autoclicker.arknights.data.ClickPoint
import com.autoclicker.arknights.data.OperationType

/**
 * 录制模式的覆盖层视图 v1.2.0
 * 用于在全屏显示录制状态，并捕获用户点击的位置
 * 添加了停止按钮和撤销按钮
 */
class RecordingOverlayView(context: Context) : View(context) {
    
    private val backgroundPaint = Paint().apply {
        color = Color.argb(30, 0, 0, 0)
        style = Paint.Style.FILL
    }
    
    private val circlePaint = Paint().apply {
        color = Color.parseColor("#FF6B35")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val longPressPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val waitPaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    
    private val infoPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    
    private val typeLabelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 18f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    
    private val recordedPoints = mutableListOf<ClickPoint>()
    
    var onPointRecorded: ((ClickPoint) -> Unit)? = null
    var onUndoPoint: (() -> Unit)? = null
    var onFinishRecording: (() -> Unit)? = null
    
    // 默认参数
    var defaultLongPressDuration: Long = 500
    var defaultWaitDuration: Long = 1000
    
    private val handler = Handler(Looper.getMainLooper())
    private var instructionShown = true
    
    // 当前选择的操作类型
    private var currentOperationType = OperationType.CLICK
    
    // 停止按钮区域
    private val stopButtonPaint = Paint().apply {
        color = Color.parseColor("#E53935")  // 红色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val stopButtonPressedPaint = Paint().apply {
        color = Color.parseColor("#B71C1C")  // 深红色按下
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val stopTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    
    // 停止按钮尺寸和位置
    private val stopButtonWidth = 180f
    private val stopButtonHeight = 70f
    private var stopButtonLeft = 0f
    private var stopButtonTop = 0f
    private var stopButtonRight = 0f
    private var stopButtonBottom = 0f
    private var isStopButtonPressed = false
    
    // 撤销按钮区域
    private val undoButtonPaint = Paint().apply {
        color = Color.parseColor("#FF9800")  // 橙色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val undoButtonPressedPaint = Paint().apply {
        color = Color.parseColor("#E65100")  // 深橙色按下
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val undoTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    
    // 撤销按钮尺寸和位置
    private val undoButtonWidth = 120f
    private val undoButtonHeight = 60f
    private var undoButtonLeft = 0f
    private var undoButtonTop = 0f
    private var undoButtonRight = 0f
    private var undoButtonBottom = 0f
    private var isUndoButtonPressed = false
    
    init {
        // 设置为可以接收触摸事件
        isClickable = true
        isFocusable = true
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 计算停止按钮位置（右上角）
        stopButtonLeft = w - stopButtonWidth - 30f
        stopButtonTop = 30f
        stopButtonRight = w - 30f
        stopButtonBottom = stopButtonTop + stopButtonHeight
        
        // 计算撤销按钮位置（停止按钮下方）
        undoButtonLeft = w - undoButtonWidth - 30f
        undoButtonTop = stopButtonBottom + 15f
        undoButtonRight = w - 30f
        undoButtonBottom = undoButtonTop + undoButtonHeight
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // 绘制半透明背景
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        
        // 绘制所有已录制的点位
        for (point in recordedPoints) {
            val paint = when (point.type) {
                OperationType.CLICK -> circlePaint
                OperationType.LONG_PRESS -> longPressPaint
                OperationType.WAIT -> waitPaint
            }
            
            // 绘制圆形
            canvas.drawCircle(point.x, point.y, 40f, paint)
            
            // 绘制编号
            val textY = point.y + (textPaint.textSize / 3)
            canvas.drawText(point.order.toString(), point.x, textY, textPaint)
            
            // 绘制类型标签
            val typeLabel = when (point.type) {
                OperationType.CLICK -> ""
                OperationType.LONG_PRESS -> "长"
                OperationType.WAIT -> "等"
            }
            if (typeLabel.isNotEmpty()) {
                canvas.drawText(typeLabel, point.x, point.y + 60f, typeLabelPaint)
            }
        }
        
        // 绘制操作类型选择器
        drawOperationTypeSelector(canvas)
        
        // 绘制提示信息
        if (instructionShown) {
            val infoY = height / 2f
            infoPaint.alpha = 200
            canvas.drawText("录制模式", width / 2f, infoY - 60, infoPaint)
            
            infoPaint.textSize = 28f
            infoPaint.alpha = 150
            canvas.drawText("点击屏幕录制点位", width / 2f, infoY - 10, infoPaint)
            infoPaint.textSize = 28f
            canvas.drawText("点击红色按钮结束录制", width / 2f, infoY + 30, infoPaint)
            infoPaint.textSize = 40f
            infoPaint.alpha = 255
        }
        
        // 绘制底部提示
        if (recordedPoints.isNotEmpty()) {
            val bottomText = "已录制 ${recordedPoints.size} 个点位"
            infoPaint.textSize = 32f
            infoPaint.alpha = 200
            canvas.drawText(bottomText, width / 2f, height - 140f, infoPaint)
            
            infoPaint.textSize = 24f
            infoPaint.alpha = 150
            canvas.drawText("点击红色按钮结束录制", width / 2f, height - 100f, infoPaint)
            infoPaint.textSize = 40f
            infoPaint.alpha = 255
        }
        
        // 绘制停止按钮
        drawStopButton(canvas)
        
        // 绘制撤销按钮
        drawUndoButton(canvas)
    }
    
    /**
     * 绘制停止按钮
     */
    private fun drawStopButton(canvas: Canvas) {
        val rect = RectF(stopButtonLeft, stopButtonTop, stopButtonRight, stopButtonBottom)
        val paint = if (isStopButtonPressed) stopButtonPressedPaint else stopButtonPaint
        canvas.drawRoundRect(rect, 12f, 12f, paint)
        
        // 绘制按钮文字
        val textY = stopButtonTop + stopButtonHeight / 2 + stopTextPaint.textSize / 3
        canvas.drawText("停止录制", stopButtonLeft + stopButtonWidth / 2, textY, stopTextPaint)
    }
    
    /**
     * 绘制撤销按钮
     */
    private fun drawUndoButton(canvas: Canvas) {
        val rect = RectF(undoButtonLeft, undoButtonTop, undoButtonRight, undoButtonBottom)
        val paint = if (isUndoButtonPressed) undoButtonPressedPaint else undoButtonPaint
        canvas.drawRoundRect(rect, 12f, 12f, paint)
        
        // 绘制按钮文字
        val textY = undoButtonTop + undoButtonHeight / 2 + undoTextPaint.textSize / 3
        canvas.drawText("撤销", undoButtonLeft + undoButtonWidth / 2, textY, undoTextPaint)
    }
    
    /**
     * 绘制操作类型选择器
     */
    private fun drawOperationTypeSelector(canvas: Canvas) {
        val buttonWidth = 100f
        val buttonHeight = 50f
        val margin = 20f
        val startX = margin
        val startY = margin
        
        val types = listOf(
            OperationType.CLICK to "点击",
            OperationType.LONG_PRESS to "长按",
            OperationType.WAIT to "等待"
        )
        
        for ((index, pair) in types.withIndex()) {
            val (type, label) = pair
            val left = startX + index * (buttonWidth + margin)
            val top = startY
            val right = left + buttonWidth
            val bottom = top + buttonHeight
            
            val rect = RectF(left, top, right, bottom)
            
            // 背景颜色
            val bgPaint = Paint().apply {
                style = Paint.Style.FILL
                if (currentOperationType == type) {
                    color = when (type) {
                        OperationType.CLICK -> Color.parseColor("#FF6B35")
                        OperationType.LONG_PRESS -> Color.parseColor("#4CAF50")
                        OperationType.WAIT -> Color.parseColor("#2196F3")
                    }
                } else {
                    color = Color.argb(150, 100, 100, 100)
                }
                isAntiAlias = true
            }
            
            canvas.drawRoundRect(rect, 10f, 10f, bgPaint)
            
            // 文字
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 24f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            canvas.drawText(label, left + buttonWidth / 2, top + buttonHeight / 2 + 8, textPaint)
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 检查是否点击了停止按钮
                if (x >= stopButtonLeft && x <= stopButtonRight && y >= stopButtonTop && y <= stopButtonBottom) {
                    isStopButtonPressed = true
                    invalidate()
                    return true
                }
                
                // 检查是否点击了撤销按钮
                if (x >= undoButtonLeft && x <= undoButtonRight && y >= undoButtonTop && y <= undoButtonBottom) {
                    isUndoButtonPressed = true
                    invalidate()
                    return true
                }
                
                instructionShown = false
                
                // 检查是否点击了操作类型选择器
                val buttonWidth = 100f
                val buttonHeight = 50f
                val margin = 20f
                
                // 点击了选择器区域
                if (y >= margin && y <= margin + buttonHeight) {
                    for ((index, type) in OperationType.entries.withIndex()) {
                        val left = margin + index * (buttonWidth + margin)
                        val right = left + buttonWidth
                        if (x >= left && x <= right) {
                            currentOperationType = type
                            invalidate()
                            
                            // 如果点击的是等待类型，弹出对话框设置等待时间
                            if (type == OperationType.WAIT) {
                                showWaitDialog()
                            }
                            return true
                        }
                    }
                }
                
                // 普通点击录制点位（所有非按钮区域）
                when (currentOperationType) {
                    OperationType.CLICK -> {
                        val point = ClickPoint(
                            x = x,
                            y = y,
                            order = recordedPoints.size + 1,
                            type = OperationType.CLICK
                        )
                        recordedPoints.add(point)
                        onPointRecorded?.invoke(point)
                    }
                    OperationType.LONG_PRESS -> {
                        showLongPressDialog(x, y)
                    }
                    OperationType.WAIT -> {
                        // 等待类型不需要点击位置，点击时添加等待步骤
                        // 已在上方通过对话框处理
                    }
                }
                
                invalidate()
                return true
            }
            
            MotionEvent.ACTION_UP -> {
                // 处理停止按钮点击
                if (isStopButtonPressed) {
                    isStopButtonPressed = false
                    if (x >= stopButtonLeft && x <= stopButtonRight && y >= stopButtonTop && y <= stopButtonBottom) {
                        onFinishRecording?.invoke()
                    }
                    invalidate()
                    return true
                }
                
                // 处理撤销按钮点击
                if (isUndoButtonPressed) {
                    isUndoButtonPressed = false
                    if (x >= undoButtonLeft && x <= undoButtonRight && y >= undoButtonTop && y <= undoButtonBottom) {
                        if (recordedPoints.isNotEmpty()) {
                            recordedPoints.removeAt(recordedPoints.size - 1)
                            onUndoPoint?.invoke()
                            // 重新编号
                            recordedPoints.forEachIndexed { index, point ->
                                val newPoint = point.copy(order = index + 1)
                                recordedPoints[index] = newPoint
                            }
                            invalidate()
                        } else {
                            Toast.makeText(context, "没有可撤销的点位", Toast.LENGTH_SHORT).show()
                        }
                    }
                    invalidate()
                    return true
                }
            }
            
            MotionEvent.ACTION_CANCEL -> {
                isStopButtonPressed = false
                isUndoButtonPressed = false
                invalidate()
            }
        }
        
        return super.onTouchEvent(event)
    }
    
    /**
     * 显示长按设置对话框 - 通过Activity安全弹出
     */
    private fun showLongPressDialog(x: Float, y: Float) {
        val intent = Intent(context, ServiceDialogActivity::class.java).apply {
            putExtra(ServiceDialogActivity.EXTRA_DIALOG_TYPE, ServiceDialogActivity.TYPE_LONG_PRESS)
            putExtra(ServiceDialogActivity.EXTRA_POINT_X, x)
            putExtra(ServiceDialogActivity.EXTRA_POINT_Y, y)
            putExtra(ServiceDialogActivity.EXTRA_ORDER, recordedPoints.size + 1)
            putExtra(ServiceDialogActivity.EXTRA_DEFAULT_DURATION, defaultLongPressDuration)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
    
    /**
     * 显示等待设置对话框 - 通过Activity安全弹出
     */
    private fun showWaitDialog() {
        val intent = Intent(context, ServiceDialogActivity::class.java).apply {
            putExtra(ServiceDialogActivity.EXTRA_DIALOG_TYPE, ServiceDialogActivity.TYPE_WAIT)
            putExtra(ServiceDialogActivity.EXTRA_DEFAULT_DURATION, defaultWaitDuration)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
    
    /**
     * 清空所有录制的点位
     */
    fun clearPoints() {
        recordedPoints.clear()
        instructionShown = true
        currentOperationType = OperationType.CLICK
        invalidate()
    }
    
    /**
     * 获取已录制的点位
     */
    fun getRecordedPoints(): List<ClickPoint> = recordedPoints.toList()
    
    /**
     * 获取点位数量
     */
    fun getPointCount(): Int = recordedPoints.size
    
    /**
     * 设置点位列表
     */
    fun setPoints(points: List<ClickPoint>) {
        recordedPoints.clear()
        recordedPoints.addAll(points)
        invalidate()
    }
}
