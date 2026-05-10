package com.autoclicker.arknights.util

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlin.random.Random

/**
 * 点击工具类，用于通过AccessibilityService执行模拟点击
 */
object ClickUtils {
    
    /**
     * 在指定坐标执行点击
     * @param service AccessibilityService实例
     * @param x X坐标
     * @param y Y坐标
     * @param duration 点击持续时间（毫秒），默认50ms（≥50ms更可靠）
     * @param offsetRange 随机偏移范围（像素），0表示不偏移
     * @param callback 点击结果回调
     */
    fun click(
        service: AccessibilityService,
        x: Float,
        y: Float,
        duration: Long = 50,
        offsetRange: Int = 0,
        callback: ((Boolean) -> Unit)? = null
    ) {
        // 应用随机偏移
        val actualX = if (offsetRange > 0) {
            x + Random.nextInt(-offsetRange, offsetRange + 1)
        } else {
            x
        }
        val actualY = if (offsetRange > 0) {
            y + Random.nextInt(-offsetRange, offsetRange + 1)
        } else {
            y
        }
        
        val path = Path()
        path.moveTo(actualX, actualY)
        path.lineTo(actualX, actualY)
        
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
     * 执行长按操作
     * @param service AccessibilityService实例
     * @param x X坐标
     * @param y Y坐标
     * @param duration 按住时长（毫秒）
     * @param offsetRange 随机偏移范围（像素），0表示不偏移
     * @param callback 点击结果回调
     */
    fun longPress(
        service: AccessibilityService,
        x: Float,
        y: Float,
        duration: Long = 500,
        offsetRange: Int = 0,
        callback: ((Boolean) -> Unit)? = null
    ) {
        // 应用随机偏移
        val actualX = if (offsetRange > 0) {
            x + Random.nextInt(-offsetRange, offsetRange + 1)
        } else {
            x
        }
        val actualY = if (offsetRange > 0) {
            y + Random.nextInt(-offsetRange, offsetRange + 1)
        } else {
            y
        }
        
        val path = Path()
        path.moveTo(actualX, actualY)
        
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
