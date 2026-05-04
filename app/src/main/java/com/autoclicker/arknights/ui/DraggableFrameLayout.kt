package com.autoclicker.arknights.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import android.view.WindowManager
import android.util.Log

/**
 * 可拖动的FrameLayout
 * 用于悬浮窗的拖动功能
 */
class DraggableFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private var dragThreshold = 10f // 移动超过这个距离才算拖动

    // 回调接口
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    
    // 录制模式标志 - 在录制模式下不拦截触摸事件，让触摸穿透到下面的游戏
    var touchTransparentMode = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 在触摸透明模式下，不拦截任何触摸事件，让事件传递到下面的游戏
        if (touchTransparentMode) {
            return false
        }
        
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.rawX
                lastY = ev.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(ev.rawX - lastX)
                val dy = Math.abs(ev.rawY - lastY)
                // 如果移动距离超过阈值，拦截触摸事件用于拖动
                if (dx > dragThreshold || dy > dragThreshold) {
                    isDragging = true
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 在触摸透明模式下，消耗所有触摸事件但不处理，让它们穿透
        if (touchTransparentMode) {
            return true
        }
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                
                // 如果开始拖动，通知回调
                if (!isDragging) {
                    val moveDistance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (moveDistance > dragThreshold) {
                        isDragging = true
                        onDragStart?.invoke()
                    }
                }
                
                if (isDragging) {
                    onDrag?.invoke(dx, dy)
                    lastX = event.rawX
                    lastY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    onDragEnd?.invoke()
                    isDragging = false
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 是否正在拖动
     */
    fun isDragging(): Boolean = isDragging
}
