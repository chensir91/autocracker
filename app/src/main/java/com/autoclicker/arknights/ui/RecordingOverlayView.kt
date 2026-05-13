package com.autoclicker.arknights.ui

import android.annotation.SuppressLint
import android.content.Context
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
import android.widget.Toast
import com.autoclicker.arknights.data.ClickPoint
import com.autoclicker.arknights.data.OperationType

/**
 * 录制模式的覆盖层视图 v1.4.0
 * 用于在全屏显示录制状态，自动识别操作类型（点击/长按/等待/滑动）
 * 录制时拦截触摸并回放到游戏，实现边录制边操作
 * 点位样式：半透明淡绿色描边+序号
 */
class RecordingOverlayView(context: Context) : View(context) {
    
    // 覆盖层在屏幕上的偏移量（用于坐标转换）
    private var screenOffsetX = 0
    private var screenOffsetY = 0
    private var statusBarHeight = 0  // 状态栏高度，用于修正某些设备上getLocationOnScreen返回0的问题
    
    // 半透明淡绿色填充
    private val circlePaint = Paint().apply {
        color = Color.argb(80, 76, 175, 80)  // 半透明淡绿色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 淡绿色描边
    private val circleStrokePaint = Paint().apply {
        color = Color.parseColor("#81C784")  // 淡绿色描边
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    
    // 长按专用 - 半透明黄色填充
    private val longPressPaint = Paint().apply {
        color = Color.argb(80, 255, 193, 7)  // 半透明黄色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 长按描边
    private val longPressStrokePaint = Paint().apply {
        color = Color.parseColor("#FFD54F")  // 淡黄色描边
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    
    // 等待专用 - 半透明蓝色填充
    private val waitPaint = Paint().apply {
        color = Color.argb(80, 33, 150, 243)  // 半透明蓝色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 等待描边
    private val waitStrokePaint = Paint().apply {
        color = Color.parseColor("#64B5F6")  // 淡蓝色描边
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    
    // 滑动专用 - 半透明紫色填充
    private val swipePaint = Paint().apply {
        color = Color.argb(80, 156, 39, 176)  // 半透明紫色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 滑动描边
    private val swipeStrokePaint = Paint().apply {
        color = Color.parseColor("#CE93D8")  // 淡紫色描边
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    
    // 滑动箭头线
    private val swipeLinePaint = Paint().apply {
        color = Color.parseColor("#CE93D8")  // 淡紫色
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    // 箭头头部
    private val swipeArrowPaint = Paint().apply {
        color = Color.parseColor("#CE93D8")  // 淡紫色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 长按拖动专用 - 半透明橙色填充
    private val longPressDragPaint = Paint().apply {
        color = Color.argb(80, 255, 152, 0)  // 半透明橙色
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 长按拖动描边
    private val longPressDragStrokePaint = Paint().apply {
        color = Color.parseColor("#FFB74D")  // 淡橙色描边
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    
    // 长按拖动箭头线
    private val longPressDragLinePaint = Paint().apply {
        color = Color.parseColor("#FFB74D")  // 淡橙色
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    // 长按拖动箭头头部
    private val longPressDragArrowPaint = Paint().apply {
        color = Color.parseColor("#FFB74D")  // 淡橙色
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
    var onPlaybackNeeded: ((x: Float, y: Float, type: OperationType, duration: Long, endX: Float, endY: Float) -> Unit)? = null
    
    // 时间追踪变量（自动识别操作类型）
    private var touchDownTime = 0L  // 按下时间
    private var lastPointTime = 0L  // 上一个点位记录的时间
    private var touchDownX = 0f     // 按下位置
    private var touchDownY = 0f     // 按下位置
    private var touchCurrentX = 0f  // 当前触摸位置（用于追踪移动）
    private var touchCurrentY = 0f  // 当前触摸位置
    
    companion object {
        private const val LONG_PRESS_THRESHOLD = 500L  // 长按阈值：500ms
        private const val WAIT_THRESHOLD = 300L        // 自动等待阈值：300ms
        private const val SWIPE_DISTANCE_THRESHOLD = 30f  // 滑动距离阈值：30px
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private var instructionShown = true
    
    // 停止按钮区域
    
    // 停止按钮尺寸和位置
    
    // 撤销按钮区域
    
    // 撤销按钮尺寸和位置
    
    init {
        // 设置为可以接收触摸事件
        isClickable = true
        isFocusable = true
    }
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateScreenOffset()
    }
    
    private fun updateScreenOffset() {
        val location = IntArray(2)
        getLocationOnScreen(location)
        screenOffsetX = location[0]
        screenOffsetY = location[1]
        
        // 修复某些设备上getLocationOnScreen返回0但view实际从状态栏下方开始的问题
        if (screenOffsetY == 0) {
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) {
                statusBarHeight = resources.getDimensionPixelSize(resourceId)
            }
        }
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateScreenOffset()
        // 计算停止按钮位置（右上角）



        
        // 计算撤销按钮位置（停止按钮下方）



    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // 屏幕坐标转View坐标的辅助函数
        val drawX = { px: Float -> px - screenOffsetX }
        val drawY = { py: Float -> py - screenOffsetY - statusBarHeight }
        
        // 绘制所有已录制的点位
        for (point in recordedPoints) {
            when (point.type) {
                OperationType.CLICK -> {
                    canvas.drawCircle(drawX(point.x), drawY(point.y), 40f, circlePaint)
                    canvas.drawCircle(drawX(point.x), drawY(point.y), 40f, circleStrokePaint)
                    // 绘制编号
                    val textY = drawY(point.y) + (textPaint.textSize / 3)
                    canvas.drawText(point.order.toString(), drawX(point.x), textY, textPaint)
                }
                OperationType.LONG_PRESS -> {
                    canvas.drawCircle(drawX(point.x), drawY(point.y), 40f, longPressPaint)
                    canvas.drawCircle(drawX(point.x), drawY(point.y), 40f, longPressStrokePaint)
                    // 绘制编号
                    val textY = drawY(point.y) + (textPaint.textSize / 3)
                    canvas.drawText(point.order.toString(), drawX(point.x), textY, textPaint)
                    // 绘制类型标签
                    canvas.drawText("长", drawX(point.x), drawY(point.y) + 60f, typeLabelPaint)
                }
                OperationType.WAIT -> {
                    // 等待点在屏幕中央上方绘制
                    val centerX = width / 2f
                    val waitY = 120f
                    canvas.drawCircle(centerX, waitY, 30f, waitPaint)
                    canvas.drawCircle(centerX, waitY, 30f, waitStrokePaint)
                    val textY = waitY + (textPaint.textSize / 3)
                    canvas.drawText(point.order.toString(), centerX, textY, textPaint)
                    canvas.drawText("等${point.duration}ms", centerX, waitY - 45f, typeLabelPaint)
                }
                OperationType.SWIPE -> {
                    // 绘制滑动起点圆
                    canvas.drawCircle(drawX(point.x), drawY(point.y), 30f, swipePaint)
                    canvas.drawCircle(drawX(point.x), drawY(point.y), 30f, swipeStrokePaint)
                    
                    // 绘制滑动线
                    canvas.drawLine(drawX(point.x), drawY(point.y), drawX(point.endX), drawY(point.endY), swipeLinePaint)
                    
                    // 绘制箭头头部
                    drawArrowHead(canvas, drawX(point.x), drawY(point.y), drawX(point.endX), drawY(point.endY), swipeArrowPaint)
                    
                    // 绘制终点小圆
                    canvas.drawCircle(drawX(point.endX), drawY(point.endY), 20f, swipePaint)
                    canvas.drawCircle(drawX(point.endX), drawY(point.endY), 20f, swipeStrokePaint)
                    
                    // 序号标注在中间
                    val midX = (drawX(point.x) + drawX(point.endX)) / 2
                    val midY = (drawY(point.y) + drawY(point.endY)) / 2
                    val textY = midY + (textPaint.textSize / 3)
                    canvas.drawText(point.order.toString(), midX, textY, textPaint)
                    
                    // 类型标签
                    canvas.drawText("滑", midX, midY + 50f, typeLabelPaint)
                }
                OperationType.LONG_PRESS_DRAG -> {
                    // 绘制长按拖动起点圆
                    canvas.drawCircle(drawX(point.x), drawY(point.y), 30f, longPressDragPaint)
                    canvas.drawCircle(drawX(point.x), drawY(point.y), 30f, longPressDragStrokePaint)
                    
                    // 绘制拖动线
                    canvas.drawLine(drawX(point.x), drawY(point.y), drawX(point.endX), drawY(point.endY), longPressDragLinePaint)
                    
                    // 绘制箭头头部
                    drawArrowHead(canvas, drawX(point.x), drawY(point.y), drawX(point.endX), drawY(point.endY), longPressDragArrowPaint)
                    
                    // 绘制终点小圆
                    canvas.drawCircle(drawX(point.endX), drawY(point.endY), 20f, longPressDragPaint)
                    canvas.drawCircle(drawX(point.endX), drawY(point.endY), 20f, longPressDragStrokePaint)
                    
                    // 序号标注在中间
                    val midX = (drawX(point.x) + drawX(point.endX)) / 2
                    val midY = (drawY(point.y) + drawY(point.endY)) / 2
                    val textY = midY + (textPaint.textSize / 3)
                    canvas.drawText(point.order.toString(), midX, textY, textPaint)
                    
                    // 类型标签
                    canvas.drawText("拖", midX, midY + 50f, typeLabelPaint)
                OperationType.WAIT_PIXEL -> {
                    // 等像素和等待用同样的样式
                    canvas.drawCircle(drawX(point.x), drawY(point.y), 30f, waitPaint)
                    canvas.drawCircle(drawX(point.x), drawY(point.y), 30f, waitStrokePaint)
                }
                OperationType.MULTI_CLICK -> {
                    // 连击和点击用同样的样式
                    canvas.drawCircle(drawX(point.x), drawY(point.y), 30f, clickPaint)
                    canvas.drawCircle(drawX(point.x), drawY(point.y), 30f, clickStrokePaint)
                }
                }
            }
        }
        
        // 绘制提示信息
        if (instructionShown) {
            val infoY = height / 2f
            infoPaint.alpha = 200
            canvas.drawText("录制模式", width / 2f, infoY - 80, infoPaint)
            
            infoPaint.textSize = 28f
            infoPaint.alpha = 150
            canvas.drawText("点击/长按/滑动自动识别", width / 2f, infoY - 30, infoPaint)
            canvas.drawText("操作将同步回放到游戏", width / 2f, infoY + 10, infoPaint)
            canvas.drawText("点击红色按钮结束录制", width / 2f, infoY + 50, infoPaint)
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
                        infoPaint.textSize = 40f
            infoPaint.alpha = 255
        }
        
        // 绘制停止按钮
        
        // 绘制撤销按钮
    }
    
    /**
     * 绘制箭头头部
     */
    private fun drawArrowHead(canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float, paint: Paint = swipeArrowPaint) {
        val angle = Math.atan2((toY - fromY).toDouble(), (toX - fromX).toDouble())
        val arrowLen = 20f
        val arrowAngle = Math.PI / 6  // 30度
        
        val x1 = toX - arrowLen * Math.cos(angle - arrowAngle).toFloat()
        val y1 = toY - arrowLen * Math.sin(angle - arrowAngle).toFloat()
        val x2 = toX - arrowLen * Math.cos(angle + arrowAngle).toFloat()
        val y2 = toY - arrowLen * Math.sin(angle + arrowAngle).toFloat()
        
        val path = Path()
        path.moveTo(toX, toY)
        path.lineTo(x1, y1)
        path.lineTo(x2, y2)
        path.close()
        canvas.drawPath(path, paint)
    }
    
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rawX = event.rawX  // 屏幕坐标，用于记录点位和回放
        val rawY = event.rawY
        val x = event.x        // View内坐标，用于按钮区域判断
        val y = event.y
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {

                
                instructionShown = false
                
                // 记录按下时间和位置（用屏幕坐标）
                touchDownTime = System.currentTimeMillis()
                touchDownX = rawX
                touchDownY = rawY
                touchCurrentX = rawX
                touchCurrentY = rawY
                return true
            }
            
            MotionEvent.ACTION_MOVE -> {
                // 追踪移动位置（用屏幕坐标）
                touchCurrentX = rawX
                touchCurrentY = rawY
                return true
            }
            
            MotionEvent.ACTION_UP -> {

                
                // 计算按压时长
                val pressDuration = System.currentTimeMillis() - touchDownTime
                val currentTime = System.currentTimeMillis()
                
                // 计算移动距离（用屏幕坐标）
                val dx = rawX - touchDownX
                val dy = rawY - touchDownY
                val moveDistance = kotlin.math.sqrt(dx * dx + dy * dy)
                
                // 检查是否需要自动插入等待步骤
                if (lastPointTime > 0 && recordedPoints.isNotEmpty()) {
                    val gapMs = currentTime - lastPointTime
                    if (gapMs > WAIT_THRESHOLD) {
                        // 自动插入等待步骤
                        val waitPoint = ClickPoint(
                            x = 0f,
                            y = 0f,
                            order = recordedPoints.size + 1,
                            type = OperationType.WAIT,
                            duration = gapMs
                        )
                        recordedPoints.add(waitPoint)
                        onPointRecorded?.invoke(waitPoint)
                    }
                }
                
                // 根据移动距离和按压时长判断操作类型（坐标用屏幕坐标）
                if (moveDistance > SWIPE_DISTANCE_THRESHOLD) {
                    // 有移动时的判断：按压>=500ms → LONG_PRESS_DRAG，否则 → SWIPE
                    if (pressDuration >= LONG_PRESS_THRESHOLD) {
                        // 长按拖动（先按后拖）
                        val point = ClickPoint(
                            x = touchDownX,
                            y = touchDownY,
                            order = recordedPoints.size + 1,
                            type = OperationType.LONG_PRESS_DRAG,
                            duration = pressDuration,
                            endX = rawX,
                            endY = rawY
                        )
                        recordedPoints.add(point)
                        onPointRecorded?.invoke(point)
                        
                        // 回放长按拖动到游戏
                        onPlaybackNeeded?.invoke(touchDownX, touchDownY, OperationType.LONG_PRESS_DRAG, pressDuration, rawX, rawY)
                    } else {
                        // 滑动操作（快速滑动）
                        val point = ClickPoint(
                            x = touchDownX,
                            y = touchDownY,
                            order = recordedPoints.size + 1,
                            type = OperationType.SWIPE,
                            duration = pressDuration,
                            endX = rawX,
                            endY = rawY
                        )
                        recordedPoints.add(point)
                        onPointRecorded?.invoke(point)
                        
                        // 回放滑动到游戏
                        onPlaybackNeeded?.invoke(touchDownX, touchDownY, OperationType.SWIPE, pressDuration, rawX, rawY)
                    }
                } else if (pressDuration >= LONG_PRESS_THRESHOLD) {
                    // 长按
                    val point = ClickPoint(
                        x = touchDownX,
                        y = touchDownY,
                        order = recordedPoints.size + 1,
                        type = OperationType.LONG_PRESS,
                        duration = pressDuration
                    )
                    recordedPoints.add(point)
                    onPointRecorded?.invoke(point)
                    
                    // 回放长按到游戏
                    onPlaybackNeeded?.invoke(touchDownX, touchDownY, OperationType.LONG_PRESS, pressDuration, 0f, 0f)
                } else {
                    // 普通点击
                    val point = ClickPoint(
                        x = touchDownX,
                        y = touchDownY,
                        order = recordedPoints.size + 1,
                        type = OperationType.CLICK
                    )
                    recordedPoints.add(point)
                    onPointRecorded?.invoke(point)
                    
                    // 回放点击到游戏
                    onPlaybackNeeded?.invoke(touchDownX, touchDownY, OperationType.CLICK, 10, 0f, 0f)
                }
                
                lastPointTime = currentTime
                invalidate()
                return true
            }
            
            MotionEvent.ACTION_CANCEL -> {
                invalidate()
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
        lastPointTime = 0L
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
