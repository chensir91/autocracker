package com.autoclicker.arknights.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import com.autoclicker.arknights.util.ClickUtils

/**
 * 自动点击的无障碍服务
 * 负责执行实际的屏幕点击操作
 */
class AutoClickAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "AutoClickService"
        
        // 用于与服务通信的Action
        const val ACTION_CLICK = "com.autoclicker.arknights.ACTION_CLICK"
        const val ACTION_STOP = "com.autoclicker.arknights.ACTION_STOP"
        
        // Intent Extra keys
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        
        // 服务实例
        var instance: AutoClickAccessibilityService? = null
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Service destroyed")
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service connected")
        
        // 服务配置已通过XML完成，这里只做日志记录
        val info = serviceInfo
        Log.d(TAG, "Service configured: ${info?.feedbackType}")
    }
    
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // 本应用不需要处理界面事件
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
    
    /**
     * 执行点击操作
     * @param x X坐标
     * @param y Y坐标
     */
    fun performClick(x: Float, y: Float) {
        Log.d(TAG, "performClick: ($x, $y)")
        ClickUtils.click(this, x, y)
    }
    
    /**
     * 执行长按操作
     * @param x X坐标
     * @param y Y坐标
     * @param duration 按压时长（毫秒）
     */
    fun performLongPress(x: Float, y: Float, duration: Long = 500L) {
        Log.d(TAG, "performLongPress: ($x, $y, ${duration}ms)")
        
        val path = Path()
        path.moveTo(x, y)
        
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        
        dispatchGesture(
            gestureBuilder.build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Long press gesture completed")
                }
                
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Long press gesture cancelled")
                }
            },
            null
        )
    }
}
