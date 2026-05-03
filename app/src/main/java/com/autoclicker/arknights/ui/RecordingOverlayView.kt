package com.autoclicker.arknights.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import com.autoclicker.arknights.R
import com.autoclicker.arknights.data.ClickPoint
import com.autoclicker.arknights.data.OperationType

/**
 * 录制模式的覆盖层视图 v1.1.0
 * 用于在全屏显示录制状态，并捕获用户点击的位置
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
    var onFinishRecording: (() -> Unit)? = null
    
    // 默认参数
    var defaultLongPressDuration: Long = 500
    var defaultWaitDuration: Long = 1000
    
    private val handler = Handler(Looper.getMainLooper())
    private var instructionShown = true
    
    // 当前选择的操作类型
    private var currentOperationType = OperationType.CLICK
    
    init {
        // 设置为可以接收触摸事件
        isClickable = true
        isFocusable = true
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
            canvas.drawText("长按悬浮窗结束录制", width / 2f, infoY + 30, infoPaint)
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
            canvas.drawText("再次点击悬浮窗结束录制", width / 2f, height - 100f, infoPaint)
            infoPaint.textSize = 40f
            infoPaint.alpha = 255
        }
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
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                instructionShown = false
                
                val x = event.x
                val y = event.y
                
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
                
                // 普通点击录制点位
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
        }
        return super.onTouchEvent(event)
    }
    
    /**
     * 显示长按设置对话框
     */
    private fun showLongPressDialog(x: Float, y: Float) {
        val dialogView = android.view.LayoutInflater.from(context)
            .inflate(R.layout.dialog_long_press, null)
        
        val seekBar = dialogView.findViewById<SeekBar>(R.id.seekLongPressDuration)
        val tvDuration = dialogView.findViewById<TextView>(R.id.tvLongPressDuration)
        
        // 设置初始值（厘秒为单位，显示为毫秒）
        seekBar.max = 20  // 0-2000ms
        seekBar.progress = (defaultLongPressDuration / 100).toInt()
        tvDuration.text = "${seekBar.progress * 100}ms"
        
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val duration = (progress + 1) * 100  // 100-2100ms
                tvDuration.text = "${duration}ms"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        AlertDialog.Builder(context)
            .setTitle("设置长按时长")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                val duration = (seekBar.progress + 1) * 100L
                defaultLongPressDuration = duration
                val point = ClickPoint(
                    x = x,
                    y = y,
                    order = recordedPoints.size + 1,
                    type = OperationType.LONG_PRESS,
                    duration = duration
                )
                recordedPoints.add(point)
                onPointRecorded?.invoke(point)
                invalidate()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 显示等待设置对话框
     */
    private fun showWaitDialog() {
        val dialogView = android.view.LayoutInflater.from(context)
            .inflate(R.layout.dialog_wait, null)
        
        val seekBar = dialogView.findViewById<SeekBar>(R.id.seekWaitDuration)
        val tvDuration = dialogView.findViewById<TextView>(R.id.tvWaitDuration)
        
        // 设置初始值（厘秒为单位，显示为毫秒）
        seekBar.max = 30  // 0-3000ms
        seekBar.progress = (defaultWaitDuration / 100).toInt()
        tvDuration.text = "${seekBar.progress * 100}ms"
        
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val duration = (progress + 1) * 100  // 100-3100ms
                tvDuration.text = "${duration}ms"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        AlertDialog.Builder(context)
            .setTitle("设置等待时长")
            .setView(dialogView)
            .setPositiveButton("添加等待") { _, _ ->
                val duration = (seekBar.progress + 1) * 100L
                defaultWaitDuration = duration
                // 添加等待步骤（位置为0,0）
                val point = ClickPoint(
                    x = 0f,
                    y = 0f,
                    order = recordedPoints.size + 1,
                    type = OperationType.WAIT,
                    duration = duration
                )
                recordedPoints.add(point)
                onPointRecorded?.invoke(point)
                invalidate()
            }
            .setNegativeButton("取消", null)
            .show()
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
}
