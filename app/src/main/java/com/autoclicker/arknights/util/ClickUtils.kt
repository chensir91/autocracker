package com.autoclicker.arknights.util

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.graphics.Path
import android.view.accessibility.GestureDescription
import android.view.accessibility.GestureDescription.StrokeDescription

/**
 * 点击工具类，用于通过AccessibilityService执行模拟点击
 */
object ClickUtils {
    
    /**
     * 在指定坐标执行点击
     * @param service AccessibilityService实例
     * @param x X坐标
     * @param y Y坐标
     * @param duration 点击持续时间（毫秒），默认10ms
     * @param callback 点击结果回调
     */
    fun click(
        service: AccessibilityService,
        x: Float,
        y: Float,
        duration: Long = 10,
        callback: ((Boolean) -> Unit)? = null
    ) {
        val path = Path()
        path.moveTo(x, y)
        path.lineTo(x, y)
        
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, duration)
        val gestureDescription = GestureDescription.Builder()
            .addStroke(strokeDescription)
            .build()
        
        service.dispatchGesture(
            gestureDescription,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    callback?.invoke(true)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription) {
                    callback?.invoke(false)
                }
            },
            null
        )
    }
    
    /**
     * 执行滑动手势
     * @param service AccessibilityService实例
     * @param startX 起始X坐标
     * @param startY 起始Y坐标
     * @param endX 结束X坐标
     * @param endY 结束Y坐标
     * @param duration 手势持续时间（毫秒）
     */
    fun swipe(
        service: AccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long = 300
    ) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, duration)
        val gestureDescription = GestureDescription.Builder()
            .addStroke(strokeDescription)
            .build()
        
        service.dispatchGesture(gestureDescription, null, null)
    }
}
