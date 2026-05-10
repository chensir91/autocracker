package com.autoclicker.arknights.util

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
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
    
    /**
     * 执行长按拖动手势（先按住再拖动）
     * @param service AccessibilityService实例
     * @param startX 起始X坐标
     * @param startY 起始Y坐标
     * @param endX 结束X坐标
     * @param endY 结束Y坐标
     * @param duration 手势持续时间（毫秒），包含按住和拖动
     */
    fun longPressDrag(
        service: AccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        holdDuration: Long = 500,
        dragDuration: Long = 300
    ) {
        // 长按拖动：分两步派发
        // 第1步：在起点长按（willContinue=true，表示后续还有手势）
        val holdPath = Path()
        holdPath.moveTo(startX, startY)
        val holdStroke = GestureDescription.StrokeDescription(holdPath, 0, holdDuration, true)
        val holdGesture = GestureDescription.Builder()
            .addStroke(holdStroke)
            .build()
        
        service.dispatchGesture(holdGesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                // 第2步：长按完成，继续拖动到终点
                val dragPath = Path()
                dragPath.moveTo(startX, startY)
                dragPath.lineTo(endX, endY)
                val dragStroke = holdStroke.continueStroke(dragPath, holdDuration, dragDuration, false)
                val dragGesture = GestureDescription.Builder()
                    .addStroke(dragStroke)
                    .build()
                service.dispatchGesture(dragGesture, null, null)
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                // 长按被取消，不继续拖动
            }
        }, null)
    }
}
