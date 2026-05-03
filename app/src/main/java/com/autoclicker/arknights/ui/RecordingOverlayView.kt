package com.autoclicker.arknights.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.autoclicker.arknights.data.ClickPoint

/**
 * 录制模式的覆盖层视图
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
    
    private val recordedPoints = mutableListOf<ClickPoint>()
    
    var onPointRecorded: ((ClickPoint) -> Unit)? = null
    var onFinishRecording: (() -> Unit)? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var instructionShown = true
    
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
            // 绘制圆形
            canvas.drawCircle(point.x, point.y, 40f, circlePaint)
            // 绘制编号
            val textY = point.y + (textPaint.textSize / 3)
            canvas.drawText(point.order.toString(), point.x, textY, textPaint)
        }
        
        // 绘制提示信息
        if (instructionShown) {
            val infoY = height / 2f
            infoPaint.alpha = 200
            canvas.drawText("录制模式", width / 2f, infoY - 30, infoPaint)
            
            infoPaint.textSize = 28f
            infoPaint.alpha = 150
            canvas.drawText("点击屏幕录制点位", width / 2f, infoY + 20, infoPaint)
            canvas.drawText("点击任意位置开始", width / 2f, infoY + 60, infoPaint)
            infoPaint.textSize = 40f
            infoPaint.alpha = 255
        }
        
        // 绘制底部提示
        if (recordedPoints.isNotEmpty()) {
            val bottomText = "已录制 ${recordedPoints.size} 个点位"
            infoPaint.textSize = 32f
            infoPaint.alpha = 200
            canvas.drawText(bottomText, width / 2f, height - 100f, infoPaint)
            
            infoPaint.textSize = 24f
            infoPaint.alpha = 150
            canvas.drawText("再次点击悬浮窗结束录制", width / 2f, height - 60f, infoPaint)
            infoPaint.textSize = 40f
            infoPaint.alpha = 255
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                instructionShown = false
                val point = ClickPoint(
                    x = event.x,
                    y = event.y,
                    order = recordedPoints.size + 1
                )
                recordedPoints.add(point)
                onPointRecorded?.invoke(point)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    
    /**
     * 清空所有录制的点位
     */
    fun clearPoints() {
        recordedPoints.clear()
        instructionShown = true
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
